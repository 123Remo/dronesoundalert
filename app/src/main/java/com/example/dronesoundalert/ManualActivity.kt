package com.example.dronesoundalert

import android.content.Context
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class ManualActivity : AppCompatActivity() {
    
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)
        
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.manual_title)
        }

        webView = findViewById(R.id.webViewManual)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }

        val htmlContent = getString(R.string.manual_content)
        val styledHtml = """
            <html>
            <head>
                <style>
                    body { font-family: sans-serif; line-height: 1.5; color: #333; padding: 16px; }
                    h3 { color: #1a73e8; border-bottom: 1px solid #ccc; padding-bottom: 4px; margin-top: 24px; }
                    ul { padding-left: 20px; }
                    li { margin-bottom: 8px; }
                    b { color: #000; }
                </style>
            </head>
            <body>
                $htmlContent
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
    }

    private fun createWebPrintJob(webView: WebView) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "${getString(R.string.app_name)} ${getString(R.string.manual_title)}"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        
        printManager.print(
            jobName,
            printAdapter,
            PrintAttributes.Builder().build()
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manual, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_print -> {
                createWebPrintJob(webView)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
