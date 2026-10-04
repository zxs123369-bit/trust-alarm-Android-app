package com.trustalarm.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import com.trustalarm.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App 自更新：检查新版本 → 下载 APK → 拉起系统安装器。
 *
 * ## 为什么需要
 *
 * 用户反馈「每一次版本更新我都得找网址下载」。手工发链接不可持续。
 *
 * ## 为什么不能全自动
 *
 * Android 出于安全，**安装 APK 必须用户确认**（非商店渠道）。
 * 所以本方案能做到的是：App 自己发现新版本、自己下载、自动拉起安装界面，
 * 用户只需点一次「安装」。真正的静默升级需要上应用商店。
 *
 * ## 流程
 *
 * ```
 * 网页调 checkUpdate()  →  比对 versionCode
 *   有新版 → 返回 {hasUpdate:true, versionName, notes, size}
 * 网页让用户确认后调 startUpdate()
 *   → DownloadManager 下载 APK 到 app 外部目录
 *   → 下载完成广播 → FileProvider 生成 content:// URI
 *   → ACTION_VIEW 拉起系统安装器（用户在系统界面点「安装」）
 * ```
 *
 * ## 关键点：FileProvider
 *
 * Android 7+ 不允许用 file:// URI 跨进程传递文件（会抛
 * FileUriExposedException）。必须用 FileProvider 生成 content:// URI，
 * 并在 AndroidManifest 声明 provider + res/xml/file_paths.xml。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        /** JS 侧对象名：window.TrustAlarmUpdater */
        const val JS_NAME = "TrustAlarmUpdater"

        /** 下载用的 OkHttp（只用来查版本，APK 交给系统 DownloadManager） */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    /** 最近一次查到的更新信息，startUpdate 时用 */
    @Volatile
    private var latest: JSONObject? = null

    /**
     * 检查是否有新版本。
     *
     * @return JSON 字符串（跨 JS 边界只传简单类型可靠）：
     *   { hasUpdate, versionName, versionCode, notes, size, error? }
     */
    @JavascriptInterface
    fun checkUpdate(): String {
        return try {
            val url = BuildConfig.API_BASE.trimEnd('/') + "/api/v1/app/version"
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return errJson("服务端返回 ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                return errJson(json.optString("message", "检查更新失败"))
            }
            val d = json.optJSONObject("data") ?: return errJson("返回体缺少 data")
            latest = d

            val remoteCode = d.optInt("version_code", 0)
            val localCode = BuildConfig.VERSION_CODE
            val has = remoteCode > localCode

            JSONObject()
                .put("hasUpdate", has)
                .put("versionName", d.optString("version_name"))
                .put("versionCode", remoteCode)
                .put("notes", d.optString("notes"))
                .put("size", d.optInt("size", 0))
                .put("currentVersion", BuildConfig.VERSION_NAME)
                .toString()
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败", e)
            errJson(e.message ?: "网络异常")
        }
    }

    /**
     * 开始下载并安装。
     *
     * 用系统 DownloadManager 而不是自己写流：它在后台可靠下载、
     * 断网可续、且失败有系统级重试 —— APK 较大时比自己实现稳。
     *
     * @return true 表示已开始下载
     */
    @JavascriptInterface
    fun startUpdate(): Boolean {
        val d = latest ?: run {
            Log.w(TAG, "没有版本信息，先调 checkUpdate")
            return false
        }
        val path = d.optString("download_url")
        if (path.isBlank()) {
            Log.w(TAG, "服务端未提供下载地址")
            return false
        }
        val fullUrl = if (path.startsWith("http")) {
            path
        } else {
            BuildConfig.API_BASE.trimEnd('/') + path
        }
        val fileName = d.optString("file_name").ifBlank { "trustalarm.apk" }

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(fullUrl)).apply {
                setTitle("信任闹钟 $fileName")
                setDescription("正在下载新版本…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                // 下到 App 专属外部目录，不需要存储权限
                setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, fileName
                )
                setMimeType("application/vnd.android.package-archive")
            }
            val id = dm.enqueue(req)
            Log.i(TAG, "开始下载更新 id=$id url=$fullUrl")
            registerInstallOnComplete(id, fileName, dm)
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动下载失败", e)
            false
        }
    }

    /**
     * 下载完成后拉起安装界面。
     *
     * 为什么用动态注册的 Receiver：下载是系统进程做的，完成后要回到
     * 我们进程来启动安装 Activity。用一次性注册（用完立即注销）避免泄漏。
     */
    private fun registerInstallOnComplete(downloadId: Long, fileName: String, dm: DownloadManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                }
                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    Log.w(TAG, "下载失败，拿不到文件 URI")
                    return
                }
                installApk(ctx, uri)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    /** 用系统安装器打开 APK。用户需在系统界面点一次「安装」。 */
    private fun installApk(ctx: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 授予临时读权限，否则安装器读不到文件
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            Log.i(TAG, "已拉起系统安装器")
        } catch (e: Exception) {
            Log.e(TAG, "拉起安装器失败", e)
        }
    }

    /** 单纯查版本用（不动 latest），供原生侧调用。 */
    fun fetchLatestSync(): JSONObject? = try {
        val url = BuildConfig.API_BASE.trimEnd('/') + "/api/v1/app/version"
        val req = Request.Builder().url(url).get().build()
        val body = http.newCall(req).execute().use { it.body?.string().orEmpty() }
        JSONObject(body).optJSONObject("data")
    } catch (e: Exception) {
        Log.w(TAG, "fetchLatest 失败：${e.message}")
        null
    }

    private fun errJson(msg: String): String =
        JSONObject().put("hasUpdate", false).put("error", msg).toString()
}
