package com.example.transtopcm

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProfileActivity : AppCompatActivity(), BleManager.BleStateListener {

    private lateinit var cardUser: CardView
    private lateinit var txtNickname: TextView
    private lateinit var txtVipStatus: TextView
    private lateinit var txtLogout: TextView
    private lateinit var txtDeviceStatus: TextView
    private lateinit var btnDeviceAction: Button
    private lateinit var rvHistory: RecyclerView
    private lateinit var txtNoHistory: TextView

    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        UserManager.init(this)
        initViews()
        setupListeners()
        BleManager.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateUserUI()
        updateDeviceUI()
        updateHistoryUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        BleManager.removeListener(this)
    }

    private fun initViews() {
        cardUser = findViewById(R.id.cardUser)
        txtNickname = findViewById(R.id.txtNickname)
        txtVipStatus = findViewById(R.id.txtVipStatus)
        txtLogout = findViewById(R.id.txtLogout)
        txtDeviceStatus = findViewById(R.id.txtDeviceStatus)
        btnDeviceAction = findViewById(R.id.btnDeviceAction)
        rvHistory = findViewById(R.id.rvHistory)
        txtNoHistory = findViewById(R.id.txtNoHistory)

        historyAdapter = HistoryAdapter()
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        cardUser.setOnClickListener {
            if (!UserManager.isLoggedIn()) {
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        txtLogout.setOnClickListener {
            UserManager.logout()
            updateUserUI()
        }

        btnDeviceAction.setOnClickListener {
            if (BleManager.isConnected()) {
                BleManager.disconnect()
            } else {
                BleManager.startScan(this)
            }
        }
    }

    private fun updateUserUI() {
        if (UserManager.isLoggedIn()) {
            txtNickname.text = UserManager.getNickname()
            txtVipStatus.text = if (UserManager.isVip()) "会员" else "普通用户"
            txtLogout.visibility = View.VISIBLE
        } else {
            txtNickname.text = "未登录"
            txtVipStatus.text = "点击登录账号"
            txtLogout.visibility = View.GONE
        }
    }

    private fun updateDeviceUI() {
        val connected = BleManager.isConnected()
        txtDeviceStatus.text = if (connected) "已连接" else "未连接"
        txtDeviceStatus.setTextColor(getColor(if (connected) R.color.success else R.color.text_secondary))
        btnDeviceAction.text = if (connected) "断开" else "连接"
    }

    private fun updateHistoryUI() {
        val history = BleManager.connectionHistory
        if (history.isEmpty()) {
            txtNoHistory.visibility = View.VISIBLE
            rvHistory.visibility = View.GONE
        } else {
            txtNoHistory.visibility = View.GONE
            rvHistory.visibility = View.VISIBLE
            historyAdapter.updateData(history)
        }
    }

    override fun onStateChanged(state: BleManager.ConnectionState, message: String) {
        runOnUiThread {
            updateDeviceUI()
            updateHistoryUI()
        }
    }

    // 连接历史适配器
    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private var data = listOf<BleManager.ConnectionLog>()

        fun updateData(newData: List<BleManager.ConnectionLog>) {
            data = newData.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = data[position]
            holder.text1.text = item.event
            holder.text1.setTextColor(getColor(R.color.text_primary))
            holder.text2.text = item.time
            holder.text2.setTextColor(getColor(R.color.text_secondary))
        }

        override fun getItemCount() = data.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text1: TextView = view.findViewById(android.R.id.text1)
            val text2: TextView = view.findViewById(android.R.id.text2)
        }
    }
}
