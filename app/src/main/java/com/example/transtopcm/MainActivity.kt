package com.example.transtopcm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class MainActivity : AppCompatActivity() {

    private val TAG = "TransApp"

    private lateinit var btnConnect: Button
    private lateinit var btnStart: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtResult: TextView

    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var transChar: BluetoothGattCharacteristic? = null

    private var expectedBytes = 16000 * 5 * 2
    private var pcmBuffer = ByteArray(0)
    private var receiving = false
    private var waitPcmAfterStart = false

    // Xunfei IAT credentials
    private val APP_ID = "e6dd1814"
    private val API_KEY = "19920b233b1d0f319a6152770dca01f9"
    private val API_SECRET = "NjE4M2Q3MjcwNDg0MmExNjZiZDNjMDll"

    // ESP32 side: SERVICE 0x00FF, CHAR 0xFF01, device name "S3_BLE_TRANS"
    private val SERVICE_UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val TARGET_NAME = "S3_BLE_TRANS"
    private val PCM_EXPECTED_BYTES = 16000 * 5 * 2 // 5s * 16kHz * int16 mono

    private val IAT_HOST = "iat-api.xfyun.cn"
    private val IAT_PATH = "/v2/iat"
    private val httpClient = OkHttpClient.Builder().build()
    private val asrInProgress = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        btnStart = findViewById(R.id.btnStart)
        txtStatus = findViewById(R.id.txtStatus)
        txtResult = findViewById(R.id.txtResult)

        btnConnect.setOnClickListener { startScan() }
        btnStart.setOnClickListener { startTrans() }

        requestPermissions()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBlePermission()) {
            txtStatus.text = "Status: BLE permission denied"
            requestPermissions()
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            txtStatus.text = "Status: Bluetooth off"
            return
        }
        bleScanner = adapter.bluetoothLeScanner
        txtStatus.text = "Status: scanning..."

        val filter = ScanFilter.Builder()
            .setDeviceName(TARGET_NAME)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != TARGET_NAME) return
            txtStatus.text = "Status: connecting..."
            bleScanner?.stopScan(this)
            gatt = device.connectGatt(this@MainActivity, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { txtStatus.text = "Status: connect failed ($status)" }
                gatt.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { txtStatus.text = "Status: connected, requesting MTU" }
                if (!gatt.requestMtu(247)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { txtStatus.text = "Status: disconnected" }
                transChar = null
                waitPcmAfterStart = false
                receiving = false
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnUiThread {
                txtStatus.text = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Status: MTU=$mtu, discovering services"
                } else {
                    "Status: MTU request failed, discovering services"
                }
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { txtStatus.text = "Status: service discovery failed ($status)" }
                return
            }
            val svc = gatt.getService(SERVICE_UUID)
            transChar = svc?.getCharacteristic(CHAR_UUID)

            if (transChar == null) {
                runOnUiThread { txtStatus.text = "Status: char 0xFF01 not found" }
                return
            }

            gatt.setCharacteristicNotification(transChar, true)
            val desc = transChar!!.getDescriptor(CCCD_UUID)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }

            runOnUiThread { txtStatus.text = "Status: BLE ready" }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != CHAR_UUID) return
            runOnUiThread {
                txtStatus.text = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Status: text sent"
                } else {
                    "Status: text write failed ($status)"
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != CHAR_UUID) return
            val data = characteristic.value
            if (!waitPcmAfterStart) {
                return
            }

            if (!receiving) {
                receiving = true
                runOnUiThread { txtStatus.text = "Status: receiving PCM..." }
            }

            pcmBuffer += data
            if (pcmBuffer.size >= expectedBytes) {
                receiving = false
                waitPcmAfterStart = false
                val pcm = pcmBuffer.copyOf(expectedBytes)
                runOnUiThread { txtStatus.text = "Status: ASR..." }
                startASR(pcm)
            }
        }
    }

    private fun startTrans() {
        if (gatt == null || transChar == null) {
            txtStatus.text = "Status: BLE not ready"
            return
        }
        expectedBytes = PCM_EXPECTED_BYTES
        pcmBuffer = ByteArray(0)
        receiving = false
        waitPcmAfterStart = true
        txtStatus.text = "Status: Press Trans on ESP32 now"
    }

    private fun startASR(pcm: ByteArray) {
        if (!asrInProgress.compareAndSet(false, true)) {
            runOnUiThread { txtStatus.text = "Status: ASR busy" }
            return
        }
        val wsUrl = buildAuthUrl()
        if (wsUrl.isEmpty()) {
            asrInProgress.set(false)
            runOnUiThread { txtStatus.text = "Status: ASR auth failed" }
            return
        }

        val request = Request.Builder().url(wsUrl).build()
        val asrText = StringBuilder()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { txtStatus.text = "Status: ASR connected" }
                sendPcmFrames(webSocket, pcm)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val code = json.optInt("code", -1)
                    if (code != 0) {
                        val msg = json.optString("message", "error")
                        runOnUiThread { txtStatus.text = "Status: ASR error $code $msg" }
                        webSocket.close(1000, "error")
                        return
                    }

                    val data = json.optJSONObject("data") ?: return
                    val status = data.optInt("status", -1)
                    val result = data.optJSONObject("result")
                    if (result != null) {
                        val ws = result.optJSONArray("ws")
                        if (ws != null) {
                            for (i in 0 until ws.length()) {
                                val wsObj = ws.optJSONObject(i)
                                val cw = wsObj?.optJSONArray("cw") ?: continue
                                val first = cw.optJSONObject(0)
                                val w = first?.optString("w", "") ?: ""
                                asrText.append(w)
                            }
                            val current = asrText.toString()
                            runOnUiThread { txtResult.text = "Text: $current" }
                        }
                    }

                    if (status == 2 || (result != null && result.optBoolean("ls", false))) {
                        val finalText = asrText.toString()
                        sendTextToEsp(finalText)
                        runOnUiThread { txtStatus.text = "Status: ASR done" }
                        webSocket.close(1000, "done")
                    }
                } catch (e: Exception) {
                    runOnUiThread { txtStatus.text = "Status: ASR parse error" }
                    webSocket.close(1000, "parse error")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ASR ws failed", t)
                runOnUiThread { txtStatus.text = "Status: ASR failed" }
                asrInProgress.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                asrInProgress.set(false)
            }
        }

        httpClient.newWebSocket(request, listener)
    }

    private fun buildAuthUrl(): String {
        if (APP_ID.isBlank() || API_KEY.isBlank() || API_SECRET.isBlank()) {
            return ""
        }
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")
        val date = dateFormat.format(Date())

        val signatureOrigin = "host: $IAT_HOST\ndate: $date\nGET $IAT_PATH HTTP/1.1"
        val signatureSha = hmacSha256(signatureOrigin, API_SECRET)
        val signature = Base64.encodeToString(signatureSha, Base64.NO_WRAP)
        val authorizationOrigin =
            "api_key=\"$API_KEY\",algorithm=\"hmac-sha256\",headers=\"host date request-line\",signature=\"$signature\""
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(), Base64.NO_WRAP)

        val auth = URLEncoder.encode(authorization, "UTF-8")
        val dateEnc = URLEncoder.encode(date, "UTF-8")
        return "wss://$IAT_HOST$IAT_PATH?authorization=$auth&date=$dateEnc&host=$IAT_HOST"
    }

    private fun hmacSha256(data: String, key: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray())
    }

    private fun sendPcmFrames(webSocket: WebSocket, pcm: ByteArray) {
        Thread {
            try {
                val frameSize = 1280
                var offset = 0
                var status = 0
                while (offset < pcm.size) {
                    val end = (offset + frameSize).coerceAtMost(pcm.size)
                    val slice = pcm.copyOfRange(offset, end)
                    val audio = Base64.encodeToString(slice, Base64.NO_WRAP)
                    val data = JSONObject()
                    data.put("status", status)
                    data.put("format", "audio/L16;rate=16000")
                    data.put("encoding", "raw")
                    data.put("audio", audio)

                    val frame = JSONObject()
                    if (status == 0) {
                        val common = JSONObject()
                        common.put("app_id", APP_ID)
                        val business = JSONObject()
                        business.put("language", "zh_cn")
                        business.put("domain", "iat")
                        business.put("accent", "mandarin")
                        frame.put("common", common)
                        frame.put("business", business)
                    }
                    frame.put("data", data)
                    webSocket.send(frame.toString())

                    status = 1
                    offset = end
                    Thread.sleep(40)
                }

                val endFrame = JSONObject()
                val endData = JSONObject()
                endData.put("status", 2)
                endFrame.put("data", endData)
                webSocket.send(endFrame.toString())
            } catch (e: Exception) {
                Log.e(TAG, "sendPcmFrames failed", e)
                webSocket.close(1000, "send error")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun sendTextToEsp(text: String) {
        val c = transChar ?: return
        val payload = if (text.isBlank()) "[empty]" else text
        val bytes = payload.toByteArray()
        if (Build.VERSION.SDK_INT >= 33) {
            val ret = gatt?.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (ret != BluetoothStatusCodes.SUCCESS) {
                runOnUiThread { txtStatus.text = "Status: write request rejected ($ret)" }
            }
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            val ok = gatt?.writeCharacteristic(c) ?: false
            if (!ok) {
                runOnUiThread { txtStatus.text = "Status: write request rejected" }
            }
        }
    }
}
