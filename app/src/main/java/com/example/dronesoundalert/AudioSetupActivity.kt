package com.example.dronesoundalert

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.slider.RangeSlider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File

class AudioSetupActivity : AppCompatActivity() {

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var testFile: File? = null
    private var isRecording = false

    private lateinit var labelMinScore: TextView
    private lateinit var seekMinScore: SeekBar
    private lateinit var labelMaxScore: TextView
    private lateinit var seekMaxScore: SeekBar
    private lateinit var labelFilePath: TextView
    private lateinit var labelAnalysisRange: TextView
    private lateinit var sliderAnalysisRange: RangeSlider
    private lateinit var btnSelectFile: Button
    private lateinit var btnTestFile: Button

    private var selectedFileUri: String? = null

    companion object {
        const val SOURCE_FILE = -2 // Custom value for audio file source
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                selectedFileUri = uri.toString()
                labelFilePath.text = getString(R.string.audio_file_path, getFileName(uri))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mic_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.mic_setup)

        applyScreenSettings()

        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val spinnerAudioSource = findViewById<Spinner>(R.id.spinner_audio_source)
        val btnSave = findViewById<Button>(R.id.btnSaveMicSetup)
        val btnTfLite = findViewById<Button>(R.id.btn_tensorflow_settings)
        val btnTestMic = findViewById<Button>(R.id.btnTestMic)
        
        val labelGain = findViewById<TextView>(R.id.label_audio_gain)
        val seekGain = findViewById<SeekBar>(R.id.seek_audio_gain)
        val labelNoise = findViewById<TextView>(R.id.label_noise_gate)
        val seekNoise = findViewById<SeekBar>(R.id.seek_noise_gate)
        val switchBluetoothSco = findViewById<SwitchMaterial>(R.id.switch_bluetooth_sco)

        labelMinScore = findViewById(R.id.label_min_score)
        seekMinScore = findViewById(R.id.seek_min_score)
        labelMaxScore = findViewById(R.id.label_max_score)
        seekMaxScore = findViewById(R.id.seek_max_score)
        
        labelAnalysisRange = findViewById(R.id.label_analysis_range)
        sliderAnalysisRange = findViewById(R.id.slider_analysis_range)
        
        labelFilePath = findViewById(R.id.label_audio_file_path)
        btnSelectFile = findViewById(R.id.btn_select_audio_file)
        btnTestFile = findViewById(R.id.btn_test_audio_file)
        val switchPlayThroughSpeaker = findViewById<SwitchMaterial>(R.id.switch_play_through_speaker)
        val labelPlayThroughSpeakerDesc = findViewById<TextView>(R.id.label_play_through_speaker_desc)

