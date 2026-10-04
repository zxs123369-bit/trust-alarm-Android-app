package com.trustalarm.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trustalarm.app.R
import com.trustalarm.app.data.ApiClient
import kotlinx.coroutines.launch

/** 登录页 */
class LoginActivity : AppCompatActivity() {

    private lateinit var api: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        api = ApiClient(applicationContext)

        val phone = findViewById<EditText>(R.id.edtPhone)
        val pwd = findViewById<EditText>(R.id.edtPassword)
        val btn = findViewById<Button>(R.id.btnLogin)
        val tip = findViewById<TextView>(R.id.txtTip)

        // 已登录 → 直接进主页
        if (api.isLoggedIn) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        btn.setOnClickListener {
            val p = phone.text.toString().trim()
            val w = pwd.text.toString()
            if (p.length != 11) {
                tip.text = "请输入 11 位手机号"
                return@setOnClickListener
            }
            if (w.isBlank()) {
                tip.text = "请输入密码"
                return@setOnClickListener
            }
            btn.isEnabled = false
            tip.text = "登录中…"
            lifecycleScope.launch {
                try {
                    api.login(p, w)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    tip.text = e.message ?: "登录失败"
                    btn.isEnabled = true
                }
            }
        }
    }
}
