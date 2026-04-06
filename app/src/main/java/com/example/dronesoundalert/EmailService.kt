package com.example.dronesoundalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

// Malli sähköpostiviestille SendGridille
data class EmailRequest(
    val personalizations: List<Personalization>,
    val from: From,
    val subject: String,
    val content: List<Content>
)

data class Personalization(val to: List<To>)
data class To(val email: String)
data class From(val email: String, val name: String? = null)
data class Content(val type: String, val value: String)

// Rajapinta SendGrid API:lle
interface SendGridApi {
    @POST("v3/mail/send")
    fun sendEmail(
        @Header("Authorization") auth: String,
        @Body request: EmailRequest
    ): Call<Void>
}

object EmailSender {
    private const val BASE_URL = "https://api.sendgrid.com/"
    private const val DEFAULT_FROM_EMAIL = "noreply@dronesoundalert.com"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val sendGridApi = retrofit.create(SendGridApi::class.java)

    fun send(context: Context, toEmail: String, subject: String, body: String, apiKey: String? = null) {
        val prefs = context.getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val useSmtp = prefs.getBoolean("use_smtp", false)

        if (useSmtp) {
            sendViaSmtp(context, toEmail, subject, body)
        } else {
            sendViaSendGrid(context, toEmail, subject, body, apiKey)
        }
    }

    private fun sendViaSendGrid(context: Context, toEmail: String, subject: String, body: String, apiKey: String?) {
        val prefs = context.getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val rawKey = apiKey ?: prefs.getString("email_api_key", "") ?: ""
        val finalApiKey = rawKey.trim()
        val senderName = prefs.getString("email_sender_name", "DSA Alert")
        val customSubject = prefs.getString("email_subject", subject)

        logDebug(context, "SendGrid: Starting transmission to: $toEmail")

        if (finalApiKey.isEmpty()) {
            logDebug(context, "SendGrid ERROR: API Key missing!")
            return
        }

        val request = EmailRequest(
            listOf(Personalization(listOf(To(toEmail)))),
            From(DEFAULT_FROM_EMAIL, senderName),
            customSubject ?: subject,
            listOf(Content("text/plain", body))
        )

        Thread {
            try {
                val response = sendGridApi.sendEmail("Bearer $finalApiKey", request).execute()
                if (response.isSuccessful) {
                    logDebug(context, "SendGrid: Email sent successfully")
                } else {
                    val errorBody = response.errorBody()?.string() ?: ""
                    logDebug(context, "SendGrid ERROR: Server responded ${response.code()}. $errorBody")
                }
            } catch (e: Exception) {
                logDebug(context, "SendGrid EXCEPTION: ${e.message}")
            }
        }.start()
    }

    private fun sendViaSmtp(context: Context, toEmail: String, subject: String, body: String) {
        val prefs = context.getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        val host = prefs.getString("smtp_host", "")?.trim() ?: ""
        val port = prefs.getString("smtp_port", "587")?.trim() ?: "587"
        val user = prefs.getString("smtp_username", "")?.trim() ?: ""
        val pass = prefs.getString("smtp_password", "")?.trim() ?: ""
        val useTls = prefs.getBoolean("smtp_use_tls", false)
        val useStartTls = prefs.getBoolean("smtp_use_starttls", true)
        val senderName = prefs.getString("email_sender_name", "DSA Alert")
        val customSubject = prefs.getString("email_subject", subject)

        logDebug(context, "SMTP: Starting transmission to: $toEmail")

        if (host.isEmpty() || user.isEmpty()) {
            logDebug(context, "SMTP ERROR: Server or username missing!")
            return
        }

        val props = Properties()
        props["mail.smtp.host"] = host
        props["mail.smtp.port"] = port
        props["mail.smtp.auth"] = "true"
        props["mail.smtp.connectiontimeout"] = "10000"
        props["mail.smtp.timeout"] = "10000"
        
        if (useTls) {
            props["mail.smtp.socketFactory.port"] = port
            props["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"
            props["mail.smtp.socketFactory.fallback"] = "false"
            props["mail.smtp.ssl.enable"] = "true"
        }
        
        if (useStartTls) {
            props["mail.smtp.starttls.enable"] = "true"
            props["mail.smtp.starttls.required"] = "true"
        }

        val debugOutputStream = ByteArrayOutputStream()
        val debugPrintStream = PrintStream(debugOutputStream)

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(user, pass)
            }
        })
        
        session.debug = true
        session.setDebugOut(debugPrintStream)

        Thread {
            try {
                val message = MimeMessage(session)
                message.setFrom(InternetAddress(user, senderName))
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                message.subject = customSubject ?: subject
                message.setText(body)

                Transport.send(message)
                logDebug(context, "SMTP: Email sent successfully")
            } catch (e: Exception) {
                val debugLogs = debugOutputStream.toString("UTF-8")
                if (debugLogs.isNotEmpty()) {
                    logDebug(context, "SMTP DEBUG LOG:\n$debugLogs")
                }
                logDebug(context, "SMTP ERROR: ${e.message}")
            } finally {
                debugPrintStream.close()
            }
        }.start()
    }

    private fun logDebug(context: Context, message: String) {
        val prefs = context.getSharedPreferences("EmailLogs", Context.MODE_PRIVATE)
        val logsJson = prefs.getString("logs", "[]")
        val jsonArray = JSONArray(logsJson)
        
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = JSONObject().apply {
            put("timestamp", timestamp)
            put("message", message)
        }
        
        jsonArray.put(newEntry)
        prefs.edit().putString("logs", jsonArray.toString()).apply()
    }
}
