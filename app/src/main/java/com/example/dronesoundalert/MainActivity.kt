package com.example.dronesoundalert

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var frequencyText: TextView
    private lateinit var btnToggle: Button
    private lateinit var audioProgress: ProgressBar
    private lateinit var switchGnssBackground: SwitchMaterial

    private lateinit var dotRed: View
    private lateinit var dotYellow: View
    private lateinit var dotGreen: View
    private lateinit var labelRed: TextView
    private lateinit var labelYellow: TextView
    private lateinit var labelGreen: TextView

    private lateinit var viewStandardFreq: LinearLayout
    private lateinit var viewSpectrogram: ImageView
    private lateinit var viewOscilloscope: ImageView
    private lateinit var axisX: ImageView
    private lateinit var axisY: ImageView
    
    private lateinit var layoutBottomMenus: LinearLayout
    private lateinit var layoutGnssStatusMain: LinearLayout
    private lateinit var textGnssStatusMain: TextView
    private lateinit var textSatelliteCountMain: TextView
    private lateinit var textInterferenceLevelMain: TextView
    
    private var isAudioServiceRunning = false
    private var useSpectrogram = true
    private var useOscilloscope = false
    private var showSpectrogramThreshold = true
    private var audioMinFreq = 2000
    private var audioMaxFreq = 8000

    // Visualizer properties
    private val SPECTROGRAM_WIDTH = 256
    private val SPECTROGRAM_HEIGHT = 250
    private lateinit var spectrogramBitmap: Bitmap
    private lateinit var spectrogramPixels: IntArray
    private lateinit var overlayBitmap: Bitmap
    private lateinit var overlayCanvas: Canvas

    private lateinit var oscilloscopeBitmap: Bitmap
    private lateinit var oscilloscopeCanvas: Canvas
    private val osciPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val peakPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 2f
        isAntiAlias = true
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }

    private val thresholdPaint = Paint().apply {
        color = Color.WHITE
        alpha = 150
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val axisPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        strokeWidth = 2f
        isAntiAlias = true
    }

    private lateinit var axisXBitmap: Bitmap
    private lateinit var axisYBitmap: Bitmap

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "DRONE_UPDATE") {
                val maxAmp = intent.getIntExtra("maxAmp", 0)
                val peakFreq = intent.getIntExtra("peakFreq", 0)
                val isDetecting = intent.getBooleanExtra("isDetecting", false)
                val isCooldown = intent.getBooleanExtra("isCooldown", false)
                val triggered = intent.getBooleanExtra("triggered", false)
                val detectedInFrame = intent.getBooleanExtra("detectedInFrame", false)
                val spectrogramData = intent.getFloatArrayExtra("spectrogram")
                
                audioProgress.progress = (maxAmp / 327).coerceIn(0, 100)
                frequencyText.text = getString(R.string.frequency_text, peakFreq)
                
                // Visualizer updates
                if (useSpectrogram && spectrogramData != null) {
                    updateSpectrogram(spectrogramData)
                } else if (useOscilloscope && spectrogramData != null) {
                    updateOscilloscope(spectrogramData)
                }

                // Only update dots if UI is in running state
                if (isAudioServiceRunning) {
                    when {
                        detectedInFrame || triggered -> {
                            updateIndicatorDots(Color.RED)
                        }
                        isCooldown -> {
                            updateIndicatorDots(Color.YELLOW)
                        }
                        isDetecting -> {
                            updateIndicatorDots(Color.YELLOW)
                        }
                        else -> {
                            updateIndicatorDots(Color.GREEN)
                        }
                    }
                }
            } else if (intent?.action == "GNSS_UPDATE") {
                val count = intent.getIntExtra("count", 0)
                val usedCount = intent.getIntExtra("usedInFixCount", 0)
                val jammingScore = intent.getIntExtra("jammingScore", 0)
                val isBaseline = intent.getBooleanExtra("isBaselinePhase", false)
                val baselineProgress = intent.getIntExtra("baselineProgress", 0)

                if (isBaseline) {
                    textGnssStatusMain.text = getString(R.string.mapping_env, baselineProgress)
                } else {
                    textGnssStatusMain.text = getString(R.string.gnss_status_active)
                }
                
                textSatelliteCountMain.text = getString(R.string.satellites_count, count, usedCount)
                
                val interference = when {
                    jammingScore >= 6 -> getString(R.string.interference_high)
                    jammingScore >= 3 -> getString(R.string.interference_elevated)
                    else -> getString(R.string.interference_normal)
                }
                textInterferenceLevelMain.text = interference
                
                val avgCn0 = intent.getFloatExtra("avgCn0", 0f)
                val textAverageCn0Main = findViewById<TextView>(R.id.textAverageCn0Main)
                textAverageCn0Main.text = getString(R.string.avg_cn0, avgCn0)
                
                val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
                val isDarkMode = prefs.getBoolean("dark_mode", false)
                
                val color = when {
                    jammingScore >= 6 -> if (isDarkMode) Color.WHITE else Color.RED
                    jammingScore >= 3 -> Color.parseColor("#FFA500") // Orange
                    else -> Color.parseColor("#388E3C") // Green
                }
                textInterferenceLevelMain.setTextColor(color)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frequencyText = findViewById(R.id.frequencyText)
        audioProgress = findViewById(R.id.analysisProgress)
        btnToggle = findViewById(R.id.btnToggleTunnistus)
        switchGnssBackground = findViewById(R.id.switch_gnss_background)

        dotRed = findViewById(R.id.dot_red)
        dotYellow = findViewById(R.id.dot_yellow)
        dotGreen = findViewById(R.id.dot_green)

        labelRed = findViewById(R.id.label_red)
        labelYellow = findViewById(R.id.label_yellow)
        labelGreen = findViewById(R.id.label_green)
        
        viewStandardFreq = findViewById(R.id.view_standard_freq)
        viewSpectrogram = findViewById(R.id.view_spectrogram)
        viewOscilloscope = findViewById(R.id.view_oscilloscope)
        axisX = findViewById(R.id.axis_x)
        axisY = findViewById(R.id.axis_y)
        
        layoutBottomMenus = findViewById(R.id.layout_bottom_menus)
        layoutGnssStatusMain = findViewById(R.id.layout_gnss_status_main)
        textGnssStatusMain = findViewById(R.id.textGnssStatusMain)
        textSatelliteCountMain = findViewById(R.id.textSatelliteCountMain)
        textInterferenceLevelMain = findViewById(R.id.textInterferenceLevelMain)

        // Initialize Visualizer Bitmaps
        spectrogramBitmap = Bitmap.createBitmap(SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT, Bitmap.Config.ARGB_8888)
        spectrogramPixels = IntArray(SPECTROGRAM_WIDTH * SPECTROGRAM_HEIGHT) { Color.BLACK }
        spectrogramBitmap.setPixels(spectrogramPixels, 0, SPECTROGRAM_WIDTH, 0, 0, SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT)
        
        overlayBitmap = Bitmap.createBitmap(SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlayBitmap)
        
        oscilloscopeBitmap = Bitmap.createBitmap(SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT, Bitmap.Config.ARGB_8888)
        oscilloscopeCanvas = Canvas(oscilloscopeBitmap)
        
        viewSpectrogram.setImageBitmap(overlayBitmap)
        viewOscilloscope.setImageBitmap(oscilloscopeBitmap)

        val axisHeightPx = dpToPx(40)
        val axisWidthPx = dpToPx(40)
        axisXBitmap = Bitmap.createBitmap(SPECTROGRAM_WIDTH, axisHeightPx, Bitmap.Config.ARGB_8888)
        axisYBitmap = Bitmap.createBitmap(axisWidthPx, SPECTROGRAM_HEIGHT, Bitmap.Config.ARGB_8888)
        axisX.setImageBitmap(axisXBitmap)
        axisY.setImageBitmap(axisYBitmap)
        drawAxes()
        
        val btnGnssMonitor = findViewById<Button>(R.id.btnGnssMonitor)
        val btnOpenMap = findViewById<Button>(R.id.btnOpenMap)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnGeneralSettings = findViewById<Button>(R.id.btnGeneralSettings)
        val btnMicSetup = findViewById<Button>(R.id.btnMicSetup)
        val btnGpsSettings = findViewById<Button>(R.id.btnGpsSettings)
        val btnLogs = findViewById<Button>(R.id.btnLogs)
        val btnManual = findViewById<Button>(R.id.btnManual)
        val btnAbout = findViewById<Button>(R.id.btnAbout)

        checkPermissions()
        applyScreenSettings()

        btnToggle.setOnClickListener {
            if (!isAudioServiceRunning) {
                if (shouldCheckGps()) {
                    checkGpsAndStartAudio()
                } else {
                    startAudioDetection()
                }
            } else {
                stopAudioDetection()
            }
        }

        switchGnssBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startGnssBackgroundMonitoring()
            } else {
                stopGnssBackgroundMonitoring()
            }
            // Update UI to show/hide GNSS status block if detection is running
            updateAudioUiState(isAudioServiceRunning)
        }

        btnGnssMonitor.setOnClickListener {
            startActivity(Intent(this, GnssMonitorActivity::class.java))
        }

        btnOpenMap.setOnClickListener {
            openCurrentLocationOnMap()
        }

        btnGeneralSettings.setOnClickListener {
            startActivity(Intent(this, GeneralSettingsActivity::class.java))
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnMicSetup.setOnClickListener {
            startActivity(Intent(this, AudioSetupActivity::class.java))
        }

        btnGpsSettings.setOnClickListener {
            startActivity(Intent(this, GpsSettingsActivity::class.java))
        }

        btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        btnManual.setOnClickListener {
            startActivity(Intent(this, ManualActivity::class.java))
        }

        btnAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun updateSpectrogram(data: FloatArray) {
        // Shift existing pixels down by one row
        System.arraycopy(spectrogramPixels, 0, spectrogramPixels, SPECTROGRAM_WIDTH, SPECTROGRAM_WIDTH * (SPECTROGRAM_HEIGHT - 1))
        
        // Draw new line at the top
        for (i in 0 until SPECTROGRAM_WIDTH) {
            val mag = if (i < data.size) data[i] else 0f
            // Simple colormap scaling (mag scaled roughly up to ~2000 for standard FFT peaks)
            val normalized = (mag / 500f).coerceIn(0f, 1f)
            
            // Map 0->Black, 0.5->Blue/Green, 1.0->Red/Yellow (Jet-like or Heatmap)
            val r = (normalized * 255).toInt()
            val g = ((1f - Math.abs(normalized - 0.5f) * 2f) * 255).toInt().coerceIn(0, 255)
            val b = ((1f - normalized) * 255).toInt()
            
            spectrogramPixels[i] = Color.rgb(r, g, b)
        }
        
        spectrogramBitmap.setPixels(spectrogramPixels, 0, SPECTROGRAM_WIDTH, 0, 0, SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT)
        
        overlayBitmap.eraseColor(Color.TRANSPARENT)
        overlayCanvas.drawBitmap(spectrogramBitmap, 0f, 0f, null)
        
        if (showSpectrogramThreshold) {
            // Draw threshold lines for chosen frequency region
            val sampleRate = 44100
            val maxFreq = sampleRate / 2f
            
            val lowFreqX = (audioMinFreq.toFloat() / maxFreq) * SPECTROGRAM_WIDTH
            val highFreqX = (audioMaxFreq.toFloat() / maxFreq) * SPECTROGRAM_WIDTH
            
            overlayCanvas.drawLine(lowFreqX, 0f, lowFreqX, SPECTROGRAM_HEIGHT.toFloat(), thresholdPaint)
            overlayCanvas.drawLine(highFreqX, 0f, highFreqX, SPECTROGRAM_HEIGHT.toFloat(), thresholdPaint)
        }
        
        viewSpectrogram.invalidate()
    }

    private fun updateOscilloscope(data: FloatArray) {
        oscilloscopeBitmap.eraseColor(Color.BLACK)
        
        val points = FloatArray(data.size * 4)
        var maxVal = 0.01f
        for (f in data) if (f > maxVal) maxVal = f
        
        val stepX = SPECTROGRAM_WIDTH.toFloat() / data.size
        
        for (i in 0 until data.size - 1) {
            points[i * 4] = i * stepX
            points[i * 4 + 1] = SPECTROGRAM_HEIGHT - (data[i] / maxVal * SPECTROGRAM_HEIGHT)
            points[i * 4 + 2] = (i + 1) * stepX
            points[i * 4 + 3] = SPECTROGRAM_HEIGHT - (data[i + 1] / maxVal * SPECTROGRAM_HEIGHT)
        }
        
        oscilloscopeCanvas.drawLines(points, osciPaint)

        // Draw Peak Sensitivity threshold line
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val sensitivity = prefs.getInt("peak_sensitivity", 50)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        
        // Use White in dark mode, Red in light mode
        peakPaint.color = if (isDarkMode) Color.WHITE else Color.RED
        
        // Map 0-100 sensitivity to Y coordinate
        val thresholdY = SPECTROGRAM_HEIGHT - (sensitivity.toFloat() / 100f * SPECTROGRAM_HEIGHT)
        oscilloscopeCanvas.drawLine(0f, thresholdY, SPECTROGRAM_WIDTH.toFloat(), thresholdY, peakPaint)
        
        if (showSpectrogramThreshold) {
            val sampleRate = 44100
            val maxFreq = sampleRate / 2f
            
            val lowFreqX = (audioMinFreq.toFloat() / maxFreq) * SPECTROGRAM_WIDTH
            val highFreqX = (audioMaxFreq.toFloat() / maxFreq) * SPECTROGRAM_WIDTH
            
            oscilloscopeCanvas.drawLine(lowFreqX, 0f, lowFreqX, SPECTROGRAM_HEIGHT.toFloat(), thresholdPaint)
            oscilloscopeCanvas.drawLine(highFreqX, 0f, highFreqX, SPECTROGRAM_HEIGHT.toFloat(), thresholdPaint)
            
            // Draw a subtle box for the detection range
            val rectPaint = Paint().apply {
                color = Color.WHITE
                alpha = 30
                style = Paint.Style.FILL
            }
            oscilloscopeCanvas.drawRect(lowFreqX, 0f, highFreqX, SPECTROGRAM_HEIGHT.toFloat(), rectPaint)
        }
        
        viewOscilloscope.invalidate()
    }

    private fun showAboutDialog() {
        val version = "v2.6"
        val url = "https://dronesoundalert.com"
        AlertDialog.Builder(this)
            .setTitle(R.string.about)
            .setMessage("Drone Sound Alert $version\n\nVisit our website for more info:\n$url")
            .setPositiveButton("Open Website") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openCurrentLocationOnMap() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val gmmIntentUri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}(My Location)")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    if (mapIntent.resolveActivity(packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps?q=${location.latitude},${location.longitude}"))
                        startActivity(webIntent)
                    }
                } else {
                    Toast.makeText(this, "Could not fetch location. Ensure GPS is on.", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun startGnssBackgroundMonitoring() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            switchGnssBackground.isChecked = false
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            switchGnssBackground.isChecked = false
            
            AlertDialog.Builder(this)
                .setTitle(R.string.gps_off_title)
                .setMessage(R.string.gnss_status_no_permission)
                .setPositiveButton(R.string.settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val intent = Intent(this, DroneDetectionService::class.java)
        intent.action = DroneDetectionService.ACTION_START_GNSS
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, getString(R.string.gnss_status_active), Toast.LENGTH_SHORT).show()
    }

    private fun stopGnssBackgroundMonitoring() {
        val intent = Intent(this, DroneDetectionService::class.java)
        intent.action = DroneDetectionService.ACTION_STOP_GNSS
        startService(intent)
        
        if (!DroneDetectionService.isAudioRunning) {
            val stopIntent = Intent(this, DroneDetectionService::class.java)
            stopIntent.action = DroneDetectionService.ACTION_STOP_SERVICE
            startService(stopIntent)
        }
    }

    private fun shouldCheckGps(): Boolean {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("alert_sms", false) || prefs.getBoolean("include_gps", false)
    }

    private fun checkGpsAndStartAudio() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.gps_off_title)
                .setMessage(R.string.gps_off_msg)
                .setPositiveButton(R.string.settings) { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton(R.string.start_anyway) { _, _ -> startAudioDetection() }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            startAudioDetection()
        }
    }

    private fun startAudioDetection() {
        val serviceIntent = Intent(this, DroneDetectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateAudioUiState(true)
    }

    private fun stopAudioDetection() {
        if (DroneDetectionService.isGnssRunning) {
            val intent = Intent(this, DroneDetectionService::class.java)
            intent.action = DroneDetectionService.ACTION_STOP_AUDIO
            startService(intent)
        } else {
            val serviceIntent = Intent(this, DroneDetectionService::class.java)
            serviceIntent.action = DroneDetectionService.ACTION_STOP_SERVICE
            startService(serviceIntent)
        }
        updateAudioUiState(false)
    }

    private fun updateIndicatorDots(activeColor: Int) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        val alertColor = if (isDarkMode) Color.WHITE else Color.RED

        dotRed.alpha = if (activeColor == Color.RED) 1.0f else 0.2f
        dotYellow.alpha = if (activeColor == Color.YELLOW) 1.0f else 0.2f
        dotGreen.alpha = if (activeColor == Color.GREEN) 1.0f else 0.2f

        labelRed.alpha = if (activeColor == Color.RED) 1.0f else 0.2f
        labelYellow.alpha = if (activeColor == Color.YELLOW) 1.0f else 0.2f
        labelGreen.alpha = if (activeColor == Color.GREEN) 1.0f else 0.2f

        // Set text colors
        labelRed.setTextColor(alertColor)
        val indicatorTextColor = if (isDarkMode) Color.WHITE else Color.BLACK
        labelYellow.setTextColor(indicatorTextColor)
        labelGreen.setTextColor(indicatorTextColor)

        dotRed.background.setTint(if (activeColor == Color.RED) alertColor else Color.LTGRAY)
        dotYellow.background.setTint(if (activeColor == Color.YELLOW) Color.YELLOW else Color.LTGRAY)
        dotGreen.background.setTint(if (activeColor == Color.GREEN) Color.GREEN else Color.LTGRAY)
    }

    private fun updateAudioUiState(running: Boolean) {
        isAudioServiceRunning = running
        val statusCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.statusCard)
        
        if (running) {
            val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
            val isDarkMode = prefs.getBoolean("dark_mode", false)
            val alertColor = if (isDarkMode) Color.WHITE else Color.RED
            
            btnToggle.text = getString(R.string.stop_detection)
            btnToggle.setBackgroundColor(alertColor)
            // If button background is white, text should be black
            if (isDarkMode) {
                btnToggle.setTextColor(Color.BLACK)
            } else {
                btnToggle.setTextColor(Color.WHITE)
            }
            
            viewSpectrogram.visibility = if (useSpectrogram) View.VISIBLE else View.GONE
            viewOscilloscope.visibility = if (useOscilloscope) View.VISIBLE else View.GONE
            viewStandardFreq.visibility = if (!useSpectrogram && !useOscilloscope) View.VISIBLE else View.GONE
            
            // Show axes if a visualizer is active
            val showAxes = useSpectrogram || useOscilloscope
            axisX.visibility = if (showAxes) View.VISIBLE else View.GONE
            axisY.visibility = if (showAxes) View.VISIBLE else View.GONE
            
            // Hide bottom menus and GNSS switch when monitoring
            layoutBottomMenus.visibility = View.GONE
            switchGnssBackground.visibility = View.GONE
            
            // Show GNSS status if it's running
            val gnssActive = DroneDetectionService.isGnssRunning
            layoutGnssStatusMain.visibility = if (gnssActive) View.VISIBLE else View.GONE
            
            statusCard.visibility = View.VISIBLE
            
            // Adjust card height: Space for visualizer + GNSS if active
            val params = statusCard.layoutParams
            var heightDp = 130
            if (useSpectrogram || useOscilloscope) {
                heightDp = 260 // 220 for visualizer + 30 for axis + padding
                if (gnssActive) heightDp += 75
            } else if (gnssActive) {
                heightDp = 160
            }
            params.height = dpToPx(heightDp)
            statusCard.layoutParams = params

            updateIndicatorDots(Color.GREEN)
        } else {
            val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
            val isDarkMode = prefs.getBoolean("dark_mode", false)
            val defaultTextColor = if (isDarkMode) Color.WHITE else Color.BLACK
            
            btnToggle.text = getString(R.string.start_detection)
            btnToggle.setBackgroundColor(Color.parseColor("#388E3C"))
            btnToggle.setTextColor(Color.WHITE) // Always white on green button
            
            viewStandardFreq.visibility = View.INVISIBLE
            viewSpectrogram.visibility = View.INVISIBLE
            viewOscilloscope.visibility = View.INVISIBLE
            axisX.visibility = View.GONE
            axisY.visibility = View.GONE
            
            statusCard.visibility = View.GONE
            
            // Show menus and switch when not monitoring
            layoutBottomMenus.visibility = View.VISIBLE
            switchGnssBackground.visibility = View.VISIBLE
            layoutGnssStatusMain.visibility = View.GONE
            
            // Set text colors for main menu items
            frequencyText.setTextColor(defaultTextColor)
            switchGnssBackground.setTextColor(defaultTextColor)
            
            val params = statusCard.layoutParams
            params.height = dpToPx(130)
            statusCard.layoutParams = params

            audioProgress.progress = 0
            frequencyText.text = getString(R.string.frequency_text, 0)
            updateIndicatorDots(Color.TRANSPARENT)
        }
    }

    private fun drawAxes() {
        val canvasX = Canvas(axisXBitmap)
        val canvasY = Canvas(axisYBitmap)
        axisXBitmap.eraseColor(Color.TRANSPARENT)
        axisYBitmap.eraseColor(Color.TRANSPARENT)

        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        val textColor = if (isDarkMode) Color.WHITE else Color.BLACK
        axisPaint.color = textColor

        val maxFreq = 11025 // Visualizer shows 0 to 11025 Hz
        val stepHz = 200

        // X-axis (Frequency)
        axisPaint.textAlign = Paint.Align.CENTER
        axisPaint.style = Paint.Style.FILL_AND_STROKE
        for (hz in 0..maxFreq step stepHz) {
            val x = (hz.toFloat() / maxFreq) * SPECTROGRAM_WIDTH
            canvasX.drawLine(x, 0f, x, 12f, axisPaint)
            if (hz % 2000 == 0) {
                axisPaint.strokeWidth = 1f
                canvasX.drawText("${hz / 1000}k", x, 38f, axisPaint)
                axisPaint.strokeWidth = 2f
            }
        }

        // Y-axis (Relative Magnitude 0-100)
        axisPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..10) {
            val y = SPECTROGRAM_HEIGHT - (i / 10f) * SPECTROGRAM_HEIGHT
            canvasY.drawLine(axisYBitmap.width.toFloat() - 12f, y, axisYBitmap.width.toFloat(), y, axisPaint)
            if (i % 5 == 0) {
                axisPaint.strokeWidth = 1f
                canvasY.drawText("${i * 10}", axisYBitmap.width.toFloat() - 15f, y + 10f, axisPaint)
                axisPaint.strokeWidth = 2f
            }
        }

        axisX.invalidate()
        axisY.invalidate()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onResume() {
        super.onResume()
        
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", false)
        val currentMode = AppCompatDelegate.getDefaultNightMode()
        val expectedMode = if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        
        if (currentMode != expectedMode) {
            AppCompatDelegate.setDefaultNightMode(expectedMode)
            recreate()
            return
        }

        applyScreenSettings()
        
        useSpectrogram = prefs.getBoolean("use_spectrogram", true)
        useOscilloscope = prefs.getBoolean("use_oscilloscope", false)
        showSpectrogramThreshold = prefs.getBoolean("show_spectrogram_threshold", true)
        audioMinFreq = prefs.getInt("audio_min_freq", 2000)
        audioMaxFreq = prefs.getInt("audio_max_freq", 8000)
        
        if (DroneDetectionService.isGnssRunning) {
            switchGnssBackground.isChecked = true
            layoutGnssStatusMain.visibility = if (DroneDetectionService.isAudioRunning) View.VISIBLE else View.GONE
        } else {
            switchGnssBackground.isChecked = false
            layoutGnssStatusMain.visibility = View.GONE
        }
        updateAudioUiState(DroneDetectionService.isAudioRunning)
        drawAxes()
        
        val filter = IntentFilter()
        filter.addAction("DRONE_UPDATE")
        filter.addAction("GNSS_UPDATE")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) {}
    }

    private fun applyScreenSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("keep_screen_on", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 123)
        }
    }
}
