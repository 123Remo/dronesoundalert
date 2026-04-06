package com.example.dronesoundalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter

class GnssAnalysisActivity : AppCompatActivity() {

    private lateinit var cn0Chart: LineChart
    private lateinit var scoreChart: BarChart
    private lateinit var textLiveInfo: TextView
    private lateinit var btnReset: Button

    private val cn0Entries = mutableListOf<Entry>()
    private val scoreEntries = mutableListOf<BarEntry>()
    private var timeIndex = 0f

    private val gnssUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "GNSS_UPDATE") {
                val avgCn0 = intent.getFloatExtra("avgCn0", 0f)
                val jammingScore = intent.getIntExtra("jammingScore", 0)
                val count = intent.getIntExtra("count", 0)
                val isBaselinePhase = intent.getBooleanExtra("isBaselinePhase", false)

                if (!isBaselinePhase) {
                    updateCharts(avgCn0, jammingScore.toFloat())
                    textLiveInfo.text = getString(R.string.live_info, avgCn0, count, jammingScore)
                } else {
                    val progress = intent.getIntExtra("baselineProgress", 0)
                    textLiveInfo.text = getString(R.string.mapping_env, progress)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gnss_analysis)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.gnss_analysis_title)
        }

        cn0Chart = findViewById(R.id.cn0Chart)
        scoreChart = findViewById(R.id.scoreChart)
        textLiveInfo = findViewById(R.id.textLiveInfo)
        btnReset = findViewById(R.id.btnResetCharts)

        textLiveInfo.text = getString(R.string.waiting_for_data)

        setupCn0Chart()
        setupScoreChart()

        btnReset.setOnClickListener {
            resetCharts()
        }
    }

    private fun setupCn0Chart() {
        cn0Chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setBackgroundColor(Color.TRANSPARENT)
            legend.textColor = Color.GRAY
        }

        val xAxis = cn0Chart.xAxis
        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            textColor = Color.GRAY
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = "${value.toInt()}s"
            }
        }

        val leftAxis = cn0Chart.axisLeft
        leftAxis.apply {
            setAxisMaximum(50f)
            setAxisMinimum(0f)
            setDrawGridLines(true)
            textColor = Color.GRAY
            gridColor = Color.LTGRAY
        }

        cn0Chart.axisRight.isEnabled = false
    }

    private fun setupScoreChart() {
        scoreChart.apply {
            description.isEnabled = false
            setDrawBarShadow(false)
            setDrawValueAboveBar(true)
            setBackgroundColor(Color.TRANSPARENT)
        }
        
        val xAxis = scoreChart.xAxis
        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            setDrawLabels(false)
        }

        val leftAxis = scoreChart.axisLeft
        leftAxis.apply {
            setAxisMinimum(0f)
            setAxisMaximum(10f)
            setLabelCount(6)
            textColor = Color.GRAY
            gridColor = Color.LTGRAY
        }

        scoreChart.axisRight.isEnabled = false
        scoreChart.legend.isEnabled = false
    }

    private fun updateCharts(cn0: Float, score: Float) {
        // C/N0 Chart
        cn0Entries.add(Entry(timeIndex, cn0))
        if (cn0Entries.size > 60) cn0Entries.removeAt(0)

        val lineDataSet = LineDataSet(cn0Entries, getString(R.string.avg_cn0_label))
        lineDataSet.apply {
            color = Color.CYAN
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        cn0Chart.data = LineData(lineDataSet)
        cn0Chart.notifyDataSetChanged()
        cn0Chart.setVisibleXRangeMaximum(60f)
        cn0Chart.moveViewToX(timeIndex)
        cn0Chart.invalidate()

        // Score Chart
        scoreEntries.add(BarEntry(timeIndex, score))
        if (scoreEntries.size > 60) scoreEntries.removeAt(0)

        val barDataSet = BarDataSet(scoreEntries, getString(R.string.jamming_score_label))
        barDataSet.apply {
            color = if (score >= 4) Color.RED else if (score >= 2) Color.YELLOW else Color.GREEN
            setDrawValues(false)
        }

        scoreChart.data = BarData(barDataSet)
        scoreChart.notifyDataSetChanged()
        scoreChart.setVisibleXRangeMaximum(60f)
        scoreChart.moveViewToX(timeIndex)
        scoreChart.invalidate()

        timeIndex += 1f
    }

    private fun resetCharts() {
        cn0Entries.clear()
        scoreEntries.clear()
        timeIndex = 0f
        cn0Chart.clear()
        scoreChart.clear()
        textLiveInfo.text = getString(R.string.waiting_for_data)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("GNSS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gnssUpdateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(gnssUpdateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(gnssUpdateReceiver)
        } catch (e: Exception) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