        val audioSources = mutableListOf(
            AudioSourceItem("Default (MIC)", MediaRecorder.AudioSource.MIC),
            AudioSourceItem("Camcorder", MediaRecorder.AudioSource.CAMCORDER),
            AudioSourceItem("Voice Recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION),
            AudioSourceItem("Voice Communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
            AudioSourceItem("Unprocessed", MediaRecorder.AudioSource.UNPROCESSED),
            AudioSourceItem(getString(R.string.audio_source_file), SOURCE_FILE)
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioSources.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAudioSource.adapter = adapter
        
        val savedAudioSource = prefs.getInt("audio_source", MediaRecorder.AudioSource.MIC)
        val sourceIndex = audioSources.indexOfFirst { it.value == savedAudioSource }.coerceAtLeast(0)
        spinnerAudioSource.setSelection(sourceIndex)

        selectedFileUri = prefs.getString("audio_file_uri", null)
        if (selectedFileUri != null) {
            labelFilePath.text = getString(R.string.audio_file_path, getFileName(Uri.parse(selectedFileUri)))
        }

        switchPlayThroughSpeaker.isChecked = prefs.getBoolean("play_file_through_speaker", false)

        updateFileVisibility(savedAudioSource == SOURCE_FILE, switchPlayThroughSpeaker, labelPlayThroughSpeakerDesc, btnTestMic)

        spinnerAudioSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFileVisibility(audioSources[position].value == SOURCE_FILE, switchPlayThroughSpeaker, labelPlayThroughSpeakerDesc, btnTestMic)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            filePickerLauncher.launch(intent)
        }

        btnTestFile.setOnClickListener {
            testAudioFile()
        }

        // Load Bluetooth SCO
        switchBluetoothSco.isChecked = prefs.getBoolean("use_bluetooth_sco", false)

        // Load Gain
        val savedGain = prefs.getFloat("tflite_gain", 1.0f)
        seekGain.progress = (savedGain * 10).toInt().coerceIn(0, 100)
        labelGain.text = getString(R.string.audio_gain, savedGain)

        seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val g = progress / 10.0f
                labelGain.text = getString(R.string.audio_gain, g)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load Noise Gate
        val savedNoise = prefs.getInt("tflite_min_volume", 100)
        seekNoise.progress = savedNoise.coerceIn(0, 5000)
        labelNoise.text = getString(R.string.noise_gate, savedNoise)

        seekNoise.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelNoise.text = getString(R.string.noise_gate, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load Analysis Thresholds
        val savedMultiplier = prefs.getInt("audio_analysis_multiplier", 15)
        seekMinScore.progress = savedMultiplier
        labelMinScore.text = getString(R.string.min_score, savedMultiplier)

        seekMinScore.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelMinScore.text = getString(R.string.min_score, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val savedMinScore = prefs.getInt("audio_analysis_min_score", 5)
        seekMaxScore.progress = savedMinScore
        labelMaxScore.text = getString(R.string.max_score, savedMinScore)

        seekMaxScore.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelMaxScore.text = getString(R.string.max_score, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Load Analysis Range
        val savedMinFreq = prefs.getInt("audio_min_freq", 2000)
        val savedMaxFreq = prefs.getInt("audio_max_freq", 8000)
        sliderAnalysisRange.setValues(savedMinFreq.toFloat(), savedMaxFreq.toFloat())
        labelAnalysisRange.text = getString(R.string.analysis_range, savedMinFreq, savedMaxFreq)

        sliderAnalysisRange.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            labelAnalysisRange.text = getString(R.string.analysis_range, values[0].toInt(), values[1].toInt())
        }

        btnTfLite.setOnClickListener {
            startActivity(Intent(this, TensorFlowSettingsActivity::class.java))
        }

        btnTestMic.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                return@setOnClickListener
            }
            if (!isRecording) {
                if (DroneDetectionService.isAudioRunning) {
                    Toast.makeText(this, "Stop detection first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startTestRecording(audioSources[spinnerAudioSource.selectedItemPosition].value, btnTestMic)
            }
        }

        btnSave.setOnClickListener {
            val selectedSourceValue = audioSources[spinnerAudioSource.selectedItemPosition].value
            val g = seekGain.progress / 10.0f
            val n = seekNoise.progress
            val useSco = switchBluetoothSco.isChecked
            val mult = seekMinScore.progress
            val minS = seekMaxScore.progress
            val rangeValues = sliderAnalysisRange.values
            val minFreq = rangeValues[0].toInt()
            val maxFreq = rangeValues[1].toInt()

            if (selectedSourceValue == SOURCE_FILE && selectedFileUri == null) {
                Toast.makeText(this, getString(R.string.please_select_audio_file), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putInt("audio_source", selectedSourceValue)
                putString("audio_file_uri", selectedFileUri)
                putBoolean("play_file_through_speaker", switchPlayThroughSpeaker.isChecked)
                putFloat("tflite_gain", g)
                putInt("tflite_min_volume", n)
                putBoolean("use_bluetooth_sco", useSco)
                putInt("audio_analysis_multiplier", mult)
                putInt("audio_analysis_min_score", minS)
                putInt("audio_min_freq", minFreq)
                putInt("audio_max_freq", maxFreq)
                apply()
            }
            
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (useSco) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }

            // Reload settings in service if it's running
            val reloadIntent = Intent(this, DroneDetectionService::class.java)
            reloadIntent.action = DroneDetectionService.ACTION_RELOAD_SETTINGS
            startService(reloadIntent)

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateFileVisibility(show: Boolean, switch: SwitchMaterial, labelDesc: TextView, btnTestMic: Button) {
        btnSelectFile.visibility = if (show) View.VISIBLE else View.GONE
        labelFilePath.visibility = if (show) View.VISIBLE else View.GONE
        switch.visibility = if (show) View.VISIBLE else View.GONE
        labelDesc.visibility = if (show) View.VISIBLE else View.GONE
        btnTestFile.visibility = if (show) View.VISIBLE else View.GONE
        btnTestMic.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun getFileName(uri: Uri): String {
        return uri.path?.substringAfterLast('/') ?: "file"
    }

    private fun testAudioFile() {
        if (selectedFileUri == null) {
            Toast.makeText(this, getString(R.string.no_file_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val button = btnTestFile
        button.isEnabled = false
        button.text = getString(R.string.testing_file)
        
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(this@AudioSetupActivity, Uri.parse(selectedFileUri))
                prepare()
                start()
                setOnCompletionListener {
                    resetFileTestButton(button)
                }
            } catch (e: Exception) {
                Toast.makeText(this@AudioSetupActivity, getString(R.string.error_playing_file), Toast.LENGTH_SHORT).show()
                resetFileTestButton(button)
            }
        }
    }

    private fun resetFileTestButton(button: Button) {
        button.isEnabled = true
        button.text = getString(R.string.test_file)
        
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun applyScreenSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("keep_screen_on", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenSettings()
    }

    private fun startTestRecording(source: Int, button: Button) {
        isRecording = true
        button.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // Force screen on during test
        
        testFile = File(cacheDir, "test_mic.mp4")
        
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }.apply {
            try {
                setAudioSource(source)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(testFile?.absolutePath)
                prepare()
                start()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@AudioSetupActivity, "Mic busy or error", Toast.LENGTH_SHORT).show()
                resetTestButton(button)
                return
            }
        }

        var secondsLeft = 10
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (secondsLeft > 0) {
                    button.text = getString(R.string.recording, secondsLeft)
                    secondsLeft--
                    handler.postDelayed(this, 1000)
                } else {
                    stopTestRecording(button)
                }
            }
        }
        handler.post(runnable)
    }

    private fun stopTestRecording(button: Button) {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {}
        mediaRecorder = null
        isRecording = false
        
        button.text = getString(R.string.playing)
        playTestRecording(button)
    }

    private fun playTestRecording(button: Button) {
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(testFile?.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    resetTestButton(button)
                }
                setOnErrorListener { _, _, _ ->
                    resetTestButton(button)
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                resetTestButton(button)
            }
        }
    }

    private fun resetTestButton(button: Button) {
        button.isEnabled = true
        button.text = getString(R.string.test_mic)
        applyScreenSettings() // Restore original screen setting
        
        mediaPlayer?.release()
        mediaPlayer = null
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
    }

    override fun onStop() {
        super.onStop()
        mediaRecorder?.release()
        mediaRecorder = null
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private data class AudioSourceItem(val name: String, val value: Int)
}
