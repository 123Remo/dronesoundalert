package com.example.dronesoundalert

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class GpsSettingsActivity : AppCompatActivity() {

    private lateinit var labelGnssSensitivity: TextView
    private lateinit var seekGnssSensitivity: SeekBar
    private lateinit var labelBaselineDuration: TextView
    private lateinit var seekBaselineDuration: SeekBar
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps_settings)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.gnss_settings)
        }

        labelGnssSensitivity = findViewById(R.id.label_gnss_sensitivity)
        seekGnssSensitivity = findViewById(R.id.seek_gnss_sensitivity)
        labelBaselineDuration = findViewById(R.id.label_baseline_duration)
        seekBaselineDuration = findViewById(R.id.seek_baseline_duration)
        btnSave = findViewById(R.id.btnSaveGpsSettings)

        setupListeners()
        loadSettings()
    }

    private fun setupListeners() {
        seekGnssSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                labelGnssSensitivity.text = getString(R.string.gnss_sensitivity, p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekBaselineDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                labelBaselineDuration.text = getString(R.string.baseline_duration, p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            saveSettings()
            finish()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        
        val gnssSens = try {
            prefs.getFloat("gnss_sensitivity", 18f)
        } catch (e: Exception) {
            prefs.all["gnss_sensitivity"]?.toString()?.toFloatOrNull() ?: 18f
        }.toInt()
        seekGnssSensitivity.progress = gnssSens
        labelGnssSensitivity.text = getString(R.string.gnss_sensitivity, gnssSens)
        
        val baseline = try {
            prefs.getInt("gnss_baseline_duration", 30)
        } catch (e: Exception) {
            prefs.all["gnss_baseline_duration"]?.toString()?.toIntOrNull() ?: 30
        }
        seekBaselineDuration.progress = baseline
        labelBaselineDuration.text = getString(R.string.baseline_duration, baseline)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("gnss_sensitivity", seekGnssSensitivity.progress.toFloat())
            putInt("gnss_baseline_duration", seekBaselineDuration.progress)
            apply()
        }

        // Reset cooldown in service if it's running
        val reloadIntent = Intent(this, DroneDetectionService::class.java)
        reloadIntent.action = DroneDetectionService.ACTION_RELOAD_SETTINGS
        startService(reloadIntent)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
