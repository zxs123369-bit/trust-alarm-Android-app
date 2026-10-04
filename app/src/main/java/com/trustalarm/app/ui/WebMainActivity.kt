package com.trustalarm.app.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.trustalarm.app.BuildConfig
import com.trustalarm.app.R
import com.trustalarm.app.alarm.AlarmNotifier
import com.trustalarm.app.bridge.NativeBridge
import com.trustalarm.app.service.HeartbeatService

/**
 * 手机端主界面：WebView 加载打包进 assets 的**网页版**。
 *
 * ## 设计意图
 *
 * 用户要求「手机端登录进去看起来跟网页版同步」。最省事且**永不漂移**的做法
 * 是直接复用网页版，而不是用 Kotlin 把 15 个页面重画一遍 —— 后者不仅工作量大，
 * 且此后每改一次 UI 都要改两处，必然越走越偏。
 *
 * 因此：
 *   UI     = web/ 原样（由 android/sync_web.py 同步进 assets，改写相对路径）
 *   能力   = 原生桥 [NativeBridge]（系统闹钟、心跳、精确闹钟权限）
 *
 * ## 为什么闹钟仍然可靠
 *
 * 闹钟不依赖本界面存活：注册进 AlarmManager 后，到点由系统的
 * AlarmReceiver 唤醒 App 并拉起 AlarmRingActivity，WebView 是否活着无关。
 *
 * ## 加载方式（★ 关键，踩过坑）
 *
 * **不能用 `file:///android_asset/...`。**
 *
 * 原因：网页版是 ES Module（`<script type="module">` + `import`）。
 * `file://` 是 opaque origin，WebView/Chrome 对 module 脚本强制走 CORS 检查，
 * 而 file:// 下这个检查必然失败 —— 表现就是**打开一片空白**（所有 import 被拦，
 * main.js 根本没执行）。`allowFileAccess = true` 对 module 无效。
 *
 * 正解：用 [WebViewAssetLoader] 把 assets 映射到一个**虚拟 https 域名**下
 * （`https://appassets.androidplatform.net/assets/web/...`）。这样页面有
 * 正常的 origin，ES Module 能正常加载，且仍是本地文件、不走网络。
 *
 * 附带好处：origin 从 "null" 变成真实域名后，CORS 配置也能更精确。
 */
class WebMainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebMainActivity"

        /**
         * 虚拟域名。assets 挂在这个域下，由 WebViewAssetLoader 拦截请求、
         * 直接从 APK 里读文件（不发网络请求）。
         *
         * 用 androidplatform.net 是 Google 官方推荐的保留域名。
         */
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"

        /** assets 在虚拟域下的路径前缀 */
        private const val ASSET_PATH = "/assets/"

        /** 入口页（注意必须是虚拟域的 https 地址，不是 file://） */
        private const val START_URL =
            "https://$ASSET_DOMAIN$ASSET_PATH" + "web/index.html"

        /**
         * 后端地址（网页版的接口在哪个 host 上）。
         *
         * 为什么必须注入：网页版的 api.js 用相对路径 '/api/v1'，
         * 在虚拟域下会解析成 https://appassets.androidplatform.net/api/v1 —— 必然 404。
         * 所以在页面脚本执行前注入 window.__apiBase，让 api.js 用绝对地址。
         *
         * 默认值取 BuildConfig（由 build.gradle 按构建类型/环境注入），
         * 这样同一份代码能出「连局域网」和「连模拟器宿主机」两种包，
         * 不必改源码 —— 之前写死在这里导致模拟器无法联调。
         */
        private val API_BASE: String = BuildConfig.API_BASE
    }

    private lateinit var webView: WebView

    /**
     * 把 assets 映射到虚拟 https 域名。
     *
     * 必须在 WebViewClient.shouldInterceptRequest 里接上（见下），
     * 否则虚拟域名的请求会真的走网络然后 404。
     */
    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler(
                ASSET_PATH,
                WebViewAssetLoader.AssetsPathHandler(this),
            )
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        // 通知渠道要在任何可能发通知之前建好
        AlarmNotifier.ensureChannels(this)

        webView = findViewById(R.id.webView)
        configureWebView()

        // 返回键：优先在网页内部回退（hash 路由），到底了才退出 App
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.loadUrl(START_URL)
        Log.i(TAG, "已加载 $START_URL")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true

            // 网页版用 localStorage 存 refresh_token —— 不开启会每次都要重登
            domStorageEnabled = true
            databaseEnabled = true

            // 加载本地 assets 由 WebViewAssetLoader 负责（虚拟 https 域），
            // 不再需要 file:// 访问权限；关掉还能减少攻击面。
            allowFileAccess = false
            allowContentAccess = false

            // 网页版要连局域网后端 192.168.x.x:8848（明文 http）
            // Android 9+ 默认禁止明文，这里放开（Manifest 里也配了 usesCleartextTraffic）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // 用视口宽度自适应，避免网页版被当成桌面页缩小
            useWideViewPort = true
            loadWithOverviewMode = true

            // 缩放：网页版本身是移动端设计，禁掉用户缩放（与 PWA 的 maximum-scale=1 一致）
            setSupportZoom(false)
            builtInZoomControls = false
        }

        // 调试期开启，可通过 chrome://inspect 远程调试网页（发版应关）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.addJavascriptInterface(NativeBridge(applicationContext), NativeBridge.JS_NAME)
        // ★ 把 WebView 注册给桥：原生侧刷新令牌后需要回写网页 localStorage，
        //   让两层共用同一把 refresh_token（单一事实来源）。
        //   不回写的后果：两边各自轮换、互相把对方判为「泄露」→ 账号全部令牌被吊销
        //   → 后台服务永久 401 → 闹钟一条都不注册。
        NativeBridge.attachWebView(webView)
        // 自更新桥：检查版本 / 下载 / 拉起安装
        webView.addJavascriptInterface(
            com.trustalarm.app.update.UpdateManager(applicationContext),
            com.trustalarm.app.update.UpdateManager.JS_NAME,
        )

        webView.webViewClient = object : WebViewClient() {
            /**
             * ★ 核心：把所有指向虚拟域名的请求，从 APK 的 assets 里直接读出来。
             *
             * 这是 ES Module 能在壳里跑起来的关键 —— 见类注释。
             * 用 shouldInterceptRequest 而不是 file://，页面才有正常 origin。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            /**
             * 页面脚本执行前注入配置。
             *
             * 注入时机很关键：必须早于 api.js 执行，否则 BASE 已经算成
             * '/api/v1' 了。onPageStarted 是页面 DOM 就绪前、脚本执行前的时机。
             */
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript("window.__apiBase = '$API_BASE';", null)
                Log.i(TAG, "已注入 __apiBase = $API_BASE")
            }

            /**
             * 页面加载完成后：拉起后台服务。
             *
             * 为什么放在这里：后台服务负责心跳与闹钟同步，必须尽早启动。
             * 不能只依赖用户点进「本地闹钟」页 —— 那样在用户没访问该页时，
             * 心跳不发（显示离线）且新闹钟不会被注册（到点不响）。
             * 页面每次加载都会调，但 start() 是幂等的，重复调用无副作用。
             */
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                try {
                    HeartbeatService.start(applicationContext)
                    Log.i(TAG, "已拉起后台服务")
                } catch (e: Exception) {
                    Log.w(TAG, "拉起后台服务失败：${e.message}")
                }
            }

            /**
             * 页面加载失败时把原因显示出来。
             *
             * 为什么重要：壳里出错的表现就是「一片空白」，用户在手机上
             * 完全看不到线索（也没有控制台）。这里把错误画到页面上，
             * 让问题可诊断而不是靠猜。
             */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                Log.e(TAG, "加载失败: ${request.url} -> ${error.description}")
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(
                        null,
                        """
                        <html><meta name="viewport" content="width=device-width,initial-scale=1">
                        <body style="font-family:sans-serif;padding:24px;line-height:1.7">
                        <h3>页面加载失败</h3>
                        <p>${request.url}</p>
                        <p style="color:#c53030">${error.description}</p>
                        </body></html>
                        """.trimIndent(),
                        "text/html", "UTF-8", null,
                    )
                }
            }

            /**
             * 拦截非本地跳转。
             *
             * 只放行虚拟域（本地 assets）与后端 host；其余一律拦截 ——
             * 否则 WebView 会变成无地址栏浏览器，能打开任意网页。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                val allowed = url.startsWith("https://$ASSET_DOMAIN") ||
                    url.startsWith(API_BASE) ||
                    url.startsWith("http://localhost") ||
                    url.startsWith("http://127.0.0.1")
                if (!allowed) {
                    Log.w(TAG, "已拦截外部导航：$url")
                }
                return !allowed
            }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface(NativeBridge.JS_NAME)
        webView.destroy()
        super.onDestroy()
    }
}
