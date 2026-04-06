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
import org.json.JSONObject

class GnssLogActivity : AppCompatActivity() {
    private lateinit var adapter: LogAdapter
    private val logList = mutableListOf<LogEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gnss_logs)

        val recyclerView = findViewById<RecyclerView>(R.id.gnssLogsRecyclerView)
        val btnClear = findViewById<Button>(R.id.btnClearGnssLogs)

        recyclerView.layoutManager = LinearLayoutManager(this)
        loadLogs()
        adapter = LogAdapter(this, logList)
        recyclerView.adapter = adapter

        btnClear.setOnClickListener {
            clearLogs()
        }
    }

    private fun loadLogs() {
        val prefs = getSharedPreferences("GnssLogs", Context.MODE_PRIVATE)
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
        getSharedPreferences("GnssLogs", Context.MODE_PRIVATE).edit().clear().apply()
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
            
            holder.itemView.setOnClickListener {
                copyToClipboard(entry.message, "Viesti kopioitu")
            }
            
            holder.itemView.setOnLongClickListener {
                copyToClipboard("[${entry.timestamp}] ${entry.message}", "Loki kopioitu")
                true
            }
        }
        
        private fun copyToClipboard(text: String, toastMsg: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GNSS Log", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }

        override fun getItemCount() = logs.size
    }
}
