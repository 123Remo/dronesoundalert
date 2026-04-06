package com.example.dronesoundalert

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BluetoothDeviceActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var pairedAdapter: DeviceAdapter
    private lateinit var availableAdapter: DeviceAdapter
    private val availableDevices = mutableListOf<BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && !availableDevices.contains(device)) {
                        availableDevices.add(device)
                        availableAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth_devices)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val recyclerViewPaired = findViewById<RecyclerView>(R.id.recyclerViewPaired)
        val recyclerViewAvailable = findViewById<RecyclerView>(R.id.recyclerViewAvailable)
        val btnScan = findViewById<Button>(R.id.btnScan)

        recyclerViewPaired.layoutManager = LinearLayoutManager(this)
        recyclerViewAvailable.layoutManager = LinearLayoutManager(this)

        pairedAdapter = DeviceAdapter { device -> selectDevice(device) }
        availableAdapter = DeviceAdapter { device -> pairAndSelectDevice(device) }

        recyclerViewPaired.adapter = pairedAdapter
        recyclerViewAvailable.adapter = availableAdapter

        btnScan.setOnClickListener { startDiscovery() }

        loadPairedDevices()

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!checkPermissions()) return
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter.bondedDevices
        pairedAdapter.setDevices(pairedDevices?.toList() ?: emptyList())
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!checkPermissions()) return
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        availableDevices.clear()
        availableAdapter.setDevices(availableDevices)
        bluetoothAdapter.startDiscovery()
        Toast.makeText(this, "Etsitään laitteita...", Toast.LENGTH_SHORT).show()
    }

    private fun selectDevice(device: BluetoothDevice) {
        val prefs = getSharedPreferences("DronePrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("bluetooth_device_address", device.address).apply()
        prefs.edit().putString("bluetooth_device_name", device.name ?: "Unknown").apply()
        Toast.makeText(this, "Valittu: ${device.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    @SuppressLint("MissingPermission")
    private fun pairAndSelectDevice(device: BluetoothDevice) {
        // In a real app, you might want to initiate pairing here.
        // For simplicity, we just select it if it's already paired or try to connect later.
        selectDevice(device)
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    class DeviceAdapter(private val onClick: (BluetoothDevice) -> Unit) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        private var devices = listOf<BluetoothDevice>()

        fun setDevices(newDevices: List<BluetoothDevice>) {
            devices = newDevices
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textName: TextView = view.findViewById(android.R.id.text1)
            val textAddress: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.textName.text = device.name ?: "Unknown"
            holder.textAddress.text = device.address
            holder.itemView.setOnClickListener { onClick(device) }
        }

        override fun getItemCount() = devices.size
    }
}
