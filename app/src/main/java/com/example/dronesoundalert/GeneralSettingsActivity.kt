package com.example.dronesoundalert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

class GeneralSettingsActivity : AppCompatActivity() {

    private var pendingIncludeConfidential = false

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let { exportSettingsToUri(it, pendingIncludeConfidential) }
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSettingsFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.general_settings)

        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)

        val switchScreen = findViewById<SwitchMaterial>(R.id.switch_keep_screen_on)
        val switchDarkMode = findViewById<SwitchMaterial>(R.id.switch_dark_mode)
        val switchSpectrogram = findViewById<SwitchMaterial>(R.id.switch_spectrogram)
        val switchOscilloscope = findViewById<SwitchMaterial>(R.id.switch_oscilloscope)
        val switchSpectrogramThreshold = findViewById<SwitchMaterial>(R.id.switch_spectrogram_threshold)
        val checkGps = findViewById<CheckBox>(R.id.check_include_gps)
        val checkTime = findViewById<CheckBox>(R.id.check_include_time)
        val btnSave = findViewById<Button>(R.id.btnSaveGeneralSettings)
        val btnExport = findViewById<Button>(R.id.btnExportSettings)
        val btnImport = findViewById<Button>(R.id.btnImportSettings)
        val checkIncludeConfidential = findViewById<CheckBox>(R.id.check_include_confidential)
        val textConfidentialWarning = findViewById<TextView>(R.id.text_confidential_warning)

        // Load values
        switchScreen.isChecked = prefs.getBoolean("keep_screen_on", false)
        switchDarkMode.isChecked = prefs.getBoolean("dark_mode", false)
        switchSpectrogram.isChecked = prefs.getBoolean("use_spectrogram", true)
        switchOscilloscope.isChecked = prefs.getBoolean("use_oscilloscope", false)
        switchSpectrogramThreshold.isChecked = prefs.getBoolean("show_spectrogram_threshold", true)
        checkGps.isChecked = prefs.getBoolean("include_gps", false)
        checkTime.isChecked = prefs.getBoolean("include_time", false)

        switchSpectrogramThreshold.visibility = if (switchSpectrogram.isChecked || switchOscilloscope.isChecked) View.VISIBLE else View.GONE

        switchSpectrogram.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) switchOscilloscope.isChecked = false
            switchSpectrogramThreshold.visibility = if (isChecked || switchOscilloscope.isChecked) View.VISIBLE else View.GONE
        }

        switchOscilloscope.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) switchSpectrogram.isChecked = false
            switchSpectrogramThreshold.visibility = if (isChecked || switchSpectrogram.isChecked) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            val isDarkMode = switchDarkMode.isChecked

            with(prefs.edit()) {
                putBoolean("keep_screen_on", switchScreen.isChecked)
                putBoolean("dark_mode", isDarkMode)
                putBoolean("use_spectrogram", switchSpectrogram.isChecked)
                putBoolean("use_oscilloscope", switchOscilloscope.isChecked)
                putBoolean("show_spectrogram_threshold", switchSpectrogramThreshold.isChecked)
                putBoolean("include_gps", checkGps.isChecked)
                putBoolean("include_time", checkTime.isChecked)
                apply()
            }

            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )

            // Reload settings in the service if it's running
            val reloadIntent = Intent(this, DroneDetectionService::class.java)
            reloadIntent.action = DroneDetectionService.ACTION_RELOAD_SETTINGS
            startService(reloadIntent)

            Toast.makeText(this, "Asetukset tallennettu!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnExport.setOnClickListener {
            pendingIncludeConfidential = checkIncludeConfidential.isChecked
            createDocumentLauncher.launch("dsa_asetukset.xml")
        }

        btnImport.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("text/xml", "application/xml"))
        }

        checkIncludeConfidential.setOnCheckedChangeListener { _, isChecked ->
            textConfidentialWarning.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun exportSettingsToUri(uri: Uri, includeConfidential: Boolean) {
        try {
            val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
            val allEntries = prefs.all
            val props = Properties()
            
            for ((key, value) in allEntries) {
                if (!includeConfidential) {
                    if (key.contains("password", ignoreCase = true) || 
                        key.contains("api_key", ignoreCase = true) || 
                        key.contains("cert", ignoreCase = true)) {
                        continue
                    }
                }
                // Store as key=type:value to preserve type info
                val type = value?.let { it::class.java.simpleName } ?: "String"
                props.setProperty(key, "$type:$value")
            }

            contentResolver.openOutputStream(uri)?.use { 
                props.storeToXML(it, "DSA Settings Export")
            }
            Toast.makeText(this, getString(R.string.settings_exported), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_export_error) + ": ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val props = Properties()
            contentResolver.openInputStream(uri)?.use {
                props.loadFromXML(it)
            }

            val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            for (name in props.stringPropertyNames()) {
                val rawValue = props.getProperty(name)
                val parts = rawValue.split(":", limit = 2)
                if (parts.size < 2) continue
                
                val type = parts[0]
                val value = parts[1]

                // Ensure EditText-based fields are always stored as String to avoid ClassCastException
                val forceStringKeys = setOf(
                    "smtp_port", "rsyslog_port", "sms_numbers", "email_addresses",
                    "bluetooth_message", "email_api_key", "smtp_host", "smtp_username",
                    "smtp_password", "email_sender_name", "email_subject", "rsyslog_host",
                    "alert_sound_uri"
                )

                if (name in forceStringKeys) {
                    editor.putString(name, value)
                    continue
                }

                when (type) {
                    "Boolean" -> editor.putBoolean(name, value.toBoolean())
                    "Integer" -> editor.putInt(name, value.toInt() )
                    "Float" -> editor.putFloat(name, value.toFloat())
                    "Long" -> editor.putLong(name, value.toLong())
                    else -> editor.putString(name, value)
                }
            }
            editor.apply()
            
            // Reload settings in the service if it's running
            val reloadIntent = Intent(this, DroneDetectionService::class.java)
            reloadIntent.action = DroneDetectionService.ACTION_RELOAD_SETTINGS
            startService(reloadIntent)

            Toast.makeText(this, getString(R.string.settings_imported), Toast.LENGTH_SHORT).show()
            recreate()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.settings_import_error) + ": ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
