package com.snail.easyble.core

import android.Manifest
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import android.util.Log
import com.snail.easyble.callback.ScanListener
import com.snail.easyble.util.BleUtils
import java.util.*



/**
 * This class provides methods to perform scan related operations for Bluetooth LE devices
 * 
 * date: 2018/12/19 20:11
 * author: zengfansheng
 */
internal class Scanner(private val bluetoothAdapter: BluetoothAdapter, private val mainThreadHandler: Handler) {
    private var isScanning = false
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var leScanCallback: BluetoothAdapter.LeScanCallback? = null
    private val scanListeners = ArrayList<ScanListener>()
    private var resultCallback: (BluetoothDevice, Int, ByteArray?) -> Unit = { _, _, _ -> }
    
    //Is the phone's location service turned on? Necessary!
    private fun isLocationEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.isLocationEnabled ?: false
        } else {
            try {
                val locationMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
                locationMode != Settings.Secure.LOCATION_MODE_OFF
            } catch (e: Settings.SettingNotFoundException) {
                e.printStackTrace()
                false
            }
        }
    }
        
    private fun getScanConfig(): ScanConfig {
        return Ble.instance.bleConfig.scanConfig
    }

    @Synchronized
    fun addScanListener(listener: ScanListener?) {
        if (listener != null && !scanListeners.contains(listener)) {
            scanListeners.add(listener)
        }
    }

    @Synchronized
    fun removeScanListener(listener: ScanListener) {
        scanListeners.remove(listener)
    }

    //Check location permission
    private fun noLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
    }

    private fun handleScanCallback(start: Boolean, device: Device?, errorCode: Int, errorMsg: String) {
        mainThreadHandler.post {
            for (listener in scanListeners) {
                when {
                    device != null -> listener.onScanResult(device)
                    start -> listener.onScanStart()
                    errorCode >= 0 -> listener.onScanError(errorCode, errorMsg)
                    else -> listener.onScanStop()
                }
            }
        }
    }

    private fun getSystemConnectedDevices() {
        try {
            val method = bluetoothAdapter.javaClass.getDeclaredMethod("getConnectionState")
            method.isAccessible = true
            val state = method.invoke(bluetoothAdapter) as Int
            if (state == BluetoothAdapter.STATE_CONNECTED) {
                val devices = bluetoothAdapter.bondedDevices
                for (device in devices) {
                    val isConnectedMethod = device.javaClass.getDeclaredMethod("isConnected")
                    isConnectedMethod.isAccessible = true
                    val isConnected = isConnectedMethod.invoke(device) as Boolean
                    if (isConnected) {
                        parseScanResult(device, 0, null, null)                        
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }
    
    /**
     * Start a Bluetooth LE device scan
     */
    fun startScan(context: Context, resultCallback: (BluetoothDevice, Int, ByteArray?) -> Unit) {
        this.resultCallback = resultCallback
        synchronized(this) {
            if (!bluetoothAdapter.isEnabled || isScanning) {
                return
            }
            if (!isLocationEnabled(context)) {
                handleScanCallback(false, null, ScanListener.ERROR_LOCATION_SERVICE_CLOSED, 
                        "Unable to scan for Bluetooth devices, the phone's location service is not turned on.")
                return
            } else if (noLocationPermission(context)) {
                handleScanCallback(false, null, ScanListener.ERROR_LACK_LOCATION_PERMISSION, 
                        "Unable to scan for Bluetooth devices, lack location permission.")
                return
            }
            isScanning = true
        }
        handleScanCallback(true, null, -1, "")
        if (getScanConfig().isAcceptSysConnectedDevice) {
            getSystemConnectedDevices()
        }
        if (getScanConfig().isUseBluetoothLeScanner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (bleScanner == null) {
                bleScanner = bluetoothAdapter.bluetoothLeScanner
            }
            if (scanCallback == null) {
                scanCallback = MyScanCallback()
            }
            if (getScanConfig().scanSettings == null) {
                bleScanner!!.startScan(scanCallback)
            } else {
                bleScanner!!.startScan(null, getScanConfig().scanSettings, scanCallback)
            }
        } else {
            if (leScanCallback == null) {
                leScanCallback = MyLeScanCallback()
            }
            bluetoothAdapter.startLeScan(leScanCallback)
        }
        mainThreadHandler.postDelayed(stopScanRunnable, getScanConfig().scanPeriodMillis.toLong())
    }
    
    /**
     * Stop a Bluetooth LE device scan
     */
    fun stopScan() {
        mainThreadHandler.removeCallbacks(stopScanRunnable)
        if (!bluetoothAdapter.isEnabled) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (bleScanner != null && scanCallback != null) {
                bleScanner!!.stopScan(scanCallback)
            }
        }
        if (leScanCallback != null) {
            bluetoothAdapter.stopLeScan(leScanCallback)
        }
        if (isScanning) {
            isScanning = false
            handleScanCallback(false, null, -1, "")
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private inner class MyScanCallback : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord
            parseScanResult(result.device, result.rssi, scanRecord?.bytes, result)
        }
    }

    private inner class MyLeScanCallback : BluetoothAdapter.LeScanCallback {
        override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray) {
            parseScanResult(device, rssi, scanRecord, null)
        }
    }

    /**
     * Parse raw bytes of scan record
     *
     * @param advData Raw bytes of scan record
     */
    fun parseScanResult(device: BluetoothDevice, rssi: Int, advData: ByteArray?, detailResult: ScanResult?) {
        if (getScanConfig().isOnlyAcceptBleDevice && device.type != BluetoothDevice.DEVICE_TYPE_LE) {
            return
        }
        resultCallback(device, rssi, advData)
        val deviceName = if (TextUtils.isEmpty(device.name)) "" else device.name
        if ((getScanConfig().names.isEmpty() && getScanConfig().addrs.isEmpty() && getScanConfig().uuids.isEmpty() && getScanConfig().rssiLimit <= rssi) ||
                getScanConfig().names.contains(device.name) || getScanConfig().addrs.contains(device.address) || BleUtils.hasUuid(getScanConfig().uuids, advData)){
            //create a Device instance by IDeviceCreator
            val deviceCreater = Ble.instance.bleConfig.deviceCreator
            var dev = deviceCreater?.valueOf(device, advData)
            if (dev != null || deviceCreater == null) {
                if (dev == null) {
                    dev = Device(device)
                }
                dev.name = if (TextUtils.isEmpty(dev.name)) deviceName else dev.name
                dev.rssi = rssi
                dev.bondState = device.bondState
                dev.advData = advData
                if (detailResult != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        dev.isConnectable = detailResult.isConnectable
                    }
                }
                handleScanCallback(false, dev, -1, "")
            }
        }        
        Ble.println(Ble::class.java, Log.DEBUG, "found device! [name: ${if (deviceName.isEmpty()) "N/A" else deviceName}, addr: ${device.address}]")
    }
        
    fun onBluethoothOff() {
        isScanning = false
        handleScanCallback(false, null, -1, "")
    }
    
    fun release() {
        scanListeners.clear()
        stopScan()
    }
}