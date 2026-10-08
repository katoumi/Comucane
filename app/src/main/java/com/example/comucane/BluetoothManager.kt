package com.example.comucane

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*

/**
 * HM-10 / AT-09 BLE Implementation.
 * Optimized for active scanning and stable GATT communication.
 */
@SuppressLint("MissingPermission")
class BluetoothManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var serialCharacteristic: BluetoothGattCharacteristic? = null
    private var currentScanCallback: ScanCallback? = null
    private val dataBuffer = StringBuilder()

    var isConnected = false
        private set

    var isConnecting = false
        private set

    var onDataReceived: ((String) -> Unit)? = null
    var onBatteryReceived: ((Int) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onConnectingStatusChanged: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "BluetoothManager"
        
        // HM-10 Standard UUIDs
        private val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val CHARACTERISTIC_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        @Volatile
        private var INSTANCE: BluetoothManager? = null

        fun getInstance(context: Context): BluetoothManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothManager(context).also { INSTANCE = it }
            }
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) &&
                    (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) &&
                    (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        } else {
            (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        }
    }

    fun startLeScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        if (!hasBluetoothPermission()) return
        
        stopLeScan() // Stop any previous scan

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE Scanner not available")
            return
        }

        currentScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    onDeviceFound(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed with code: $errorCode")
            }
        }

        Log.i(TAG, "BLE: Starting active scan...")
        scanner.startScan(currentScanCallback)
    }

    fun stopLeScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null && currentScanCallback != null) {
            Log.i(TAG, "BLE: Stopping active scan.")
            scanner.stopScan(currentScanCallback)
        }
        currentScanCallback = null
    }

    fun connectToDevice(macAddress: String) {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "Missing permissions for BLE connection.")
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(macAddress) ?: return

        disconnect() // Clean up existing connection

        isConnecting = true
        notifyConnecting(true)

        Log.i(TAG, "BLE: Connecting to ${device.name ?: "HM-10"} [$macAddress]...")
        
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE: Connected to GATT server. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "BLE: Disconnected from GATT server.")
                handleDisconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)

                if (characteristic != null) {
                    serialCharacteristic = characteristic
                    Log.i(TAG, "BLE: HM-10 Serial Service found. Enabling notifications...")
                    enableNotifications(gatt, characteristic)
                } else {
                    Log.e(TAG, "BLE: Serial characteristic not found!")
                    disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            processIncomingData(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            processIncomingData(value)
        }

        private fun processIncomingData(value: ByteArray?) {
            if (value == null || value.isEmpty()) return
            
            val data = String(value, Charsets.UTF_8)
            Log.i(TAG, "BLE Data Received: $data")
            
            synchronized(dataBuffer) {
                dataBuffer.append(data)
                
                var newlineIndex = dataBuffer.indexOf("\n")
                while (newlineIndex != -1) {
                    val fullLine = dataBuffer.substring(0, newlineIndex).trim()
                    dataBuffer.delete(0, newlineIndex + 1)
                    
                    if (fullLine.isNotEmpty()) {
                        Log.i(TAG, "BLE Full Line Parsed: $fullLine")
                        
                        // Hardware Guardian: Check for battery data
                        if (fullLine.uppercase().startsWith("BAT:") || fullLine.uppercase().startsWith("BATTERY:")) {
                            val batVal = fullLine.split(":")[1].trim().toIntOrNull()
                            if (batVal != null) {
                                Log.i(TAG, "Cane Battery Level: $batVal%")
                                mainHandler.post { onBatteryReceived?.invoke(batVal) }
                            }
                        } else {
                            mainHandler.post { onDataReceived?.invoke(fullLine) }
                        }
                    }
                    newlineIndex = dataBuffer.indexOf("\n")
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "BLE: Notifications enabled. Connection fully ready.")
                isConnecting = false
                isConnected = true
                notifyConnecting(false)
                mainHandler.post { onConnectionStatusChanged?.invoke(true) }
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
        if (descriptor != null) {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    fun disconnect() {
        stopLeScan()
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        handleDisconnect()
    }

    private fun handleDisconnect() {
        bluetoothGatt = null
        serialCharacteristic = null
        isConnected = false
        isConnecting = false
        notifyConnecting(false)
        mainHandler.post { onConnectionStatusChanged?.invoke(false) }
    }

    private fun notifyConnecting(connecting: Boolean) {
        mainHandler.post { onConnectingStatusChanged?.invoke(connecting) }
    }
}
