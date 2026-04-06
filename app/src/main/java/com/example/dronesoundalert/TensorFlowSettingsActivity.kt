package com.example.dronesoundalert

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream

class TensorFlowSettingsActivity : AppCompatActivity() {

    private lateinit var switchUseTfLite: SwitchMaterial
    private lateinit var textModelStatus: TextView
    private lateinit var btnImport: Button
    
    private lateinit var seekGain: SeekBar
    private lateinit var labelGain: TextView
    private lateinit var seekMinVolume: SeekBar
    private lateinit var labelMinVolume: TextView
    private lateinit var radioGroupWindowSize: RadioGroup
    
    private lateinit var seekThreshold: SeekBar
    private lateinit var labelThreshold: TextView
    private lateinit var seekSmoothing: SeekBar
    private lateinit var labelSmoothing: TextView
    private lateinit var seekConsecutive: SeekBar
    private lateinit var labelConsecutive: TextView
    
    private lateinit var btnSave: Button

    private val modelPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importModel(uri)
            }
        }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tensorflow_settings)

        // UI Bindings
        switchUseTfLite = findViewById(R.id.switch_use_tflite)
        textModelStatus = findViewById(R.id.text_model_status)
        btnImport = findViewById(R.id.btn_import_model)
        
        seekGain = findViewById(R.id.seek_tflite_gain)
        labelGain = findViewById(R.id.label_tflite_gain)
        seekMinVolume = findViewById(R.id.seek_tflite_min_volume)
        labelMinVolume = findViewById(R.id.label_tflite_min_volume)
        radioGroupWindowSize = findViewById(R.id.radioGroupWindowSize)
        
        seekThreshold = findViewById(R.id.seek_tflite_threshold)
        labelThreshold = findViewById(R.id.label_tflite_threshold)
        seekSmoothing = findViewById(R.id.seek_tflite_smoothing)
        labelSmoothing = findViewById(R.id.label_tflite_smoothing)
        seekConsecutive = findViewById(R.id.seek_tflite_consecutive)
        labelConsecutive = findViewById(R.id.label_tflite_consecutive)
        
        btnSave = findViewById(R.id.btn_save_tflite_settings)

        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        
        // Load Values
        switchUseTfLite.isChecked = prefs.getBoolean("use_tflite", false)
        
        val gain = prefs.getSafeFloat("tflite_gain", 1.0f)
        seekGain.progress = (gain * 10).toInt()
        labelGain.text = getString(R.string.input_gain, gain)
        
        val minVol = prefs.getSafeInt("tflite_min_volume", 100)
        seekMinVolume.progress = minVol
        labelMinVolume.text = getString(R.string.min_volume, minVol)
        
        val windowSize = prefs.getSafeFloat("tflite_window_size", 1.0f)
        if (windowSize == 0.5f) {
            findViewById<RadioButton>(R.id.radioWindow05).isChecked = true
        } else {
            findViewById<RadioButton>(R.id.radioWindow10).isChecked = true
        }
        
        val threshold = prefs.getSafeFloat("tflite_threshold", 0.70f)
        seekThreshold.progress = (threshold * 100).toInt()
        labelThreshold.text = getString(R.string.classification_threshold, threshold)

        val smoothing = prefs.getSafeInt("tflite_smoothing", 1)
        seekSmoothing.progress = smoothing - 1
        labelSmoothing.text = getString(R.string.smoothing_label, smoothing)

        val consecutive = prefs.getSafeInt("tflite_consecutive_count", 3)
        seekConsecutive.progress = consecutive - 3
        labelConsecutive.text = getString(R.string.consecutive_label, consecutive)

        updateModelStatus()

        // Listeners
        btnImport.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            modelPickerLauncher.launch(intent)
        }

        seekGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val g = progress / 10f
                labelGain.text = getString(R.string.input_gain, g)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekMinVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelMinVolume.text = getString(R.string.min_volume, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val t = progress / 100f
                labelThreshold.text = getString(R.string.classification_threshold, t)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekSmoothing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelSmoothing.text = getString(R.string.smoothing_label, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekConsecutive.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelConsecutive.text = getString(R.string.consecutive_label, progress + 3)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            val selectedWindowSize = if (findViewById<RadioButton>(R.id.radioWindow05).isChecked) 0.5f else 1.0f
            
            with(prefs.edit()) {
                putBoolean("use_tflite", switchUseTfLite.isChecked)
                putFloat("tflite_gain", seekGain.progress / 10f)
                putInt("tflite_min_volume", seekMinVolume.progress)
                putFloat("tflite_window_size", selectedWindowSize)
                putFloat("tflite_threshold", seekThreshold.progress / 100f)
                putInt("tflite_smoothing", seekSmoothing.progress + 1)
                putInt("tflite_consecutive_count", seekConsecutive.progress + 3)
                apply()
            }

            // Reload settings in service if it's running
            val reloadIntent = Intent(this, DroneDetectionService::class.java)
            reloadIntent.action = DroneDetectionService.ACTION_RELOAD_SETTINGS
            startService(reloadIntent)

            Toast.makeText(this, "AI Settings Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun importModel(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val modelFile = File(filesDir, "model.tflite")
            val outputStream = FileOutputStream(modelFile)
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            updateModelStatus()
            Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error importing model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateModelStatus() {
        val modelFile = File(filesDir, "model.tflite")
        if (modelFile.exists()) {
            val size = modelFile.length() / 1024
            textModelStatus.text = getString(R.string.model_loaded_format, size.toInt())
            textModelStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
        } else {
            textModelStatus.text = getString(R.string.model_not_loaded)
            textModelStatus.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
        }
    }
}
