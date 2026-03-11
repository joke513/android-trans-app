package com.example.transtopcm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageButton
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
 * 这是应用的主界面，负责以下功能：
 * 1. 通过蓝牙连接 ESP32 设备
 * 2. 从 ESP32 接收 PCM 音频数据
 * 3. 使用讯飞语音识别 API 将音频转换为文字
 * 4. 将识别的文字发送回 ESP32 设备
 */
class MainActivity : AppCompatActivity(), BleManager.BleStateListener {

    private val TAG = "TransApp"

    private lateinit var btnConnect: Button
    private lateinit var btnStart: Button
    private lateinit var btnProfile: ImageButton
    private lateinit var txtStatus: TextView
    private lateinit var txtResult: TextView
    private lateinit var cardResult: CardView
    private lateinit var statusIndicator: View

    private lateinit var fadeInUp: Animation
    private lateinit var pulseAnimation: Animation

    private var expectedBytes = 16000 * 5 * 2
    private var pcmBuffer = ByteArray(0)
    private var receiving = false
    private var waitPcmAfterStart = false

    private val APP_ID = "e6dd1814"
    private val API_KEY = "19920b233b1d0f319a6152770dca01f9"
    private val API_SECRET = "NjE4M2Q3MjcwNDg0MmExNjZiZDNjMDll"

    private val PCM_EXPECTED_BYTES = 16000 * 5 * 2

    private val IAT_HOST = "iat-api.xfyun.cn"
    private val IAT_PATH = "/v2/iat"
    private val httpClient = OkHttpClient.Builder().build()
    private val asrInProgress = AtomicBoolean(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        UserManager.init(this)

        btnConnect = findViewById(R.id.btnConnect)
        btnStart = findViewById(R.id.btnStart)
        btnProfile = findViewById(R.id.btnProfile)
        txtStatus = findViewById(R.id.txtStatus)
        txtResult = findViewById(R.id.txtResult)
        cardResult = findViewById(R.id.cardResult)
        statusIndicator = findViewById(R.id.statusIndicator)

        fadeInUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up)
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse)

        setupAnimations()
        btnConnect.setOnClickListener { startScan() }
        btnStart.setOnClickListener { startTrans() }
        btnProfile.setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }

        BleManager.addListener(this)
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUIFromBleState()
    }

    override fun onDestroy() {
        super.onDestroy()
        BleManager.removeListener(this)
    }

    private fun setupAnimations() {
        cardResult.startAnimation(fadeInUp)
    }

    private fun updateStatusIndicator(status: String) {
        statusIndicator.clearAnimation()
        
        when {
            status.contains("扫描") -> {
                statusIndicator.setBackgroundColor(getColor(R.color.warning))
                statusIndicator.startAnimation(pulseAnimation)
            }
            status.contains("就绪") || status.contains("已连接") -> {
                statusIndicator.setBackgroundColor(getColor(R.color.success))
                statusIndicator.clearAnimation()
            }
            status.contains("接收") || status.contains("ASR") -> {
                statusIndicator.setBackgroundColor(getColor(R.color.accent))
                statusIndicator.startAnimation(pulseAnimation)
            }
            status.contains("失败") || status.contains("错误") -> {
                statusIndicator.setBackgroundColor(getColor(R.color.error))
                statusIndicator.clearAnimation()
            }
            else -> {
                statusIndicator.setBackgroundColor(getColor(R.color.text_secondary))
                statusIndicator.clearAnimation()
            }
        }
    }

    private fun updateStatus(status: String) {
        txtStatus.text = status
        updateStatusIndicator(status)
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
            updateStatus("Status: 蓝牙权限被拒绝")
            requestPermissions()
            return
        }
        BleManager.startScan(this)
    }

    // BleStateListener 回调
    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        runOnUiThread {
            updateStatus("Status: $message")
            updateUIFromBleState()
        }
    }

    override fun onPcmReceived(data: ByteArray) {
        if (!waitPcmAfterStart) return

        if (!receiving) {
            receiving = true
            runOnUiThread { updateStatus("Status: 正在接收PCM...") }
        }

        pcmBuffer += data
        if (pcmBuffer.size >= expectedBytes) {
            receiving = false
            waitPcmAfterStart = false
            val pcm = pcmBuffer.copyOf(expectedBytes)
            runOnUiThread { updateStatus("Status: ASR...") }
            startASR(pcm)
        }
    }

    private fun updateUIFromBleState() {
        val connected = BleManager.isConnected()
        btnConnect.text = if (connected) "断开连接" else "蓝牙连接"
    }
    
    private fun startTrans() {
        if (!BleManager.isConnected()) {
            updateStatus("Status: 蓝牙未就绪")
            return
        }
        expectedBytes = PCM_EXPECTED_BYTES
        pcmBuffer = ByteArray(0)
        receiving = false
        waitPcmAfterStart = true
        updateStatus("Status: 请在ESP32上按下传输按钮")
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

    @SuppressLint("MissingPermission")  
    private fun sendTextToEsp(text: String) {
        val success = BleManager.writeText(text)
        runOnUiThread {
            updateStatus(if (success) "Status: 文本已发送" else "Status: 发送失败")
        }
    }
}
