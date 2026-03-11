package com.example.transtopcm

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * BleManager - 蓝牙管理单例
 * 负责蓝牙扫描、连接、断开，并通知所有监听者状态变化
 */
@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "BleManager"

    private val SERVICE_UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val TARGET_NAME = "S3_BLE_TRANS"

    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    var transChar: BluetoothGattCharacteristic? = null
        private set

    // 连接状态
    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, READY }
    var connectionState = ConnectionState.DISCONNECTED
        private set

    // 状态监听器列表
    private val listeners = mutableListOf<BleStateListener>()

    // 连接历史记录
    data class ConnectionLog(val time: String, val event: String)
    val connectionHistory = mutableListOf<ConnectionLog>()

    // PCM 数据回调
    var onPcmDataReceived: ((ByteArray) -> Unit)? = null

    // 状态监听器接口
    interface BleStateListener {
        fun onStateChanged(state: ConnectionState, message: String)
        fun onPcmReceived(data: ByteArray) {}
    }

    fun addListener(listener: BleStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: BleStateListener) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged(state: ConnectionState, message: String) {
        connectionState = state
        listeners.forEach { it.onStateChanged(state, message) }
    }

    private fun notifyPcmReceived(data: ByteArray) {
        listeners.forEach { it.onPcmReceived(data) }
    }

    private fun addLog(event: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        connectionHistory.add(0, ConnectionLog(time, event))
        if (connectionHistory.size > 50) {
            connectionHistory.removeAt(connectionHistory.size - 1)
        }
    }

    fun startScan(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            notifyStateChanged(ConnectionState.DISCONNECTED, "蓝牙未开启")
            return
        }
        bleScanner = adapter.bluetoothLeScanner
        notifyStateChanged(ConnectionState.SCANNING, "正在扫描...")
        addLog("开始扫描设备")

        val filter = ScanFilter.Builder()
            .setDeviceName(TARGET_NAME)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != TARGET_NAME) return
            notifyStateChanged(ConnectionState.CONNECTING, "正在连接...")
            addLog("发现设备: ${device.name}")
            bleScanner?.stopScan(this)
            // 需要 context，从 result 获取
            gatt = device.connectGatt(null, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyStateChanged(ConnectionState.DISCONNECTED, "连接失败 ($status)")
                addLog("连接失败: $status")
                gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                notifyStateChanged(ConnectionState.CONNECTED, "已连接，请求MTU")
                addLog("已连接")
                if (!gatt.requestMtu(247)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                notifyStateChanged(ConnectionState.DISCONNECTED, "已断开")
                addLog("已断开连接")
                transChar = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val msg = if (status == BluetoothGatt.GATT_SUCCESS) "MTU=$mtu" else "MTU请求失败"
            notifyStateChanged(ConnectionState.CONNECTED, msg)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyStateChanged(ConnectionState.DISCONNECTED, "服务发现失败")
                return
            }
            val svc = gatt.getService(SERVICE_UUID)
            transChar = svc?.getCharacteristic(CHAR_UUID)

            if (transChar == null) {
                notifyStateChanged(ConnectionState.DISCONNECTED, "特征值未找到")
                return
            }

            gatt.setCharacteristicNotification(transChar, true)
            val desc = transChar!!.getDescriptor(CCCD_UUID)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }

            notifyStateChanged(ConnectionState.READY, "蓝牙就绪")
            addLog("蓝牙就绪")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            val msg = if (status == BluetoothGatt.GATT_SUCCESS) "文本已发送" else "发送失败"
            notifyStateChanged(ConnectionState.READY, msg)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != CHAR_UUID) return
            val data = characteristic.value
            notifyPcmReceived(data)
            onPcmDataReceived?.invoke(data)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        transChar = null
        notifyStateChanged(ConnectionState.DISCONNECTED, "已断开")
        addLog("主动断开连接")
    }

    fun isConnected(): Boolean {
        return connectionState == ConnectionState.READY || connectionState == ConnectionState.CONNECTED
    }

    fun writeText(text: String): Boolean {
        val c = transChar ?: return false
        val payload = if (text.isBlank()) "[empty]" else text
        val bytes = payload.toByteArray()
        return if (Build.VERSION.SDK_INT >= 33) {
            val ret = gatt?.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            ret == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            gatt?.writeCharacteristic(c) ?: false
        }
    }

    fun getGatt(): BluetoothGatt? = gatt
}
