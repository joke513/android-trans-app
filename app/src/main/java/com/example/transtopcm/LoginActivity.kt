package com.example.transtopcm

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var txtError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        UserManager.init(this)
        initViews()
        setupListeners()
    }

    private fun initViews() {
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        txtError = findViewById(R.id.txtError)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            val username = etUsername.text?.toString()?.trim() ?: ""
            val password = etPassword.text?.toString() ?: ""

            if (username.isEmpty() || password.isEmpty()) {
                showError("请输入账号和密码")
                return@setOnClickListener
            }

            if (UserManager.login(username, password)) {
                finish()
            } else {
                showError("账号或密码错误")
            }
        }
    }

    private fun showError(msg: String) {
        txtError.text = msg
        txtError.visibility = View.VISIBLE
    }
}
