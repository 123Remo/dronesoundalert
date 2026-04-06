package com.example.dronesoundalert

import android.os.Build
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

object RsyslogSender {

    fun send(context: android.content.Context, host: String, port: Int, message: String, useTls: Boolean = false, certString: String? = null) {
        if (host.isEmpty()) return

        Thread {
            try {
                val formattedMessage = formatRfc5424(message)
                if (useTls) {
                    sendTls(host, port, formattedMessage, certString)
                } else {
                    sendUdp(host, port, formattedMessage)
                }
                RsyslogLogActivity.saveLog(context, "SUCCESS: Sent to $host:$port")
            } catch (e: Exception) {
                e.printStackTrace()
                RsyslogLogActivity.saveLog(context, "ERROR: ${e.message}")
            }
        }.start()
    }

    /**
     * Formats the message according to RFC 5424
     * Format: <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG
     */
    private fun formatRfc5424(message: String): String {
        val pri = "<165>" // LOCAL4 (20) + NOTICE (5)
        val version = "1"
        val timestamp = getIsoTimestamp() // esim. 2026-04-05T18:55:49.123Z
        val hostname = getHostname()
        val appName = "DSA"
        val procId = "-"
        val msgId = "-"
        val structuredData = "-"

        return "$pri$version $timestamp $hostname $appName $procId $msgId $structuredData $message"
    }

    private fun sendUdp(host: String, port: Int, fullMessage: String) {
        val socket = DatagramSocket()
        val address = InetAddress.getByName(host)
        val bytes = fullMessage.toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(bytes, bytes.size, address, port)
        socket.send(packet)
        socket.close()
    }

    private fun sendTls(host: String, port: Int, fullMessage: String, certString: String?) {
        val sslContext = if (!certString.isNullOrEmpty()) {
            createSslContext(certString)
        } else {
            SSLContext.getDefault()
        }

        val factory = sslContext.socketFactory
        val socket = factory.createSocket(host, port) as SSLSocket
        
        // Some Syslog servers expect a newline at the end of TLS frames
        val frame = "$fullMessage\n"
        socket.outputStream.write(frame.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
        socket.close()
    }

    private fun createSslContext(certString: String): SSLContext {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certString.toByteArray())) as X509Certificate
        
        val keyStoreType = KeyStore.getDefaultType()
        val keyStore = KeyStore.getInstance(keyStoreType).apply {
            load(null, null)
            setCertificateEntry("ca", cert)
        }

        val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
        val tmf = TrustManagerFactory.getInstance(tmfAlgorithm).apply {
            init(keyStore)
        }

        return SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun getHostname(): String {
        val model = Build.MODEL ?: "AndroidDevice"
        return model.replace(" ", "_")
    }
}
