package com.example.transtopcm

import android.content.Context
import android.content.SharedPreferences

/**
 * UserManager - 用户管理单例
 * 负责登录状态和用户信息管理
 */
object UserManager {
    private const val PREF_NAME = "user_pref"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USERNAME = "username"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_IS_VIP = "is_vip"

    private lateinit var prefs: SharedPreferences

    // 模拟账号
    private const val MOCK_ACCOUNT = "test"
    private const val MOCK_PASSWORD = "123456"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun login(username: String, password: String): Boolean {
        if (username == MOCK_ACCOUNT && password == MOCK_PASSWORD) {
            prefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_USERNAME, username)
                .putString(KEY_NICKNAME, "测试用户")
                .putBoolean(KEY_IS_VIP, false)
                .apply()
            return true
        }
        return false
    }

    fun logout() {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .remove(KEY_USERNAME)
            .remove(KEY_NICKNAME)
            .remove(KEY_IS_VIP)
            .apply()
    }

    fun getNickname(): String {
        return prefs.getString(KEY_NICKNAME, "") ?: ""
    }

    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "") ?: ""
    }

    fun isVip(): Boolean {
        return prefs.getBoolean(KEY_IS_VIP, false)
    }
}
