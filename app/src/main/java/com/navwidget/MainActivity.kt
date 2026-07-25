package com.navwidget

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    // ── BLE ────────────────────────────────────────────────────────────────
    private val SERVICE_UUID        = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val CHAR_DIRECTION_UUID = UUID.fromString("12345678-1234-1234-1234-123456789ab0")
    private val CHAR_STREET_UUID    = UUID.fromString("12345678-1234-1234-1234-123456789ab1")
    private val CHAR_DISTANCE_UUID  = UUID.fromString("12345678-1234-1234-1234-123456789ab2")
    private val CHAR_ETA_UUID       = UUID.fromString("12345678-1234-1234-1234-123456789ab3")

    private var bluetoothGatt: BluetoothGatt? = null
    private var charDirection: BluetoothGattCharacteristic? = null
    private var charStreet:    BluetoothGattCharacteristic? = null
    private var charDistance:  BluetoothGattCharacteristic? = null
    private var charEta:       BluetoothGattCharacteristic? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    // ── UI ─────────────────────────────────────────────────────────────────
    private lateinit var btnScan:          Button
    private lateinit var btnAccessibility: Button
    private lateinit var tvStatus:         TextView
    private lateinit var tvNavPreview:     TextView

    // ── BLE scan callback ──────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Found: ${device.name} / ${device.address}")
            if (device.name == "NavWidget") {
                stopScan()
                runOnUiThread { tvStatus.text = "Found NavWidget — connecting…" }
                connectToDevice(device)
            }
        }
    }

    // ── GATT callback ──────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected — discovering services")
                    gatt.discoverServices()
                    runOnUiThread { tvStatus.text = "Connected ✓ — discovering services…" }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected")
                    bluetoothGatt = null
                    charDirection = null
                    charStreet    = null
                    charDistance  = null
                    charEta       = null
                    runOnUiThread {
                        tvStatus.text = "Disconnected — tap Scan to reconnect"
                        btnScan.isEnabled = true
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = gatt.getService(SERVICE_UUID) ?: run {
                runOnUiThread { tvStatus.text = "NavWidget service not found on device" }
                return
            }
            charDirection = svc.getCharacteristic(CHAR_DIRECTION_UUID)
            charStreet    = svc.getCharacteristic(CHAR_STREET_UUID)
            charDistance  = svc.getCharacteristic(CHAR_DISTANCE_UUID)
            charEta       = svc.getCharacteristic(CHAR_ETA_UUID)

            runOnUiThread {
                tvStatus.text = "Ready — waiting for Maps navigation"
                btnScan.isEnabled = false
            }
        }
    }

    // ── Activity lifecycle ─────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnScan          = findViewById(R.id.btnScan)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvStatus         = findViewById(R.id.tvStatus)
        tvNavPreview     = findViewById(R.id.tvNavPreview)

        btnScan.setOnClickListener          { requestPermissionsAndScan() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }

        // Keep NavBridgeService alive
        NavBridgeService.onNavUpdate = { dir, street, dist, eta ->
            runOnUiThread {
                tvNavPreview.text = "▶ $dir | $street | $dist | ETA $eta"
            }
            sendNavToBle(dir, street, dist, eta)
        }

        updateAccessibilityButton()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButton()
    }

    // ── Accessibility service check ────────────────────────────────────────
    private fun isAccessibilityEnabled(): Boolean {
        val service = "${packageName}/${NavBridgeService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains(service)
    }

    private fun updateAccessibilityButton() {
        if (isAccessibilityEnabled()) {
            btnAccessibility.text = "Accessibility: ON ✓"
            btnAccessibility.isEnabled = false
        } else {
            btnAccessibility.text = "Enable Accessibility (required)"
            btnAccessibility.isEnabled = true
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ── BLE permissions + scan ─────────────────────────────────────────────
    private fun requestPermissionsAndScan() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), RC_BLE)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(req, perms, grants)
        if (req == RC_BLE && grants.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
        else tvStatus.text = "Bluetooth permissions denied"
    }

    private fun startScan() {
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bm.adapter.bluetoothLeScanner ?: return
        tvStatus.text = "Scanning for NavWidget…"
        scanning = true
        val filter = ScanFilter.Builder().setDeviceName("NavWidget").build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        // Auto-stop after 15 s
        handler.postDelayed({ stopScan() }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ── Send data over BLE ─────────────────────────────────────────────────
    private fun sendNavToBle(direction: String, street: String, distance: String, eta: String) {
        val gatt = bluetoothGatt ?: return
        fun write(char: BluetoothGattCharacteristic?, value: String) {
            char ?: return
            char.value = value.toByteArray(Charsets.UTF_8)
            gatt.writeCharacteristic(char)
        }
        // Write sequentially with small delay to avoid GATT congestion
        write(charDirection, direction)
        handler.postDelayed({ write(charStreet,   street)   }, 80)
        handler.postDelayed({ write(charDistance, distance) }, 160)
        handler.postDelayed({ write(charEta,      eta)      }, 240)
    }

    companion object {
        private const val TAG   = "NavWidget"
        private const val RC_BLE = 1001
    }
}
