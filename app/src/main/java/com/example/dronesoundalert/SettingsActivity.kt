package com.example.dronesoundalert

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var etSmsNumbers: TextInputEditText
    private lateinit var etEmailAddresses: TextInputEditText
    private lateinit var etBluetoothMessage: TextInputEditText
    
    private lateinit var labelDuration: TextView
    private lateinit var seekAlertDuration: SeekBar
    private lateinit var labelCooldown: TextView
    private lateinit var seekAlertCooldown: SeekBar
    
    private lateinit var switchSound: SwitchMaterial
    private lateinit var switchVibrate: SwitchMaterial
    private lateinit var switchLight: SwitchMaterial
    private lateinit var switchSms: SwitchMaterial
    private lateinit var switchEmail: SwitchMaterial
    private lateinit var switchBluetooth: SwitchMaterial

    // Email UI
    private lateinit var radioGroupEmail: RadioGroup
    private lateinit var layoutSendGrid: View
    private lateinit var layoutSmtp: View
    private lateinit var inputApiKey: TextInputEditText
    private lateinit var inputSmtpHost: TextInputEditText
    private lateinit var inputSmtpPort: TextInputEditText
    private lateinit var switchSmtpTls: SwitchMaterial
    private lateinit var switchSmtpStartTls: SwitchMaterial
    private lateinit var inputSmtpUser: TextInputEditText
    private lateinit var inputSmtpPass: TextInputEditText
    private lateinit var inputEmailSenderName: TextInputEditText
    private lateinit var inputEmailSubject: TextInputEditText
    private lateinit var switchEmailGps: SwitchMaterial
    private lateinit var switchEmailTime: SwitchMaterial
    private lateinit var switchEmailGoogleMaps: SwitchMaterial

    // Rsyslog UI
    private lateinit var switchRsyslog: SwitchMaterial
    private lateinit var switchRsyslogTls: SwitchMaterial
    private lateinit var switchRsyslogGps: SwitchMaterial
    private lateinit var switchRsyslogTime: SwitchMaterial
    private lateinit var switchRsyslogDebug: SwitchMaterial
    private lateinit var radioGroupRsyslogFormat: RadioGroup
    private lateinit var inputRsyslogHost: TextInputEditText
    private lateinit var inputRsyslogPort: TextInputEditText

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var selectedSoundUri: String? = null

    private val ringtonePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            selectedSoundUri = uri?.toString()
        }
    }

    private val apiKeyFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                readTextFromUri(uri)?.let { inputApiKey.setText(it.trim()) }
            }
        }
    }

    private val certFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                readTextFromUri(uri)?.let { cert ->
                    val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("rsyslog_cert", cert.trim()).apply()
                    Toast.makeText(this, "Certificate loaded and saved", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.alert_settings)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        bindViews()
        setupListeners()
        loadSettings()
    }

    private fun bindViews() {
        etSmsNumbers = findViewById(R.id.input_sms_numbers)
        etEmailAddresses = findViewById(R.id.input_email_addresses)
        etBluetoothMessage = findViewById(R.id.input_bluetooth_message)
        
        labelDuration = findViewById(R.id.label_duration)
        seekAlertDuration = findViewById(R.id.seek_alert_duration)
        labelCooldown = findViewById(R.id.label_cooldown)
        seekAlertCooldown = findViewById(R.id.seek_cooldown)
        
        switchSound = findViewById(R.id.switch_sound)
        switchVibrate = findViewById(R.id.switch_vibration)
        switchLight = findViewById(R.id.switch_light)
        switchSms = findViewById(R.id.switch_sms)
        switchEmail = findViewById(R.id.switch_email)
        switchBluetooth = findViewById(R.id.switch_bluetooth)

        radioGroupEmail = findViewById(R.id.radioGroupEmailMethod)
        layoutSendGrid = findViewById(R.id.layoutSendGrid)
        layoutSmtp = findViewById(R.id.layoutSmtp)
        inputApiKey = findViewById(R.id.input_api_key)
        inputSmtpHost = findViewById(R.id.input_smtp_host)
        inputSmtpPort = findViewById(R.id.input_smtp_port)
        switchSmtpTls = findViewById(R.id.switch_smtp_tls)
        switchSmtpStartTls = findViewById(R.id.switch_smtp_starttls)
        inputSmtpUser = findViewById(R.id.input_smtp_user)
        inputSmtpPass = findViewById(R.id.input_smtp_password)
        inputEmailSenderName = findViewById(R.id.input_email_sender_name)
        inputEmailSubject = findViewById(R.id.input_email_subject)
        switchEmailGps = findViewById(R.id.switch_email_gps)
        switchEmailTime = findViewById(R.id.switch_email_time)
        switchEmailGoogleMaps = findViewById(R.id.switch_email_google_maps)

        switchRsyslog = findViewById(R.id.switch_rsyslog)
        switchRsyslogTls = findViewById(R.id.switch_rsyslog_tls)
        switchRsyslogGps = findViewById(R.id.switch_rsyslog_gps)
        switchRsyslogTime = findViewById(R.id.switch_rsyslog_time)
        switchRsyslogDebug = findViewById(R.id.switch_rsyslog_debug)
        radioGroupRsyslogFormat = findViewById(R.id.radioGroupRsyslogFormat)
        inputRsyslogHost = findViewById(R.id.input_rsyslog_host)
        inputRsyslogPort = findViewById(R.id.input_rsyslog_port)
    }

    private fun setupListeners() {
        switchSms.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showSmsWarningDialog()
            }
        }

        radioGroupEmail.setOnCheckedChangeListener { _, checkedId ->
            layoutSendGrid.visibility = if (checkedId == R.id.radioSendGrid) View.VISIBLE else View.GONE
            layoutSmtp.visibility = if (checkedId == R.id.radioSmtp) View.VISIBLE else View.GONE
        }

        seekAlertDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { labelDuration.text = getString(R.string.alert_delay, p + 1) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekAlertCooldown.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { labelCooldown.text = getString(R.string.alert_cooldown, p + 1) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSelectSound).setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alert Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, selectedSoundUri?.let { Uri.parse(it) })
            }
            ringtonePickerLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btnLoadApiKey).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/plain"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            apiKeyFileLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btnLoadCertificate).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            certFileLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btnTestAlert).setOnClickListener { testAlerts() }
        findViewById<Button>(R.id.btnViewEmailLogs).setOnClickListener {
            startActivity(Intent(this, EmailLogActivity::class.java))
        }
        findViewById<Button>(R.id.btnViewRsyslogLogs).setOnClickListener {
            startActivity(Intent(this, RsyslogLogActivity::class.java))
        }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings(); finish() }
    }

    private fun readTextFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun showSmsWarningDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS Alert Notice")
            .setMessage("Note: Android restricts SMS sending. This feature might not work until the app is fully approved on Google Play Store. You may need to manually grant SMS permissions in system settings.")
            .setPositiveButton("I Understand", null)
            .show()
    }

    private fun SharedPreferences.getSafeString(key: String, defValue: String): String {
        return try {
            this.getString(key, defValue) ?: defValue
        } catch (e: Exception) {
            // If it's stored as an Int, convert to String
            this.all[key]?.toString() ?: defValue
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        etSmsNumbers.setText(prefs.getSafeString("sms_numbers", ""))
        etEmailAddresses.setText(prefs.getSafeString("email_addresses", ""))
        etBluetoothMessage.setText(prefs.getSafeString("bluetooth_message", "DRONE"))
        
        val duration = try { prefs.getInt("alert_duration_seconds", 1) } catch(e: Exception) { prefs.getSafeString("alert_duration_seconds", "1").toIntOrNull() ?: 1 }
        seekAlertDuration.progress = (duration - 1).coerceAtLeast(0)
        labelDuration.text = getString(R.string.alert_delay, duration)
        
        val cooldown = try { prefs.getInt("alert_cooldown_minutes", 1) } catch(e: Exception) { prefs.getSafeString("alert_cooldown_minutes", "1").toIntOrNull() ?: 1 }
        seekAlertCooldown.progress = (cooldown - 1).coerceAtLeast(0)
        labelCooldown.text = getString(R.string.alert_cooldown, cooldown)
        
        switchSound.isChecked = prefs.getBoolean("alert_sound", false)
        switchVibrate.isChecked = prefs.getBoolean("alert_vibrate", false)
        switchLight.isChecked = prefs.getBoolean("alert_light", false)
        switchSms.isChecked = prefs.getBoolean("alert_sms", false)
        switchEmail.isChecked = prefs.getBoolean("alert_email", false)
        switchBluetooth.isChecked = prefs.getBoolean("alert_bluetooth", false)
        selectedSoundUri = prefs.getSafeString("alert_sound_uri", "")

        // Email settings
        val isSmtp = prefs.getBoolean("use_smtp", false)
        radioGroupEmail.check(if (isSmtp) R.id.radioSmtp else R.id.radioSendGrid)
        layoutSendGrid.visibility = if (isSmtp) View.GONE else View.VISIBLE
        layoutSmtp.visibility = if (isSmtp) View.VISIBLE else View.GONE

        inputApiKey.setText(prefs.getSafeString("email_api_key", ""))
        inputSmtpHost.setText(prefs.getSafeString("smtp_host", ""))
        
        inputSmtpPort.setText(prefs.getSafeString("smtp_port", "587"))

        switchSmtpTls.isChecked = prefs.getBoolean("smtp_use_tls", false)
        switchSmtpStartTls.isChecked = prefs.getBoolean("smtp_use_starttls", true)
        inputSmtpUser.setText(prefs.getSafeString("smtp_username", ""))
        inputSmtpPass.setText(prefs.getSafeString("smtp_password", ""))
        inputEmailSenderName.setText(prefs.getSafeString("email_sender_name", "DSA Alert"))
        inputEmailSubject.setText(prefs.getSafeString("email_subject", "Drone Detected!"))
        switchEmailGps.isChecked = prefs.getBoolean("email_include_gps", true)
        switchEmailTime.isChecked = prefs.getBoolean("email_include_time", true)
        switchEmailGoogleMaps.isChecked = prefs.getBoolean("email_include_google_maps", true)

        // Rsyslog
        switchRsyslog.isChecked = prefs.getBoolean("alert_rsyslog", false)
        switchRsyslogTls.isChecked = prefs.getBoolean("rsyslog_use_tls", false)
        switchRsyslogGps.isChecked = prefs.getBoolean("rsyslog_include_gps", true)
        switchRsyslogTime.isChecked = prefs.getBoolean("rsyslog_include_time", true)
        switchRsyslogDebug.isChecked = prefs.getBoolean("rsyslog_include_debug", false)
        val isJson = prefs.getBoolean("rsyslog_use_json", true)
        radioGroupRsyslogFormat.check(if (isJson) R.id.radioRsyslogJson else R.id.radioRsyslogNormal)
        inputRsyslogHost.setText(prefs.getSafeString("rsyslog_host", ""))

        inputRsyslogPort.setText(prefs.getSafeString("rsyslog_port", "514"))
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("sms_numbers", etSmsNumbers.text.toString())
            putString("email_addresses", etEmailAddresses.text.toString())
            putString("bluetooth_message", etBluetoothMessage.text.toString())
            putInt("alert_duration_seconds", seekAlertDuration.progress + 1)
            putInt("alert_cooldown_minutes", seekAlertCooldown.progress + 1)
            
            putBoolean("alert_sound", switchSound.isChecked)
            putBoolean("alert_vibrate", switchVibrate.isChecked)
            putBoolean("alert_light", switchLight.isChecked)
            putBoolean("alert_sms", switchSms.isChecked)
            putBoolean("alert_email", switchEmail.isChecked)
            putBoolean("alert_bluetooth", switchBluetooth.isChecked)
            putString("alert_sound_uri", selectedSoundUri)

            // Email settings
            putBoolean("use_smtp", radioGroupEmail.checkedRadioButtonId == R.id.radioSmtp)
            putString("email_api_key", inputApiKey.text.toString())
            putString("smtp_host", inputSmtpHost.text.toString())
            putString("smtp_port", inputSmtpPort.text.toString())
            putBoolean("smtp_use_tls", switchSmtpTls.isChecked)
            putBoolean("smtp_use_starttls", switchSmtpStartTls.isChecked)
            putString("smtp_username", inputSmtpUser.text.toString())
            putString("smtp_password", inputSmtpPass.text.toString())
            putString("email_sender_name", inputEmailSenderName.text.toString())
            putString("email_subject", inputEmailSubject.text.toString())
            putBoolean("email_include_gps", switchEmailGps.isChecked)
            putBoolean("email_include_time", switchEmailTime.isChecked)
            putBoolean("email_include_google_maps", switchEmailGoogleMaps.isChecked)

            // Rsyslog
            putBoolean("alert_rsyslog", switchRsyslog.isChecked)
            putBoolean("rsyslog_use_tls", switchRsyslogTls.isChecked)
            putBoolean("rsyslog_include_gps", switchRsyslogGps.isChecked)
            putBoolean("rsyslog_include_time", switchRsyslogTime.isChecked)
            putBoolean("rsyslog_include_debug", switchRsyslogDebug.isChecked)
            putBoolean("rsyslog_use_json", radioGroupRsyslogFormat.checkedRadioButtonId == R.id.radioRsyslogJson)
            putString("rsyslog_host", inputRsyslogHost.text.toString())
            putString("rsyslog_port", inputRsyslogPort.text.toString())
            
            apply()
        }

        // Reset cooldown in service if it's running
        val reloadIntent = Intent(this, DroneDetectionService::class.java)
        reloadIntent.action = DroneDetectionService.ACTION_RELOAD_SETTINGS
        startService(reloadIntent)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun testAlerts() {
        if (switchVibrate.isChecked) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        if (switchSound.isChecked) {
            try {
                val uri = selectedSoundUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val r = RingtoneManager.getRingtone(applicationContext, uri)
                r.play()
                Handler(Looper.getMainLooper()).postDelayed({ if (r.isPlaying) r.stop() }, 2000)
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (switchLight.isChecked) flashLight(5)

        val numbers = etSmsNumbers.text.toString()
        if (switchSms.isChecked && numbers.isNotEmpty()) sendTestSms(numbers)

        val emailAddresses = etEmailAddresses.text.toString()
        if (switchEmail.isChecked && emailAddresses.isNotEmpty()) {
            val title = "DSA TEST"
            var message = "Test alert email works!"
            
            val includeGps = switchEmailGps.isChecked
            val includeTime = switchEmailTime.isChecked
            val includeMaps = switchEmailGoogleMaps.isChecked

            if (includeGps || includeTime) {
                val extraDetails = StringBuilder("\n--- Details ---")
                if (includeTime) {
                    extraDetails.append("\nTime: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                }
                
                if (includeGps) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                            .addOnSuccessListener { loc ->
                                var locStr = if (loc != null) "\nLocation: ${loc.latitude}, ${loc.longitude}" else "\nLocation: Unknown"
                                if (loc != null && includeMaps) {
                                    locStr += "\nhttps://www.google.com/maps/search/?api=1&query=${loc.latitude},${loc.longitude}"
                                }
                                sendTestEmails(emailAddresses, title, message + extraDetails.toString() + locStr)
                            }
                    } else {
                        extraDetails.append("\nLocation: Permission Missing")
                        sendTestEmails(emailAddresses, title, message + extraDetails.toString())
                    }
                } else {
                    sendTestEmails(emailAddresses, title, message + extraDetails.toString())
                }
            } else {
                sendTestEmails(emailAddresses, title, message)
            }
        }

        if (switchRsyslog.isChecked) {
            val host = inputRsyslogHost.text.toString()
            val port = inputRsyslogPort.text.toString().toIntOrNull() ?: 514
            val useTls = switchRsyslogTls.isChecked
            val cert = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE).getString("rsyslog_cert", null)
            val includeGps = switchRsyslogGps.isChecked
            val includeTime = switchRsyslogTime.isChecked
            val includeDebug = switchRsyslogDebug.isChecked
            val useJson = radioGroupRsyslogFormat.checkedRadioButtonId == R.id.radioRsyslogJson

            if (includeGps) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { loc ->
                            val payload = if (useJson) formatRsyslogJson("DSA TEST", includeTime, loc, includeDebug) 
                                          else formatRsyslogNormal("DSA TEST", includeTime, loc)
                            RsyslogSender.send(this, host, port, payload, useTls, cert)
                        }
                } else {
                    val payload = if (useJson) formatRsyslogJson("DSA TEST (Loc Missing)", includeTime, null, includeDebug)
                                  else formatRsyslogNormal("DSA TEST (Loc Missing)", includeTime, null)
                    RsyslogSender.send(this, host, port, payload, useTls, cert)
                }
            } else {
                val payload = if (useJson) formatRsyslogJson("DSA TEST", includeTime, null, includeDebug)
                              else formatRsyslogNormal("DSA TEST", includeTime, null)
                RsyslogSender.send(this, host, port, payload, useTls, cert)
            }
        }
    }

    private fun formatRsyslogJson(message: String, includeTime: Boolean, location: android.location.Location?, includeDebug: Boolean): String {
        val root = org.json.JSONObject()
        root.put("message", message)
        if (includeTime) {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            root.put("timestamp", sdf.format(Date()))
        }
        if (location != null) {
            root.put("lat", location.latitude)
            root.put("lon", location.longitude)
        }
        if (includeDebug) {
            val debugObj = org.json.JSONObject()
            debugObj.put("model", Build.MODEL)
            debugObj.put("android_version", Build.VERSION.RELEASE)
            debugObj.put("app_version", "1.0")
            root.put("debug", debugObj)
        }
        return root.toString(2) // 2 space indentation for better readability
    }

    private fun formatRsyslogNormal(message: String, includeTime: Boolean, location: android.location.Location?): String {
        val sb = StringBuilder(message)
        if (includeTime) {
            sb.append(" [").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("]")
        }
        if (location != null) {
            sb.append(" (Loc: ").append(location.latitude).append(", ").append(location.longitude).append(")")
        }
        return sb.toString()
    }

    private fun sendTestEmails(addresses: String, title: String, body: String) {
        addresses.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { address ->
            EmailSender.send(this, address, title, body)
        }
    }

    private fun sendTestSms(numbers: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), 101)
            return
        }
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        numbers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { num ->
            try { smsManager.sendTextMessage(num, null, "Drone Sound Alert: Test success!", null, null) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun flashLight(times: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 102)
            return
        }
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val handler = Handler(Looper.getMainLooper())
            var count = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (count < times * 2) {
                        try { cameraManager.setTorchMode(cameraId, count % 2 == 0) } catch (e: Exception) { e.printStackTrace() }
                        count++
                        handler.postDelayed(this, 300)
                    } else {
                        try { cameraManager.setTorchMode(cameraId, false) } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
            handler.post(runnable)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
