package com.example.dronesoundalert

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

class EmailLogActivity : AppCompatActivity() {
    private lateinit var adapter: LogAdapter
    private val logList = mutableListOf<LogEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_logs)

        val recyclerView = findViewById<RecyclerView>(R.id.emailLogsRecyclerView)
        val btnClear = findViewById<Button>(R.id.btnClearEmailLogs)

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadLogs()
        adapter = LogAdapter(this, logList)
        recyclerView.adapter = adapter

        btnClear.setOnClickListener {
            clearLogs()
        }
    }

    private fun loadLogs() {
        val prefs = getSharedPreferences("EmailLogs", Context.MODE_PRIVATE)
        val logsJson = prefs.getString("logs", "[]")
        val jsonArray = JSONArray(logsJson)
        
        logList.clear()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            logList.add(LogEntry(
                obj.getString("timestamp"),
                obj.getString("message")
            ))
        }
        logList.reverse()
    }

    private fun clearLogs() {
        getSharedPreferences("EmailLogs", Context.MODE_PRIVATE).edit().clear().apply()
        logList.clear()
        adapter.notifyDataSetChanged()
    }

    data class LogEntry(val timestamp: String, val message: String)

    class LogAdapter(private val context: Context, private val logs: List<LogEntry>) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val timestamp: TextView = view.findViewById(R.id.logTimestamp)
            val details: TextView = view.findViewById(R.id.logCoords)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.log_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = logs[position]
            holder.timestamp.text = entry.timestamp
            holder.details.text = entry.message
            
            // Lisätään kopiointiominaisuus pitkällä painalluksella
            holder.itemView.setOnLongClickListener {
                val fullText = "[${entry.timestamp}] ${entry.message}"
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Email Log", fullText)
                clipboard.setPrimaryClip(clip)
                
                Toast.makeText(context, "Loki kopioitu leikepöydälle", Toast.LENGTH_SHORT).show()
                true
            }
            
            // Myös tavallinen klikkaus voi kopioida, jotta se on helpompi löytää
            holder.itemView.setOnClickListener {
                val fullText = entry.message
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Email Log Message", fullText)
                clipboard.setPrimaryClip(clip)
                
                Toast.makeText(context, "Viesti kopioitu", Toast.LENGTH_SHORT).show()
            }
        }

        override fun getItemCount() = logs.size
    }
}
