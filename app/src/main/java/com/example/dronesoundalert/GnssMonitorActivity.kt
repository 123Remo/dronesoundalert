package com.example.dronesoundalert

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GnssMonitorActivity : AppCompatActivity() {

    private val TAG = "GnssMonitorActivity"
    private lateinit var textGnssStatus: TextView
    private lateinit var textSatelliteCount: TextView
    private lateinit var textAverageCn0: TextView
    private lateinit var textInterferenceLevel: TextView
    private lateinit var recyclerSatellites: RecyclerView
    private lateinit var btnViewGnssLogs: Button
    private lateinit var btnOpenAnalysis: Button
    private lateinit var btnResetBaseline: Button
    
    private lateinit var viewSpectrogramGnss: ImageView
    private lateinit var textDetectionStatusGnss: TextView
    private lateinit var visualizerContainer: View

    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    
    private val satelliteList = mutableListOf<SatelliteInfo>()
    private lateinit var adapter: SatelliteAdapter

    private var spectrogramBitmap: Bitmap? = null
    private var spectrogramPixels: IntArray? = null
    private var overlayBitmap: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private val SPECTROGRAM_WIDTH = 512
    private val SPECTROGRAM_HEIGHT = 120

    private val gnssUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "GNSS_UPDATE") {
                // When we receive an update from service, we know it's active
                textGnssStatus.text = getString(R.string.gnss_status_active)
                textInterferenceLevel.visibility = View.VISIBLE
                
                val isBaseline = intent.getBooleanExtra("isBaselinePhase", false)
                val progress = intent.getIntExtra("baselineProgress", 0)
                val count = intent.getIntExtra("count", 0)
                val usedCount = intent.getIntExtra("usedInFixCount", 0)
                val avgCn0 = intent.getFloatExtra("avgCn0", 0f)
                val jammingScore = intent.getIntExtra("jammingScore", 0)

                if (isBaseline) {
                    textInterferenceLevel.text = getString(R.string.mapping_env, progress)
                    textInterferenceLevel.setTextColor(Color.BLUE)
                    btnResetBaseline.isEnabled = false
                } else {
                    btnResetBaseline.isEnabled = true
                    
                    val interference = when {
                        jammingScore >= 6 -> getString(R.string.interference_high)
                        jammingScore >= 3 -> getString(R.string.interference_elevated)
                        else -> getString(R.string.interference_normal)
                    }
                    textInterferenceLevel.text = interference
                    
                    val color = when {
                        jammingScore >= 6 -> Color.RED
                        jammingScore >= 3 -> Color.parseColor("#FFA500")
                        else -> Color.parseColor("#388E3C")
                    }
                    textInterferenceLevel.setTextColor(color)
                }

                textSatelliteCount.text = getString(R.string.satellites_count, count, usedCount)
                textAverageCn0.text = getString(R.string.avg_cn0, avgCn0)

            } else if (intent?.action == "DRONE_UPDATE") {
                val detectedInFrame = intent.getBooleanExtra("detectedInFrame", false)
                val spectrogramData = intent.getFloatArrayExtra("spectrogram")
                val triggered = intent.getBooleanExtra("triggered", false)
                val isDetecting = intent.getBooleanExtra("isDetecting", false)
                val isCooldown = intent.getBooleanExtra("isCooldown", false)
                val remaining = intent.getLongExtra("remaining", 0)

                if (spectrogramData != null) {
                    updateSpectrogram(spectrogramData)
                }

                when {
                    detectedInFrame || triggered -> {
                        textDetectionStatusGnss.text = getString(R.string.alert_drone_detected)
                        textDetectionStatusGnss.setTextColor(Color.RED)
                    }
                    isCooldown -> {
                        textDetectionStatusGnss.text = getString(R.string.status_cooldown)
                        textDetectionStatusGnss.setTextColor(Color.parseColor("#FFA500"))
                    }
                    isDetecting -> {
                        textDetectionStatusGnss.text = getString(R.string.status_analyzing, remaining.toString())
                        textDetectionStatusGnss.setTextColor(Color.BLUE)
                    }
                    else -> {
                        textDetectionStatusGnss.text = getString(R.string.status_listening)
                        textDetectionStatusGnss.setTextColor(Color.parseColor("#388E3C"))
                    }
                }
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gnss_monitor)

        textGnssStatus = findViewById(R.id.textGnssStatus)
        textSatelliteCount = findViewById(R.id.textSatelliteCount)
        textAverageCn0 = findViewById(R.id.textAverageCn0)
        textInterferenceLevel = findViewById(R.id.textInterferenceLevel)
        recyclerSatellites = findViewById(R.id.recyclerSatellites)
        btnViewGnssLogs = findViewById(R.id.btnViewGnssLogs)
        btnOpenAnalysis = findViewById(R.id.btnOpenAnalysis)
        btnResetBaseline = findViewById(R.id.btnResetBaseline)
        
        viewSpectrogramGnss = findViewById(R.id.view_spectrogram_gnss)
        textDetectionStatusGnss = findViewById(R.id.textDetectionStatusGnss)
        visualizerContainer = findViewById(R.id.visualizerContainer)

        spectrogramBitmap = Bitmap.createBitmap(SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SPECTROGRAM_WIDTH * SPECTROGRAM_HEIGHT) { Color.BLACK }
        spectrogramPixels = pixels
        spectrogramBitmap?.setPixels(pixels, 0, SPECTROGRAM_WIDTH, 0, 0, SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT)
        
        overlayBitmap = Bitmap.createBitmap(SPECTROGRAM_WIDTH, SPECTROGRAM_HEIGHT, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlayBitmap!!)
        viewSpectrogramGnss.setImageBitmap(overlayBitmap)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        recyclerSatellites.layoutManager = LinearLayoutManager(this)
        adapter = SatelliteAdapter(this, satelliteList)
        recyclerSatellites.adapter = adapter

        btnViewGnssLogs.setOnClickListener {
            startActivity(Intent(this, GnssLogActivity::class.java))
        }

        btnOpenAnalysis.setOnClickListener {
            startActivity(Intent(this, GnssAnalysisActivity::class.java))
        }

        btnResetBaseline.setOnClickListener {
            val intent = Intent(this, DroneDetectionService::class.java)
            intent.action = DroneDetectionService.ACTION_RESET_GNSS_BASELINE
            startService(intent)
            Toast.makeText(this, getString(R.string.reset_baseline), Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setupGnssCallback()
        } else {
            textGnssStatus.text = getString(R.string.gnss_not_supported)
        }
        
        checkGpsEnabled()
    }

    private fun checkGpsEnabled() {
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.gps_off_title)
                .setMessage(getString(R.string.gnss_status_no_permission))
                .setPositiveButton(R.string.settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun setupGnssCallback() {
        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onStarted() { 
                updateStartedStatus()
            }
            override fun onStopped() { textGnssStatus.text = getString(R.string.gnss_status_stopped) }
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                updateSatelliteData(status)
            }
        }
    }

    private fun updateStartedStatus() {
        if (DroneDetectionService.isGnssRunning) {
            textGnssStatus.text = getString(R.string.gnss_status_active)
            textInterferenceLevel.visibility = View.VISIBLE
        } else {
            textGnssStatus.text = getString(R.string.gnss_status_active_live)
            textInterferenceLevel.text = getString(R.string.interference_idle)
            textInterferenceLevel.setTextColor(Color.GRAY)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun updateSatelliteData(status: GnssStatus) {
        val count = status.satelliteCount
        val newSatelliteList = mutableListOf<SatelliteInfo>()
        
        var totalCn0 = 0f
        var validCount = 0
        var usedInFixCount = 0
        
        for (i in 0 until count) {
            val cn0 = status.getCn0DbHz(i)
            if (cn0 > 0) {
                totalCn0 += cn0
                validCount++
                newSatelliteList.add(SatelliteInfo(
                    status.getSvid(i),
                    getConstellationName(status.getConstellationType(i)),
                    cn0,
                    status.usedInFix(i)
                ))
            }
            if (status.usedInFix(i)) usedInFixCount++
        }
        
        newSatelliteList.sortByDescending { it.cn0 }
        
        val avgCn0 = if (validCount > 0) totalCn0 / validCount else 0f

        runOnUiThread {
            satelliteList.clear()
            satelliteList.addAll(newSatelliteList)
            adapter.notifyDataSetChanged()
            
            // If the background service is running, it will send broadcasts with 
            // more authoritative data (including jamming scores and baseline progress).
            // We only update aggregate UI here if we are in "standalone" live preview mode.
            if (!DroneDetectionService.isGnssRunning) {
                textSatelliteCount.text = getString(R.string.satellites_count, count, usedInFixCount)
                textAverageCn0.text = getString(R.string.avg_cn0, avgCn0)
                textGnssStatus.text = getString(R.string.gnss_status_active_live)
                textInterferenceLevel.visibility = View.GONE
            } else {
                // Background service is running. We update textGnssStatus here just in case 
                // to show we are connected to the service data.
                textGnssStatus.text = getString(R.string.gnss_status_active)
            }
        }
    }

    private fun getConstellationName(type: Int): String {
        return when (type) {
            GnssStatus.CONSTELLATION_GPS -> getString(R.string.constellation_gps)
            GnssStatus.CONSTELLATION_GLONASS -> getString(R.string.constellation_glonass)
            GnssStatus.CONSTELLATION_GALILEO -> getString(R.string.constellation_galileo)
            GnssStatus.CONSTELLATION_BEIDOU -> getString(R.string.constellation_beidou)
            else -> getString(R.string.constellation_other)
        }
    }

    private fun updateSpectrogram(spectrogramData: FloatArray) {
        val width = SPECTROGRAM_WIDTH
        val height = SPECTROGRAM_HEIGHT
        val bitmap = spectrogramBitmap ?: return
        val pixels = spectrogramPixels ?: return
        val overlay = overlayBitmap ?: return
        val canvas = overlayCanvas ?: return

        // Shift old pixels to the left for each row
        for (y in 0 until height) {
            System.arraycopy(pixels, y * width + 1, pixels, y * width, width - 1)
        }

        // Draw new column at the end
        for (y in 0 until height) {
            // Map height to frequency bins (spectrogramData is downsampled to 256 usually)
            val binIdx = (y * spectrogramData.size / height).coerceAtMost(spectrogramData.size - 1)
            val mag = spectrogramData[binIdx]
            val normalized = (mag / 500f).coerceIn(0f, 1f)
            
            // Heatmap color: Blue -> Green -> Red
            val r = (normalized * 255).toInt()
            val g = ((1f - Math.abs(normalized - 0.5f) * 2f) * 255).toInt().coerceIn(0, 255)
            val b = ((1f - normalized) * 255).toInt()
            
            pixels[y * width + (width - 1)] = Color.rgb(r, g, b)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        // Draw to overlay
        overlayBitmap?.eraseColor(Color.TRANSPARENT)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        
        runOnUiThread {
            viewSpectrogramGnss.visibility = View.VISIBLE
            viewSpectrogramGnss.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter()
        filter.addAction("GNSS_UPDATE")
        filter.addAction("DRONE_UPDATE")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gnssUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gnssUpdateReceiver, filter)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    gnssStatusCallback?.let { locationManager.registerGnssStatusCallback(it, Handler(Looper.getMainLooper())) }
                }
            } catch (e: Exception) {}
        }
        
        updateStartedStatus()

        // If background service is running, show waiting state until first broadcast
        if (DroneDetectionService.isGnssRunning) {
            if (textInterferenceLevel.text == getString(R.string.interference_idle) || textInterferenceLevel.text == "-") {
                textInterferenceLevel.text = getString(R.string.waiting_for_data)
                textInterferenceLevel.setTextColor(Color.BLUE)
            }
        } else {
            // If background service is not running, we show idle for interference
            textSatelliteCount.text = getString(R.string.satellites_idle)
            textAverageCn0.text = getString(R.string.avg_cn0_idle)
            textInterferenceLevel.text = getString(R.string.interference_idle)
            textInterferenceLevel.setTextColor(Color.GRAY)
        }
        
        // Update visualizer and status text based on audio service state
        if (DroneDetectionService.isAudioRunning) {
            viewSpectrogramGnss.visibility = View.VISIBLE
            // Set initial state until first broadcast update
            textDetectionStatusGnss.text = getString(R.string.status_listening)
            textDetectionStatusGnss.setTextColor(Color.parseColor("#388E3C"))
        } else {
            viewSpectrogramGnss.visibility = View.GONE
            // If GNSS is active but audio is off, show GNSS status at the top to avoid confusion
            if (DroneDetectionService.isGnssRunning) {
                textDetectionStatusGnss.text = getString(R.string.gnss_status_active)
                textDetectionStatusGnss.setTextColor(Color.parseColor("#388E3C"))
            } else {
                textDetectionStatusGnss.text = getString(R.string.status_off)
                textDetectionStatusGnss.setTextColor(Color.GRAY)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(gnssUpdateReceiver) } catch (e: Exception) {}
        try {
            locationManager.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssStatusCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
            }
        } catch (e: Exception) {}
    }

    data class SatelliteInfo(val svid: Int, val constellation: String, val cn0: Float, val usedInFix: Boolean)

    class SatelliteAdapter(private val context: Context, private val satellites: List<SatelliteInfo>) : RecyclerView.Adapter<SatelliteAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val info: TextView = view.findViewById(android.R.id.text2)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sat = satellites[position]
            holder.name.text = context.getString(R.string.satellite_name_format, sat.constellation, sat.svid)
            holder.info.text = context.getString(R.string.satellite_signal_format, sat.cn0, if (sat.usedInFix) context.getString(R.string.in_use) else "")
            holder.info.setTextColor(if (sat.cn0 < 20f) Color.RED else Color.GRAY)
        }
        override fun getItemCount() = satellites.size
    }
}
