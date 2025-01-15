package com.example.bdevicefinder

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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusTextView: TextView
    private lateinit var devicesListView: ListView
    private lateinit var devicesListAdapter: ArrayAdapter<String>
    private lateinit var gradienteProgressBar: ProgressBar
    private lateinit var spinnerDevices: Spinner
    private lateinit var distanceTextView: TextView
    private val devicesList = mutableListOf<String>()
    private val deviceMap = mutableMapOf<String, BluetoothDevice>()
    private var selectedDeviceAddress: String? = null
    private var discoveryInProgress = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        1
                    )
                    return
                }
                Log.d("Bluetooth", "Dispositivo encontrado: ${device?.name ?: "Desconhecido"} com RSSI: $rssi")

                device?.let {
                    val address = device.address
                    val name = device.name ?: "Desconhecido"
                    if (!deviceMap.containsKey(address)) {
                        deviceMap[address] = device
                        devicesList.add(name)
                        devicesListAdapter.notifyDataSetChanged()
                        updateSpinner()
                    }
                    if (address == selectedDeviceAddress) {
                        val distance = calculateDistance(rssi)
                        Log.d("Bluetooth", "RSSI: $rssi, Distância calculada: $distance metros")
                        if (distance > 5) {
                            distanceTextView.text = ">5 metros"
                        } else {
                            distanceTextView.text = "Distância: %.2f metros".format(distance)
                        }
                        updateProgressBar(distance)
                        Log.d("Bluetooth", "Atualizando ProgressBar para o dispositivo selecionado: $name")
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                if (discoveryInProgress) {
                    statusTextView.text = "Atualizado"
                    discoveryInProgress = false
                    handler.postDelayed({ startDiscovery() }, 1000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.tvStatus)
        devicesListView = findViewById(R.id.lvDevices)
        gradienteProgressBar = findViewById(R.id.gradiente)
        spinnerDevices = findViewById(R.id.spinnerDevices)
        distanceTextView = findViewById(R.id.tvDistance)
        devicesListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devicesList)
        devicesListView.adapter = devicesListAdapter

        if (!hasAllPermissions()) {
            Log.d("Bluetooth", "Solicitando permissões")
            requestPermissions()
        } else {
            Log.d("Bluetooth", "Permissões concedidas")
            initializeBluetooth()
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(bluetoothReceiver, filter)

        spinnerDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedDeviceName = parent.getItemAtPosition(position) as String
                selectedDeviceAddress = deviceMap.entries.find {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                            1
                        )
                        return@find false
                    }
                    it.value.name == selectedDeviceName
                }?.key
                Log.d("Bluetooth", "Dispositivo selecionado: $selectedDeviceName com endereço $selectedDeviceAddress")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.cancelDiscovery()
        }
    }

    private fun hasAllPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        Log.d("Bluetooth", "Solicitando permissões")
        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ), 1)
    }

    @SuppressLint("SetTextI18n")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("Bluetooth", "Permissões concedidas")
                initializeBluetooth()
            } else {
                statusTextView.text = "Permissões não concedidas."
            }
        }
    }

    private fun initializeBluetooth() {
        if (!hasAllPermissions()) {
            requestPermissions()
            return
        }
        Log.d("Bluetooth", "Iniciando Bluetooth")
        startDiscovery()
    }

    @SuppressLint("SetTextI18n")
    private fun startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            Log.d("Bluetooth", "Permissões faltando, solicitando novamente")
            requestPermissions()
            return
        }

        Log.d("Bluetooth", "Iniciando descoberta de dispositivos...")
        bluetoothAdapter?.cancelDiscovery()
        devicesList.clear()
        devicesListAdapter.notifyDataSetChanged()
        statusTextView.text = "Atualizando distância"
        discoveryInProgress = true
        bluetoothAdapter?.startDiscovery()
    }

    private fun calculateDistance(rssi: Int): Double {
        val txPower = -59
        if (rssi == 0) {
            return -1.0
        }
        val ratio = rssi * 1.0 / txPower
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            (0.89976 * ratio.pow(7.7095) + 0.111)
        }
    }

    private fun updateProgressBar(distance: Double) {
        val progress = when {
            distance > 5 -> 0
            distance < 1 -> 100
            else -> ((5 - distance) / 5 * 100).toInt()
        }
        gradienteProgressBar.progress = progress
        setColorBasedOnDistance(gradienteProgressBar, distance)
    }

    private fun setColorBasedOnDistance(progressBar: ProgressBar, distance: Double) {
        val color = when {
            distance < 1 -> ContextCompat.getColor(this, android.R.color.holo_red_light)
            distance < 3 -> ContextCompat.getColor(this, android.R.color.holo_orange_light)
            else -> ContextCompat.getColor(this, android.R.color.holo_blue_light)
        }
        progressBar.progressDrawable.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun updateSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            devicesList
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDevices.adapter = adapter
    }
}
