package com.example.dronesoundalert

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.*
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONArray
import org.json.JSONObject
import org.jtransforms.fft.DoubleFFT_1D
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

class DroneDetectionService : Service() {

    companion object {
        const val TAG = "DroneDetectionService"
        const val ACTION_START_GNSS = "START_GNSS"
        const val ACTION_STOP_GNSS = "STOP_GNSS"
        const val ACTION_STOP_AUDIO = "STOP_AUDIO"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_RESET_GNSS_BASELINE = "RESET_GNSS_BASELINE"
        const val ACTION_RESET_COOLDOWN = "RESET_COOLDOWN"
        const val ACTION_RELOAD_SETTINGS = "RELOAD_SETTINGS"
        
        var isAudioRunning = false
        var isGnssRunning = false
        
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SOURCE_FILE = -2
    }

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "DroneDetectionChannel"

    // Audio settings
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val fftSize = 4096
    private var bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).let {
        if (it < fftSize) fftSize * 2 else it
    }

    private var audioRecord: AudioRecord? = null
    private val fft = DoubleFFT_1D(fftSize.toLong())
    private var droneDetectedStartTime: Long = 0
    private var lastAudioAlertTime: Long = 0
    private var isAudioCooldownActive = false

    // TensorFlow Lite
    private var tfliteInterpreter: Interpreter? = null
    private var useTfLite = false
    private var tfliteThreshold = 0.70f
    private var tfliteConsecutiveThreshold = 3
    private var currentConsecutiveDetections = 0
    
    private var tfliteGain = 1.0f
    private var tfliteMinVolume = 100
    private var tfliteWindowSize = 1.0f
    private var tfliteSmoothingCount = 1
    private val confidenceHistory = LinkedList<Float>()
    
    private var useVisualizer = true
    private var alertDurationSeconds = 1
    private var alertCooldownMinutes = 1
    private var gnssSensitivity = 18f
    private var gnssBaselineDurationMs = 30000L
    
    // Audio analysis thresholds (cached)
    private var audioAnalysisMultiplier = 15
    private var audioAnalysisMinScore = 5
    private var audioMinFreq = 2000
    private var audioMaxFreq = 8000

    // GNSS settings
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var lastGnssAlertTime: Long = 0
    private val GNSS_ALERT_COOLDOWN = 60000L
    
    private var prevAvgCn0: Float = -1f
    private var prevSatCount: Int = -1
    private val prevConstellationAvgs = mutableMapOf<Int, Float>()
    
    private var baselineCn0: Float = -1f
    private val baselineValues = mutableListOf<Float>()
    private var baselineStartTime: Long = 0

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            ACTION_STOP_SERVICE -> {
                stopAudioListening()
                stopGnssMonitoring()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_GNSS -> startGnssMonitoring()
            ACTION_STOP_GNSS -> stopGnssMonitoring()
            ACTION_STOP_AUDIO -> stopAudioListening()
            ACTION_RESET_GNSS_BASELINE -> resetGnssBaseline()
            ACTION_RESET_COOLDOWN -> resetCooldown()
            ACTION_RELOAD_SETTINGS -> reloadSettings()
            else -> startAudioListening()
        }

        updateForegroundNotification()
        return START_STICKY
    }

    private fun updateForegroundNotification() {
        createNotificationChannel()
        val status = when {
            isAudioRunning && isGnssRunning -> getString(R.string.service_status_both)
            isAudioRunning -> getString(R.string.service_status_audio)
            isGnssRunning -> getString(R.string.service_status_gps)
            else -> getString(R.string.service_status_waiting)
        }
        
        val notification = createNotification(status)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0
            if (isAudioRunning) serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (isGnssRunning) serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            
            if (serviceType != 0) {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                stopForeground(true)
                stopSelf()
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun resetCooldown() {
        isAudioCooldownActive = false
        lastAudioAlertTime = 0
    }

    private fun reloadSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        
        // Audio
        useTfLite = prefs.getBoolean("use_tflite", false)
        tfliteThreshold = prefs.getFloat("tflite_threshold", 0.70f)
        tfliteConsecutiveThreshold = prefs.getInt("tflite_consecutive_count", 3)
        tfliteGain = prefs.getFloat("tflite_gain", 1.0f)
        tfliteMinVolume = prefs.getInt("tflite_min_volume", 100)
        tfliteWindowSize = prefs.getFloat("tflite_window_size", 1.0f)
        tfliteSmoothingCount = prefs.getInt("tflite_smoothing", 1)
        
        useVisualizer = prefs.getBoolean("use_spectrogram", true) || prefs.getBoolean("use_oscilloscope", false)
        alertDurationSeconds = prefs.getInt("alert_duration_seconds", 1)
        alertCooldownMinutes = prefs.getInt("alert_cooldown_minutes", 1)
        
        // GNSS
        gnssSensitivity = prefs.getFloat("gnss_sensitivity", 18f)
        gnssBaselineDurationMs = prefs.getInt("gnss_baseline_duration", 30) * 1000L
        
        // Audio Analysis Thresholds
        audioAnalysisMultiplier = prefs.getInt("audio_analysis_multiplier", 15)
        audioAnalysisMinScore = prefs.getInt("audio_analysis_min_score", 5)
        audioMinFreq = prefs.getInt("audio_min_freq", 2000)
        audioMaxFreq = prefs.getInt("audio_max_freq", 8000)
        
        // Re-init TfLite if needed
        if (useTfLite && tfliteInterpreter == null) {
            initTfLite()
        } else if (!useTfLite && tfliteInterpreter != null) {
            tfliteInterpreter?.close()
            tfliteInterpreter = null
        }
        
        // Reset state to apply changes cleanly
        resetCooldown()
        currentConsecutiveDetections = 0
        confidenceHistory.clear()
        droneDetectedStartTime = 0
        
        Log.d(TAG, "Settings reloaded and state reset")
    }

    private fun SharedPreferences.getSafeFloat(key: String, defValue: Float): Float {
        return try {
            this.getFloat(key, defValue)
        } catch (e: Exception) {
            this.all[key]?.toString()?.toFloatOrNull() ?: defValue
        }
    }

    private fun SharedPreferences.getSafeInt(key: String, defValue: Int): Int {
        return try {
            this.getInt(key, defValue)
        } catch (e: Exception) {
            this.all[key]?.toString()?.toIntOrNull() ?: defValue
        }
    }

    private fun SharedPreferences.getSafeString(key: String, defValue: String): String {
        return try {
            this.getString(key, defValue) ?: defValue
        } catch (e: Exception) {
            this.all[key]?.toString() ?: defValue
        }
    }

    private fun startAudioListening() {
        if (isAudioRunning) return

        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val audioSource = try { prefs.getInt("audio_source", MediaRecorder.AudioSource.MIC) } catch(e: Exception) { prefs.getSafeString("audio_source", "1").toIntOrNull() ?: MediaRecorder.AudioSource.MIC }

        if (audioSource == SOURCE_FILE) {
            val uriStr = prefs.getSafeString("audio_file_uri", "")
            if (uriStr.isNotEmpty()) {
                startFileListening(uriStr)
                return
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        useTfLite = prefs.getBoolean("use_tflite", false)
        tfliteThreshold = prefs.getSafeFloat("tflite_threshold", 0.70f)
        tfliteConsecutiveThreshold = prefs.getSafeInt("tflite_consecutive_count", 3)
        tfliteGain = prefs.getSafeFloat("tflite_gain", 1.0f)
        tfliteMinVolume = prefs.getSafeInt("tflite_min_volume", 100)
        tfliteWindowSize = prefs.getSafeFloat("tflite_window_size", 1.0f)
        tfliteSmoothingCount = prefs.getSafeInt("tflite_smoothing", 1)
        
        useVisualizer = prefs.getBoolean("use_spectrogram", true) || prefs.getBoolean("use_oscilloscope", false)
        alertDurationSeconds = prefs.getSafeInt("alert_duration_seconds", 1)
        alertCooldownMinutes = prefs.getSafeInt("alert_cooldown_minutes", 1)
        
        val spectrogramBins = 256
        
        currentConsecutiveDetections = 0
        confidenceHistory.clear()

        if (useTfLite) initTfLite()

        val windowSampleCount = (sampleRate * tfliteWindowSize).toInt()
        val effectiveBufferSize = if (useTfLite && windowSampleCount > bufferSize) windowSampleCount * 2 else bufferSize

        try {
            audioRecord = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, effectiveBufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

            isAudioRunning = true
            Thread {
                val currentWindowData = ShortArray(if (useTfLite) windowSampleCount else fftSize)
                val fftBuffer = DoubleArray(fftSize)
                audioRecord?.startRecording()

                while (isAudioRunning) {
                    val readSize = audioRecord?.read(currentWindowData, 0, currentWindowData.size) ?: 0
                    if (readSize == currentWindowData.size) {
                        processAudioChunk(currentWindowData, fftBuffer, spectrogramBins)
                    }
                }
            }.start()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startFileListening(uriString: String) {
        isAudioRunning = true
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val playThroughSpeaker = prefs.getBoolean("play_file_through_speaker", false)
        
        useTfLite = prefs.getBoolean("use_tflite", false)
        tfliteThreshold = prefs.getSafeFloat("tflite_threshold", 0.70f)
        tfliteConsecutiveThreshold = prefs.getSafeInt("tflite_consecutive_count", 3)
        tfliteGain = prefs.getSafeFloat("tflite_gain", 1.0f)
        tfliteMinVolume = prefs.getSafeInt("tflite_min_volume", 100)
        tfliteWindowSize = prefs.getSafeFloat("tflite_window_size", 1.0f)
        tfliteSmoothingCount = prefs.getSafeInt("tflite_smoothing", 1)
        
        useVisualizer = prefs.getBoolean("use_spectrogram", true) || prefs.getBoolean("use_oscilloscope", false)
        alertDurationSeconds = prefs.getSafeInt("alert_duration_seconds", 1)
        alertCooldownMinutes = prefs.getSafeInt("alert_cooldown_minutes", 1)
        
        val spectrogramBins = 256
        
        if (useTfLite) initTfLite()
        
        Thread {
            var audioTrack: AudioTrack? = null
            if (playThroughSpeaker) {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                audioTrack.play()
            }

            try {
                val uri = Uri.parse(uriString)
                val extractor = MediaExtractor()
                extractor.setDataSource(applicationContext, uri, null)
                
                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        trackIndex = i
                        extractor.selectTrack(i)
                        break
                    }
                }
                
                if (trackIndex == -1) {
                    extractor.release()
                    return@Thread
                }

                val format = extractor.getTrackFormat(trackIndex)
                val fileSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()

                if (playThroughSpeaker) {
                    audioTrack?.release()
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(fileSampleRate)
                            .setChannelMask(if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                    audioTrack?.play()
                }

                val info = MediaCodec.BufferInfo()
                val windowSampleCount = (sampleRate * tfliteWindowSize).toInt()
                val audioData = ShortArray(if (useTfLite) windowSampleCount else fftSize)
                var audioDataPtr = 0
                
                val fftBuffer = DoubleArray(fftSize)

                while (isAudioRunning) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            val reReadSize = extractor.readSampleData(inputBuffer, 0)
                            if (reReadSize > 0) {
                                codec.queueInputBuffer(inputIndex, 0, reReadSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        val chunk = ShortArray(info.size / 2)
                        outputBuffer.asShortBuffer().get(chunk)
                        
                        if (playThroughSpeaker && audioTrack != null) {
                            audioTrack.write(chunk, 0, chunk.size)
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (channelCount == 2) {
                            for (i in 0 until chunk.size step 2) {
                                val left = chunk[i].toInt()
                                val right = if (i + 1 < chunk.size) chunk[i + 1].toInt() else left
                                val mono = ((left + right) / 2).toShort()
                                
                                audioData[audioDataPtr++] = mono
                                if (audioDataPtr == audioData.size) {
                                    processAudioChunk(audioData, fftBuffer, spectrogramBins)
                                    audioDataPtr = 0
                                    if (!playThroughSpeaker) {
                                        val chunkDurationMs = (audioData.size.toDouble() / fileSampleRate * 1000).toLong()
                                        Thread.sleep(chunkDurationMs)
                                    }
                                }
                            }
                        } else {
                            for (sample in chunk) {
                                audioData[audioDataPtr++] = sample
                                if (audioDataPtr == audioData.size) {
                                    processAudioChunk(audioData, fftBuffer, spectrogramBins)
                                    audioDataPtr = 0
                                    if (!playThroughSpeaker) {
                                        val chunkDurationMs = (audioData.size.toDouble() / fileSampleRate * 1000).toLong()
                                        Thread.sleep(chunkDurationMs)
                                    }
                                }
                            }
                        }
                    }
                }
                
                codec.stop()
                codec.release()
                extractor.release()
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun processAudioChunk(currentWindowData: ShortArray, fftBuffer: DoubleArray, spectrogramBins: Int) {
        var maxAmp = 0
        for (i in 0 until currentWindowData.size) {
            val value = abs(currentWindowData[i].toInt())
            if (value > maxAmp) maxAmp = value
        }

        // Always calculate peakFreq for UI
        for (i in 0 until fftSize) {
            fftBuffer[i] = if (i < currentWindowData.size) currentWindowData[i].toDouble() else 0.0
        }
        fft.realForward(fftBuffer)
        val magnitudes = DoubleArray(fftSize / 2)
        var maxMag = 0.0
        var peakFreq = 0
        for (i in 0 until fftSize / 2) {
            val re = fftBuffer[2 * i]
            val im = fftBuffer[2 * i + 1]
            magnitudes[i] = sqrt(re * re + im * im)
            if (magnitudes[i] > maxMag) {
                maxMag = magnitudes[i]
                peakFreq = (i * sampleRate / fftSize)
            }
        }

        var droneDetectedInFrame = false
        var confidence = 0f

        if (useTfLite && tfliteInterpreter != null) {
            if (maxAmp >= tfliteMinVolume) {
                val rawConfidence = runTfLiteInference(currentWindowData)
                confidenceHistory.add(rawConfidence)
                if (confidenceHistory.size > tfliteSmoothingCount) confidenceHistory.removeAt(0)
                confidence = confidenceHistory.average().toFloat()
                if (confidence >= tfliteThreshold) currentConsecutiveDetections++ else currentConsecutiveDetections = 0
            } else {
                confidence = 0f
                currentConsecutiveDetections = 0
                confidenceHistory.clear()
            }
            droneDetectedInFrame = currentConsecutiveDetections >= tfliteConsecutiveThreshold
        } else {
            droneDetectedInFrame = analyzeAudioFrequencies(magnitudes)
        }

        val currentTime = System.currentTimeMillis()
        val cooldownMillis = KYMS(alertCooldownMinutes)

        if (isAudioCooldownActive && currentTime - lastAudioAlertTime > cooldownMillis) {
            isAudioCooldownActive = false
        }

        val updateIntent = Intent("DRONE_UPDATE")
        updateIntent.setPackage(packageName)
        updateIntent.putExtra("maxAmp", maxAmp)
        updateIntent.putExtra("peakFreq", peakFreq)
        updateIntent.putExtra("isCooldown", isAudioCooldownActive)
        updateIntent.putExtra("isTfLite", useTfLite)
        updateIntent.putExtra("confidence", confidence)
        updateIntent.putExtra("consecutive", currentConsecutiveDetections)
        updateIntent.putExtra("detectedInFrame", droneDetectedInFrame)
        updateIntent.putExtra("isDetecting", droneDetectedStartTime != 0L)
        
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val minFreq = prefs.getInt("audio_min_freq", 2000)
        val maxFreq = prefs.getInt("audio_max_freq", 8000)
        updateIntent.putExtra("minFreq", minFreq)
        updateIntent.putExtra("maxFreq", maxFreq)
        
        if (useVisualizer) {
            val downsampled = FloatArray(spectrogramBins)
            val step = (magnitudes.size / 2) / spectrogramBins.toFloat()
            for (i in 0 until spectrogramBins) {
                val startIdx = (i * step).toInt()
                val endIdx = ((i + 1) * step).toInt().coerceAtMost(magnitudes.size / 2)
                var sum = 0f
                var count = 0
                for (j in startIdx until endIdx) {
                    sum += magnitudes[j].toFloat()
                    count++
                }
                downsampled[i] = if (count > 0) sum / count else 0f
            }
            updateIntent.putExtra("spectrogram", downsampled)
        }
        
        if (!isAudioCooldownActive && droneDetectedInFrame) {
            if (droneDetectedStartTime == 0L) droneDetectedStartTime = currentTime
            val elapsed = (currentTime - droneDetectedStartTime) / 1000
            updateIntent.putExtra("isDetecting", true)
            updateIntent.putExtra("remaining", (alertDurationSeconds - elapsed).coerceAtLeast(0))

            if (elapsed >= alertDurationSeconds) {
                val alertMsg = getString(R.string.alert_drone_detected)
                triggerAlert(alertMsg, if (useTfLite) (confidence * 100).toInt() else peakFreq)
                isAudioCooldownActive = true
                lastAudioAlertTime = currentTime
                droneDetectedStartTime = 0
                updateIntent.putExtra("triggered", true)
                currentConsecutiveDetections = 0
            }
        } else {
            droneDetectedStartTime = 0
            updateIntent.putExtra("isDetecting", false)
        }
        sendBroadcast(updateIntent)
    }

    private fun KYMS(minutes: Int): Long = minutes * 60 * 1000L

    private fun initTfLite() {
        try {
            val modelFile = File(filesDir, "model.tflite")
            if (modelFile.exists()) {
                tfliteInterpreter = Interpreter(modelFile, Interpreter.Options())
            } else {
                useTfLite = false
            }
        } catch (e: Exception) {
            useTfLite = false
        }
    }

    private fun runTfLiteInference(audioData: ShortArray): Float {
        val interpreter = tfliteInterpreter ?: return 0f
        val inputBuffer = ByteBuffer.allocateDirect(4 * audioData.size)
        inputBuffer.order(ByteOrder.nativeOrder())
        for (sample in audioData) {
            val normalized = (sample * tfliteGain) / 32768.0f
            inputBuffer.putFloat(normalized.coerceIn(-1.0f, 1.0f))
        }
        val outValues = Array(1) { FloatArray(1) }
        try {
            interpreter.run(inputBuffer, outValues)
            return outValues[0][0]
        } catch (e: Exception) {
            return 0f
        }
    }

    private fun stopAudioListening() {
        isAudioRunning = false
        audioRecord?.apply {
            try { stop() } catch (e: Exception) {}
            release()
        }
        audioRecord = null
        tfliteInterpreter?.close()
        tfliteInterpreter = null
    }

    private fun startGnssMonitoring() {
        if (isGnssRunning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        isGnssRunning = true
        resetGnssBaseline()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    processGnssStatus(status)
                }
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, locationListener)
                locationManager.registerGnssStatusCallback(gnssStatusCallback!!, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {}
        }
    }

    private fun stopGnssMonitoring() {
        isGnssRunning = false
        try {
            locationManager.removeUpdates(locationListener)
            gnssStatusCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        } catch (e: Exception) {}
    }

    private fun resetGnssBaseline() {
        baselineCn0 = -1f
        baselineValues.clear()
        baselineStartTime = System.currentTimeMillis()
        prevAvgCn0 = -1f
        prevSatCount = -1
        prevConstellationAvgs.clear()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun processGnssStatus(status: GnssStatus) {
        val currentTime = System.currentTimeMillis()
        val count = status.satelliteCount
        val cn0Values = mutableListOf<Float>()
        val constellationSums = mutableMapOf<Int, Float>()
        val constellationCounts = mutableMapOf<Int, Int>()
        var totalCn0 = 0f
        var usedInFixCount = 0
        
        for (i in 0 until count) {
            val cn0 = status.getCn0DbHz(i)
            val type = status.getConstellationType(i)
            if (cn0 > 0) {
                totalCn0 += cn0
                cn0Values.add(cn0)
                constellationSums[type] = (constellationSums[type] ?: 0f) + cn0
                constellationCounts[type] = (constellationCounts[type] ?: 0) + 1
            }
            if (status.usedInFix(i)) usedInFixCount++
        }
        
        val avgCn0 = if (cn0Values.isNotEmpty()) totalCn0 / cn0Values.size else 0f

        if (baselineCn0 < 0) {
            if (cn0Values.isNotEmpty()) baselineValues.add(avgCn0)
            val elapsed = currentTime - baselineStartTime
            if (elapsed >= gnssBaselineDurationMs && baselineValues.isNotEmpty()) {
                baselineCn0 = baselineValues.average().toFloat()
            }
            val intent = Intent("GNSS_UPDATE")
            intent.setPackage(packageName)
            intent.putExtra("isBaselinePhase", true)
            intent.putExtra("baselineProgress", (elapsed * 100 / gnssBaselineDurationMs).toInt().coerceAtMost(100))
            intent.putExtra("avgCn0", avgCn0)
            intent.putExtra("count", count)
            intent.putExtra("usedInFixCount", usedInFixCount)
            sendBroadcast(intent)
            return
        }

        var stdDev = 0.0
        if (cn0Values.size > 1) {
            var sumSquares = 0.0
            for (v in cn0Values) sumSquares += (v - avgCn0) * (v - avgCn0)
            stdDev = sqrt(sumSquares / cn0Values.size)
        }

        var allSystemsDropped = false
        val currentConstellationAvgs = mutableMapOf<Int, Float>()
        var droppedSystemsCount = 0
        var activeConstCount = 0

        for ((type, sum) in constellationSums) {
            val avg = sum / (constellationCounts[type] ?: 1)
            currentConstellationAvgs[type] = avg
            val prevAvg = prevConstellationAvgs[type]
            if (prevAvg != null && prevAvg > 0) {
                activeConstCount++
                if (prevAvg - avg > 6f) droppedSystemsCount++
            }
        }

        if (activeConstCount >= 2 && droppedSystemsCount == activeConstCount) allSystemsDropped = true
        prevConstellationAvgs.clear()
        prevConstellationAvgs.putAll(currentConstellationAvgs)

        var jammingScore = 0
        val reasons = mutableListOf<String>()

        if (avgCn0 > 0 && avgCn0 < gnssSensitivity) { jammingScore += 2; reasons.add("Low C/N0") }
        if (prevAvgCn0 > 0 && (prevAvgCn0 - avgCn0) > 8f) { jammingScore += 2; reasons.add("Sudden Drop") }
        prevAvgCn0 = avgCn0
        if (cn0Values.size >= 3 && avgCn0 < 25f && stdDev < 5.0) { jammingScore += 1; reasons.add("Low StdDev") }
        if (prevSatCount > 0 && (prevSatCount - count) > 3) { jammingScore += 1; reasons.add("Sat Drop") }
        prevSatCount = count
        if (usedInFixCount < 3 && count > 0) { jammingScore += 2; reasons.add("Fix Lost") }
        if (allSystemsDropped) { jammingScore += 2; reasons.add("Multi-GNSS Drop") }
        if (baselineCn0 > 0 && (baselineCn0 - avgCn0) > 12f) { jammingScore += 2; reasons.add("Baseline Deviation") }

        if (jammingScore >= 4 && currentTime - lastGnssAlertTime > GNSS_ALERT_COOLDOWN) {
            triggerAlert(getString(R.string.alert_jamming_detected), avgCn0.toInt())
            lastGnssAlertTime = currentTime
            saveGnssLog("Jamming detected. Score: $jammingScore, Reasons: ${reasons.joinToString(", ")}, C/N0: ${"%.1f".format(avgCn0)}")
        }
        
        val intent = Intent("GNSS_UPDATE")
        intent.setPackage(packageName)
        intent.putExtra("count", count)
        intent.putExtra("usedInFixCount", usedInFixCount)
        intent.putExtra("avgCn0", avgCn0)
        intent.putExtra("stdDev", stdDev.toFloat())
        intent.putExtra("jammingScore", jammingScore)
        intent.putExtra("allSystemsDropped", allSystemsDropped)
        intent.putExtra("baselineCn0", baselineCn0)
        intent.putExtra("isBaselinePhase", false)
        sendBroadcast(intent)
    }

    private fun triggerAlert(title: String, value: Int) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("alert_sound", false)) playAlertSound()
        if (prefs.getBoolean("alert_vibrate", false)) vibrate()
        if (prefs.getBoolean("alert_light", false)) flashLight(5)
        
        val baseMessage = "$title ($value)"
        
        val needLoc = prefs.getBoolean("email_include_gps", true) || prefs.getBoolean("rsyslog_include_gps", true)
        
        if (needLoc && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    sendAllRemoteAlerts(title, baseMessage, loc)
                }
        } else {
            sendAllRemoteAlerts(title, baseMessage, null)
        }

        saveToLog(baseMessage)
        if (prefs.getBoolean("alert_bluetooth", false)) {
            sendBluetoothAlert(prefs.getString("bluetooth_message", "DRONE") ?: "DRONE")
        }
        updateNotification("ALARM: $title")
    }

    private fun sendAllRemoteAlerts(title: String, baseMessage: String, location: Location?) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        
        // Email
        if (prefs.getBoolean("alert_email", false)) {
            val body = StringBuilder(baseMessage)
            if (prefs.getBoolean("email_include_gps", true) || prefs.getBoolean("email_include_time", true)) {
                body.append("\n--- Details ---")
                if (prefs.getBoolean("email_include_time", true)) {
                    body.append("\nTime: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                }
                if (prefs.getBoolean("email_include_gps", true)) {
                    if (location != null) {
                        body.append("\nLocation: ").append(location.latitude).append(", ").append(location.longitude)
                        if (prefs.getBoolean("email_include_google_maps", true)) {
                            body.append("\nhttps://www.google.com/maps/search/?api=1&query=").append(location.latitude).append(",").append(location.longitude)
                        }
                    } else {
                        body.append("\nLocation: Unknown")
                    }
                }
            }
            sendAlertEmails(title, body.toString())
        }

        // Rsyslog
        if (prefs.getBoolean("alert_rsyslog", false)) {
            val useJson = prefs.getBoolean("rsyslog_use_json", true)
            val payload = if (useJson) {
                val json = JSONObject()
                json.put("event", title)
                json.put("details", baseMessage)
                if (prefs.getBoolean("rsyslog_include_time", true)) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    json.put("timestamp", sdf.format(Date()))
                }
                if (prefs.getBoolean("rsyslog_include_gps", true) && location != null) {
                    json.put("lat", location.latitude)
                    json.put("lon", location.longitude)
                }
                if (prefs.getBoolean("rsyslog_include_debug", false)) {
                    val debugObj = JSONObject()
                    debugObj.put("model", Build.MODEL)
                    debugObj.put("android_version", Build.VERSION.RELEASE)
                    debugObj.put("audio_running", isAudioRunning)
                    debugObj.put("gnss_running", isGnssRunning)
                    json.put("debug", debugObj)
                }
                json.toString(2)
            } else {
                val sb = StringBuilder(title).append(": ").append(baseMessage)
                if (prefs.getBoolean("rsyslog_include_time", true)) {
                    sb.append(" [").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("]")
                }
                if (prefs.getBoolean("rsyslog_include_gps", true) && location != null) {
                    sb.append(" (Loc: ").append(location.latitude).append(", ").append(location.longitude).append(")")
                }
                sb.toString()
            }
            
            val host = prefs.getString("rsyslog_host", "") ?: ""
            val port = try { 
                prefs.getString("rsyslog_port", "514")?.toIntOrNull() ?: 514
            } catch (e: ClassCastException) {
                prefs.getInt("rsyslog_port", 514)
            }
            val useTls = prefs.getBoolean("rsyslog_use_tls", false)
            val cert = prefs.getString("rsyslog_cert", null)
            RsyslogSender.send(this, host, port, payload, useTls, cert)
        }
    }

    private fun sendAlertEmails(title: String, body: String) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val emails = prefs.getString("email_addresses", "") ?: ""
        emails.split(",").forEach { 
            if (it.trim().isNotEmpty()) EmailSender.send(this, it.trim(), "DSA ALERT: $title", body) 
        }
    }

    private fun sendBluetoothAlert(message: String) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val address = prefs.getString("bluetooth_device_address", null) ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return

        Thread {
            var socket: BluetoothSocket? = null
            try {
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                val device = adapter.getRemoteDevice(address)
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                socket.outputStream.write(message.toByteArray())
            } catch (e: Exception) {} finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }.start()
    }

    private fun saveToLog(details: String) {
        val prefs = getSharedPreferences("DroneLogs", Context.MODE_PRIVATE)
        val logs = JSONArray(prefs.getString("logs", "[]"))
        val entry = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("details", details)
        }
        logs.put(entry)
        prefs.edit().putString("logs", logs.toString()).apply()
        
        forwardLogToRsyslog("DroneLog", details)
    }

    private fun saveGnssLog(message: String) {
        val prefs = getSharedPreferences("GnssLogs", Context.MODE_PRIVATE)
        val logs = JSONArray(prefs.getString("logs", "[]"))
        val entry = JSONObject().apply {
            put("timestamp", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
            put("message", message)
        }
        logs.put(entry)
        prefs.edit().putString("logs", logs.toString()).apply()
        
        forwardLogToRsyslog("GnssLog", message)
    }

    private fun forwardLogToRsyslog(type: String, message: String) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("alert_rsyslog", false) && prefs.getBoolean("rsyslog_include_debug", false)) {
            val useJson = prefs.getBoolean("rsyslog_use_json", true)
            val payload = if (useJson) {
                val json = JSONObject()
                json.put("log_type", type)
                json.put("message", message)
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                json.put("timestamp", sdf.format(Date()))
                json.toString(2)
            } else {
                "[$type] $message"
            }
            
            val host = prefs.getString("rsyslog_host", "") ?: ""
            val port = try { 
                prefs.getString("rsyslog_port", "514")?.toIntOrNull() ?: 514
            } catch (e: ClassCastException) {
                prefs.getInt("rsyslog_port", 514)
            }
            val useTls = prefs.getBoolean("rsyslog_use_tls", false)
            val cert = prefs.getString("rsyslog_cert", null)
            RsyslogSender.send(this, host, port, payload, useTls, cert)
        }
    }

    private fun playAlertSound() {
        try {
            val uri = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE).getString("alert_sound_uri", null)
            val soundUri = uri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val r = RingtoneManager.getRingtone(applicationContext, soundUri)
            r.play()
            Handler(Looper.getMainLooper()).postDelayed({ if (r.isPlaying) r.stop() }, 5000)
        } catch (e: Exception) {}
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun flashLight(times: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val handler = Handler(Looper.getMainLooper())
            var count = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (count < times * 2) {
                        try { cameraManager.setTorchMode(cameraId, count % 2 == 0) } catch (e: Exception) {}
                        count++
                        handler.postDelayed(this, 300)
                    } else {
                        try { cameraManager.setTorchMode(cameraId, false) } catch (e: Exception) {}
                    }
                }
            }
            handler.post(runnable)
        } catch (e: Exception) {}
    }

    private fun analyzeAudioFrequencies(magnitudes: DoubleArray): Boolean {
        val avg = magnitudes.average()
        val lowIdx = (audioMinFreq * fftSize / sampleRate)
        val highIdx = (audioMaxFreq * fftSize / sampleRate)
        var score = 0
        for (i in lowIdx until highIdx) {
            if (i < magnitudes.size && magnitudes[i] > avg * audioAnalysisMultiplier) score++
        }
        return score >= audioAnalysisMinScore
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Drone Detection Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isAudioRunning = false
        isGnssRunning = false
        stopAudioListening()
        stopGnssMonitoring()
        super.onDestroy()
    }
}
