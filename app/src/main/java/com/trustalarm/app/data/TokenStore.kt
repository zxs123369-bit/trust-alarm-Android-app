package com.trustalarm.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * 令牌与设备信息的本地存储。
 *
 * 为什么 access 不落盘：access 是短效令牌，放内存即可；
 * 落盘会增加泄露面。refresh 必须落盘（用于重启后恢复会话）。
 */
class TokenStore(context: Context) {

    private val sp = context.getSharedPreferences("trust_alarm_session", Context.MODE_PRIVATE)

    fun save(session: Session) {
        sp.edit {
            putString(KEY_REFRESH, session.refreshToken)
            putString(KEY_UID, session.userUid)
            putString(KEY_NICK, session.nickname)
            putString(KEY_PHONE, session.phone)
        }
    }

    fun saveRefreshToken(rt: String) {
        sp.edit { putString(KEY_REFRESH, rt) }
    }

    fun getRefreshToken(): String? = sp.getString(KEY_REFRESH, null)

    fun getUserUid(): String = sp.getString(KEY_UID, "").orEmpty()

    fun getNickname(): String = sp.getString(KEY_NICK, "").orEmpty()

    fun getPhone(): String = sp.getString(KEY_PHONE, "").orEmpty()

    fun hasRefreshToken(): Boolean = !getRefreshToken().isNullOrBlank()

    fun clear() {
        sp.edit { clear() }
    }

    // ---------- 本机绑定的设备 ----------

    fun saveDevice(uid: String, name: String) {
        sp.edit {
            putString(KEY_DEVICE_UID, uid)
            putString(KEY_DEVICE_NAME, name)
        }
    }

    fun getDeviceUid(): String = sp.getString(KEY_DEVICE_UID, "").orEmpty()

    fun getDeviceName(): String = sp.getString(KEY_DEVICE_NAME, "").orEmpty()

    fun hasDevice(): Boolean = getDeviceUid().isNotBlank()

    fun clearDevice() {
        sp.edit {
            remove(KEY_DEVICE_UID)
            remove(KEY_DEVICE_NAME)
        }
    }

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_UID = "user_uid"
        const val KEY_NICK = "nickname"
        const val KEY_PHONE = "phone"
        const val KEY_DEVICE_UID = "device_uid"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
