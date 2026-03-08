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
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.cardview.widget.CardView
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

/**
 * MainActivity - 主活动类
 * 
 * 这是应用的唯一界面，负责以下功能：
 * 1. 通过蓝牙连接 ESP32 设备
 * 2. 从 ESP32 接收 PCM 音频数据
 * 3. 使用讯飞语音识别 API 将音频转换为文字
 * 4. 将识别的文字发送回 ESP32 设备
 * 
 * 继承自 AppCompatActivity，这是 Android 的基础活动类
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "TransApp"  // 日志标签，用于在Logcat中过滤和识别本应用的日志信息

    private lateinit var btnConnect: Button  // 蓝牙连接按钮，点击后开始扫描并连接ESP32设备
    private lateinit var btnStart: Button  // 开始传输按钮，点击后准备接收ESP32发送的音频数据
    private lateinit var txtStatus: TextView  // 状态显示文本框，显示当前应用的工作状态（如：扫描中、已连接、正在接收等）
    private lateinit var txtResult: TextView  // 结果显示文本框，显示语音识别后的文字结果
    private lateinit var cardResult: CardView  // 结果卡片视图
    private lateinit var statusIndicator: View  // 状态指示器

    private lateinit var fadeInUp: Animation  // 淡入上移动画
    private lateinit var pulseAnimation: Animation  // 脉冲动画

    private var bleScanner: BluetoothLeScanner? = null  // 蓝牙扫描器，用于扫描附近的BLE设备
    private var gatt: BluetoothGatt? = null  // 蓝牙GATT客户端，用于与BLE设备进行通信
    private var transChar: BluetoothGattCharacteristic? = null  // 蓝牙特征值，用于与ESP32设备进行数据传输的通道

    private var expectedBytes = 16000 * 5 * 2  // 预期接收的字节数：16000Hz采样率 × 5秒时长 × 2字节/采样点（16位音频）= 160000字节
    private var pcmBuffer = ByteArray(0)  // PCM音频数据缓冲区，用于存储从ESP32接收到的音频数据
    private var receiving = false  // 接收状态标志，true表示正在接收PCM数据
    private var waitPcmAfterStart = false  // 等待PCM数据标志，true表示已准备好接收ESP32发送的音频数据

    private val APP_ID = "e6dd1814"  // 讯飞语音识别API的应用ID
    private val API_KEY = "19920b233b1d0f319a6152770dca01f9"  // 讯飞语音识别API的密钥
    private val API_SECRET = "NjE4M2Q3MjcwNDg0MmExNjZiZDNjMDll"  // 讯飞语音识别API的密钥

    private val SERVICE_UUID = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb")  // ESP32蓝牙服务的UUID，用于识别设备提供的服务
    private val CHAR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")  // ESP32特征值的UUID，用于数据传输的通道
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")  // 客户端特征配置描述符UUID，用于启用通知功能
    private val TARGET_NAME = "S3_BLE_TRANS"  // 目标设备名称，扫描时会寻找此名称的ESP32设备
    private val PCM_EXPECTED_BYTES = 16000 * 5 * 2  // PCM音频数据的预期字节数，与expectedBytes相同

    private val IAT_HOST = "iat-api.xfyun.cn"  // 讯飞语音识别API的服务器地址
    private val IAT_PATH = "/v2/iat"  // 讯飞语音识别API的请求路径
    private val httpClient = OkHttpClient.Builder().build()  // HTTP客户端，用于建立WebSocket连接与讯飞API通信
    private val asrInProgress = AtomicBoolean(false)  // ASR（自动语音识别）进行状态标志，使用原子变量确保线程安全
    
    // Activity创建时调用的生命周期函数
    override fun onCreate(savedInstanceState: Bundle?) {  
        super.onCreate(savedInstanceState)  // 调用父类的onCreate方法
        setContentView(R.layout.activity_main)  // 加载布局文件，设置界面

        btnConnect = findViewById(R.id.btnConnect)  // 从布局中获取连接按钮
        btnStart = findViewById(R.id.btnStart)  // 从布局中获取开始按钮
        txtStatus = findViewById(R.id.txtStatus)  // 从布局中获取状态文本框
        txtResult = findViewById(R.id.txtResult)  // 从布局中获取结果文本框
        cardResult = findViewById(R.id.cardResult)  // 从布局中获取结果卡片
        statusIndicator = findViewById(R.id.statusIndicator)  // 从布局中获取状态指示器

        fadeInUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up)  // 加载淡入上移动画
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse)  // 加载脉冲动画

        setupAnimations()  // 设置动画效果
        btnConnect.setOnClickListener { startScan() }  // 为连接按钮设置点击事件，点击时开始扫描蓝牙设备
        btnStart.setOnClickListener { startTrans() }  // 为开始按钮设置点击事件，点击时准备接收音频数据

        requestPermissions()  // 请求应用所需的权限
    }

    private fun setupAnimations() {  // 设置卡片动画效果
        cardResult.startAnimation(fadeInUp)  // 结果卡片淡入上移
    }

    private fun updateStatusIndicator(status: String) {  // 更新状态指示器的颜色和动画
        statusIndicator.clearAnimation()  // 清除之前的动画
        
        when {
            status.contains("scanning") -> {  // 扫描状态
                statusIndicator.setBackgroundColor(getColor(R.color.warning))
                statusIndicator.startAnimation(pulseAnimation)
            }
            status.contains("connected") || status.contains("BLE ready") -> {  // 已连接状态
                statusIndicator.setBackgroundColor(getColor(R.color.success))
                statusIndicator.clearAnimation()
            }
            status.contains("receiving") || status.contains("ASR") -> {  // 接收或识别状态
                statusIndicator.setBackgroundColor(getColor(R.color.accent))
                statusIndicator.startAnimation(pulseAnimation)
            }
            status.contains("failed") || status.contains("error") -> {  // 失败或错误状态
                statusIndicator.setBackgroundColor(getColor(R.color.error))
                statusIndicator.clearAnimation()
            }
            else -> {  // 其他状态
                statusIndicator.setBackgroundColor(getColor(R.color.text_secondary))
                statusIndicator.clearAnimation()
            }
        }
    }

    private fun updateStatus(status: String) {  // 同时更新状态文本和指示器
        txtStatus.text = status  // 更新状态文本
        updateStatusIndicator(status)  // 更新状态指示器
    }

    // 请求应用运行所需的权限
    private fun requestPermissions() {  
        val perms = mutableListOf(  // 创建权限列表
            Manifest.permission.RECORD_AUDIO,  // 录音权限，用于音频处理
            Manifest.permission.ACCESS_FINE_LOCATION  // 精确位置权限，用于蓝牙扫描
        )
        if (Build.VERSION.SDK_INT >= 31) {  // 如果Android版本是12或更高
            perms.add(Manifest.permission.BLUETOOTH_SCAN)  // 添加蓝牙扫描权限
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)  // 添加蓝牙连接权限
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)  // 请求所有权限，请求码为1
    }

    // 检查是否拥有蓝牙相关权限
    private fun hasBlePermission(): Boolean {  
        return if (Build.VERSION.SDK_INT >= 31) {  // 如果Android版本是12或更高
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED  // 检查蓝牙扫描和连接权限
        } else {  // 如果Android版本低于12
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED  // 检查位置权限
        }
    }
    
    // 开始扫描蓝牙设备
    @SuppressLint("MissingPermission")
    private fun startScan() {  
        if (!hasBlePermission()) {  // 如果没有蓝牙权限
            updateStatus("Status: BLE permission denied")  // 显示权限被拒绝的状态
            requestPermissions()  // 重新请求权限
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter  // 获取蓝牙适配器
        if (adapter == null || !adapter.isEnabled) {  // 如果蓝牙不可用或未开启
            updateStatus("Status: Bluetooth off")  // 显示蓝牙关闭的状态
            return
        }
        bleScanner = adapter.bluetoothLeScanner  // 获取BLE扫描器
        updateStatus("Status: scanning...")  // 显示正在扫描的状态

        val filter = ScanFilter.Builder()  // 创建扫描过滤器
            .setDeviceName(TARGET_NAME)  // 只扫描指定名称的设备
            .build()
        val settings = ScanSettings.Builder()  // 创建扫描设置
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // 使用低延迟模式，扫描更快但耗电更多
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)  // 开始扫描，使用过滤器和回调
    }
    
    // 蓝牙扫描回调，处理扫描结果
    private val scanCallback = object : ScanCallback() {  
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {  // 当扫描到设备时调用
            val device = result.device  // 获取扫描到的设备
            if (device.name != TARGET_NAME) return  // 如果设备名称不匹配，忽略
            updateStatus("Status: connecting...")  // 显示正在连接的状态
            bleScanner?.stopScan(this)  // 停止扫描
            gatt = device.connectGatt(this@MainActivity, false, gattCallback)  // 连接到设备，false表示不自动重连
        }
    }

    
    // 蓝牙GATT回调，处理蓝牙连接的各种事件
    private val gattCallback = object : BluetoothGattCallback() {  
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {  // 当连接状态改变时调用
            if (status != BluetoothGatt.GATT_SUCCESS) {  // 如果连接失败
                runOnUiThread { updateStatus("Status: connect failed ($status)") }  // 显示连接失败状态
                gatt.close()  // 关闭连接
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {  // 如果已连接
                runOnUiThread { updateStatus("Status: connected, requesting MTU") }  // 显示已连接并请求MTU
                if (!gatt.requestMtu(247)) {  // 请求更大的MTU（最大传输单元），247是推荐值
                    gatt.discoverServices()  // 如果MTU请求失败，直接发现服务
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {  // 如果已断开
                runOnUiThread { updateStatus("Status: disconnected") }  // 显示断开状态
                transChar = null  // 清空特征值
                waitPcmAfterStart = false  // 重置等待标志
                receiving = false  // 重置接收标志
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {  // 当MTU改变时调用
            runOnUiThread {
                updateStatus(if (status == BluetoothGatt.GATT_SUCCESS) {  // 如果MTU设置成功
                    "Status: MTU=$mtu, discovering services"  // 显示MTU值并开始发现服务
                } else {  // 如果MTU设置失败
                    "Status: MTU request failed, discovering services"  // 显示失败信息但仍继续发现服务
                })
            }
            gatt.discoverServices()  // 开始发现服务
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {  // 当服务发现完成时调用
            if (status != BluetoothGatt.GATT_SUCCESS) {  // 如果服务发现失败
                runOnUiThread { updateStatus("Status: service discovery failed ($status)") }  // 显示失败状态
                return
            }
            val svc = gatt.getService(SERVICE_UUID)  // 获取指定UUID的服务
            transChar = svc?.getCharacteristic(CHAR_UUID)  // 获取指定UUID的特征值

            if (transChar == null) {  // 如果特征值不存在
                runOnUiThread { updateStatus("Status: char 0xFF01 not found") }  // 显示特征值未找到
                return
            }

            gatt.setCharacteristicNotification(transChar, true)  // 启用特征值通知
            val desc = transChar!!.getDescriptor(CCCD_UUID)  // 获取CCCD描述符
            if (desc != null) {  // 如果描述符存在
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE  // 设置为启用通知
                gatt.writeDescriptor(desc)  // 写入描述符
            }

            runOnUiThread { updateStatus("Status: BLE ready") }  // 显示蓝牙已就绪状态
        }

        override fun onCharacteristicWrite(  // 当特征值写入完成时调用
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != CHAR_UUID) return  // 如果不是我们关心的特征值，忽略
            runOnUiThread {
                updateStatus(if (status == BluetoothGatt.GATT_SUCCESS) {  // 如果写入成功
                    "Status: text sent"  // 显示文本已发送
                } else {  // 如果写入失败
                    "Status: text write failed ($status)"  // 显示写入失败状态
                })
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {  // 当特征值改变时调用（接收到数据）
            if (characteristic.uuid != CHAR_UUID) return  // 如果不是我们关心的特征值，忽略
            val data = characteristic.value  // 获取接收到的数据
            if (!waitPcmAfterStart) {  // 如果不在等待PCM数据状态
                return
            }

            if (!receiving) {  // 如果之前未在接收状态
                receiving = true  // 设置为正在接收
                runOnUiThread { updateStatus("Status: receiving PCM...") }  // 显示正在接收PCM数据
            }

            pcmBuffer += data  // 将接收到的数据追加到缓冲区
            if (pcmBuffer.size >= expectedBytes) {  // 如果已接收到足够的数据
                receiving = false  // 停止接收状态
                waitPcmAfterStart = false  // 停止等待状态
                val pcm = pcmBuffer.copyOf(expectedBytes)  // 截取预期的字节数
                runOnUiThread { updateStatus("Status: ASR...") }  // 显示正在进行语音识别
                startASR(pcm)  // 开始语音识别
            }
        }
    }
    
    // 开始传输功能，准备接收ESP32发送的音频数据
    private fun startTrans() {  
        if (gatt == null || transChar == null) {  // 如果蓝牙未连接或特征值未找到
            updateStatus("Status: BLE not ready")  // 显示蓝牙未就绪状态
            return
        }
        expectedBytes = PCM_EXPECTED_BYTES  // 设置预期的字节数
        pcmBuffer = ByteArray(0)  // 清空PCM缓冲区
        receiving = false  // 重置接收状态
        waitPcmAfterStart = true  // 设置等待PCM数据标志
        updateStatus("Status: Press Trans on ESP32 now")  // 提示用户在ESP32上按下传输按钮
    }
    
    // 开始自动语音识别（ASR），将PCM音频数据发送给讯飞API进行识别
    private fun startASR(pcm: ByteArray) {  
        if (!asrInProgress.compareAndSet(false, true)) {  // 如果ASR正在进行中，使用原子操作检查并设置状态
            runOnUiThread { updateStatus("Status: ASR busy") }  // 显示ASR忙碌状态
            return
        }
        val wsUrl = buildAuthUrl()  // 构建带认证信息的WebSocket URL
        if (wsUrl.isEmpty()) {  // 如果URL构建失败
            asrInProgress.set(false)  // 重置ASR状态
            runOnUiThread { updateStatus("Status: ASR auth failed") }  // 显示认证失败状态
            return
        }

        val request = Request.Builder().url(wsUrl).build()  // 创建WebSocket请求
        val asrText = StringBuilder()  // 用于存储识别结果的字符串构建器

        val listener = object : WebSocketListener() {  // WebSocket监听器，处理与讯飞API的通信
            override fun onOpen(webSocket: WebSocket, response: Response) {  // 当WebSocket连接建立时调用
                runOnUiThread { updateStatus("Status: ASR connected") }  // 显示ASR已连接状态
                sendPcmFrames(webSocket, pcm)  // 发送PCM音频数据帧
            }

            override fun onMessage(webSocket: WebSocket, text: String) {  // 当收到服务器消息时调用
                try {
                    val json = JSONObject(text)  // 解析JSON响应
                    val code = json.optInt("code", -1)  // 获取响应码
                    if (code != 0) {  // 如果响应码不为0，表示错误
                        val msg = json.optString("message", "error")  // 获取错误消息
                        runOnUiThread { updateStatus("Status: ASR error $code $msg") }  // 显示错误信息
                        webSocket.close(1000, "error")  // 关闭WebSocket连接
                        return
                    }

                    val data = json.optJSONObject("data") ?: return  // 获取数据对象
                    val status = data.optInt("status", -1)  // 获取状态码
                    val result = data.optJSONObject("result")  // 获取识别结果
                    if (result != null) {  // 如果有识别结果
                        val ws = result.optJSONArray("ws")  // 获取词语数组
                        if (ws != null) {  // 如果词语数组存在
                            for (i in 0 until ws.length()) {  // 遍历每个词语
                                val wsObj = ws.optJSONObject(i)  // 获取词语对象
                                val cw = wsObj?.optJSONArray("cw") ?: continue  // 获取候选词数组
                                val first = cw.optJSONObject(0)  // 获取第一个候选词
                                val w = first?.optString("w", "") ?: ""  // 获取词语文本
                                asrText.append(w)  // 追加到识别结果
                            }
                            val current = asrText.toString()  // 获取当前识别文本
                            runOnUiThread { txtResult.text = "Text: $current" }  // 显示识别结果
                        }
                    }

                    if (status == 2 || (result != null && result.optBoolean("ls", false))) {  // 如果识别完成
                        val finalText = asrText.toString()  // 获取最终识别文本
                        sendTextToEsp(finalText)  // 将识别结果发送给ESP32
                        runOnUiThread { updateStatus("Status: ASR done") }  // 显示ASR完成状态
                        webSocket.close(1000, "done")  // 关闭WebSocket连接
                    }
                } catch (e: Exception) {  // 捕获解析异常
                    runOnUiThread { updateStatus("Status: ASR parse error") }  // 显示解析错误
                    webSocket.close(1000, "parse error")  // 关闭WebSocket连接
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {  // 当WebSocket连接失败时调用
                Log.e(TAG, "ASR ws failed", t)  // 记录错误日志
                runOnUiThread { updateStatus("Status: ASR failed") }  // 显示ASR失败状态
                asrInProgress.set(false)  // 重置ASR状态
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {  // 当WebSocket连接关闭时调用
                asrInProgress.set(false)  // 重置ASR状态
            }
        }

        httpClient.newWebSocket(request, listener)  // 创建WebSocket连接
    }

    
    // 构建带认证信息的WebSocket URL，用于连接讯飞语音识别API
    private fun buildAuthUrl(): String {  
        if (APP_ID.isBlank() || API_KEY.isBlank() || API_SECRET.isBlank()) {  // 如果API凭证为空
            return ""  // 返回空字符串
        }
        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)  // 创建日期格式化器
        dateFormat.timeZone = TimeZone.getTimeZone("GMT")  // 设置时区为GMT
        val date = dateFormat.format(Date())  // 格式化当前时间

        val signatureOrigin = "host: $IAT_HOST\ndate: $date\nGET $IAT_PATH HTTP/1.1"  // 构建签名原始字符串
        val signatureSha = hmacSha256(signatureOrigin, API_SECRET)  // 使用HMAC-SHA256计算签名
        val signature = Base64.encodeToString(signatureSha, Base64.NO_WRAP)  // 将签名进行Base64编码
        val authorizationOrigin =
            "api_key=\"$API_KEY\",algorithm=\"hmac-sha256\",headers=\"host date request-line\",signature=\"$signature\""  // 构建授权原始字符串
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(), Base64.NO_WRAP)  // 将授权信息进行Base64编码

        val auth = URLEncoder.encode(authorization, "UTF-8")  // URL编码授权信息
        val dateEnc = URLEncoder.encode(date, "UTF-8")  // URL编码日期
        return "wss://$IAT_HOST$IAT_PATH?authorization=$auth&date=$dateEnc&host=$IAT_HOST"  // 返回完整的WebSocket URL
    }


    // 使用HMAC-SHA256算法计算签名
    private fun hmacSha256(data: String, key: String): ByteArray {  
        val mac = Mac.getInstance("HmacSHA256")  // 获取HMAC-SHA256算法实例
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacSHA256")  // 创建密钥规范
        mac.init(secretKey)  // 初始化MAC实例
        return mac.doFinal(data.toByteArray())  // 计算并返回签名
    }

    
    // 将PCM音频数据分帧发送给讯飞API
    private fun sendPcmFrames(webSocket: WebSocket, pcm: ByteArray) {  
        Thread {  // 在新线程中发送数据，避免阻塞主线程
            try {
                val frameSize = 1280  // 每帧的大小：1280字节 = 80ms的音频（16000Hz × 0.08s × 2字节）
                var offset = 0  // 当前偏移量
                var status = 0  // 帧状态：0=首帧，1=中间帧，2=尾帧
                while (offset < pcm.size) {  // 循环发送所有音频数据
                    val end = (offset + frameSize).coerceAtMost(pcm.size)  // 计算当前帧的结束位置
                    val slice = pcm.copyOfRange(offset, end)  // 截取当前帧的数据
                    val audio = Base64.encodeToString(slice, Base64.NO_WRAP)  // 将音频数据进行Base64编码
                    val data = JSONObject()  // 创建数据对象
                    data.put("status", status)  // 设置帧状态
                    data.put("format", "audio/L16;rate=16000")  // 设置音频格式：16位PCM，16000Hz采样率
                    data.put("encoding", "raw")  // 设置编码方式：原始数据
                    data.put("audio", audio)  // 设置音频数据

                    val frame = JSONObject()  // 创建帧对象
                    if (status == 0) {  // 如果是首帧
                        val common = JSONObject()  // 创建通用配置对象
                        common.put("app_id", APP_ID)  // 设置应用ID
                        val business = JSONObject()  // 创建业务配置对象
                        business.put("language", "zh_cn")  // 设置语言：中文
                        business.put("domain", "iat")  // 设置领域：语音听写
                        business.put("accent", "mandarin")  // 设置口音：普通话
                        frame.put("common", common)  // 添加通用配置
                        frame.put("business", business)  // 添加业务配置
                    }
                    frame.put("data", data)  // 添加数据
                    webSocket.send(frame.toString())  // 发送帧数据

                    status = 1  // 后续帧都设置为中间帧状态
                    offset = end  // 更新偏移量
                    Thread.sleep(40)  // 等待40ms，模拟实时发送（每帧80ms音频，发送间隔40ms）
                }

                val endFrame = JSONObject()  // 创建尾帧对象
                val endData = JSONObject()  // 创建尾帧数据对象
                endData.put("status", 2)  // 设置状态为尾帧
                endFrame.put("data", endData)  // 添加数据
                webSocket.send(endFrame.toString())  // 发送尾帧
            } catch (e: Exception) {  // 捕获异常
                Log.e(TAG, "sendPcmFrames failed", e)  // 记录错误日志
                webSocket.close(1000, "send error")  // 关闭WebSocket连接
            }
        }.start()  // 启动线程
    }

    // 将识别后的文本发送给ESP32设备
    @SuppressLint("MissingPermission")  
    private fun sendTextToEsp(text: String) {  
        val c = transChar ?: return  // 如果特征值不存在，直接返回
        val payload = if (text.isBlank()) "[empty]" else text  // 如果文本为空，发送"[empty]"，否则发送原文本
        val bytes = payload.toByteArray()  // 将文本转换为字节数组
        if (Build.VERSION.SDK_INT >= 33) {  // 如果Android版本是13或更高
            val ret = gatt?.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)  // 使用新的API写入特征值
            if (ret != BluetoothStatusCodes.SUCCESS) {  // 如果写入失败
                runOnUiThread { updateStatus("Status: write request rejected ($ret)") }  // 显示写入被拒绝的状态
            }
        } else {  // 如果Android版本低于13
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT  // 设置写入类型为默认
            c.value = bytes  // 设置要写入的数据
            val ok = gatt?.writeCharacteristic(c) ?: false  // 写入特征值
            if (!ok) {  // 如果写入失败
                runOnUiThread { updateStatus("Status: write request rejected") }  // 显示写入被拒绝的状态
            }
        }
    }
}
