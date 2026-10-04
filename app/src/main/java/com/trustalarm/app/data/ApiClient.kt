package com.trustalarm.app.data

import android.content.Context
import android.util.Log
import com.trustalarm.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后端 API 客户端。
 *
 * ## 关键设计：与 PWA 一致的令牌策略
 *   access 存内存 + refresh 存本地 + 刷新单飞。
 *   后端 /auth/refresh 是**一次性轮换**（用掉旧 refresh 立即吊销），
 *   并发刷新会被判定为令牌泄露 → 吊销该用户全部令牌（强制登出）。
 *   所以刷新必须串行化 —— 这里用 synchronized 实现。
 */
class ApiClient(private val context: Context) {

    companion object {
        private const val TAG = "ApiClient"

        /**
         * 后端基地址。
         *
         * ★ 必须从 BuildConfig.API_BASE 派生，不能写死局域网 IP。
         *   历史缺陷（P0，本轮修复）：这里曾硬编码 "http://192.168.1.3:8848/api/v1"，
         *   且全项目无任何地方覆盖它 —— 于是 release 包（API_BASE=公网域名）的
         *   「登录/刷新/心跳/闹钟/消息/家庭/设备」全部仍打 192.168.1.3。
         *   后果：用户手机不在该局域网时，界面靠旧 session 看似正常，
         *   实际所有核心请求静默失败 → 「登录不上」「显示离线」「闹钟不同步」。
         *   （WebMainActivity 早已改用 BuildConfig，此处是同一 bug 的漏修一半。）
         *
         * BuildConfig.API_BASE 形如 "http://101.133.229.168"（无 /api/v1 后缀），
         * 这里统一补上 "/api/v1"，保持各处 path 以 "/" 开头的调用约定不变。
         */
        var baseUrl: String = BuildConfig.API_BASE.trimEnd('/') + "/api/v1"

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val store = TokenStore(context)

    /** 内存中的 access token（不落盘，降低泄露风险） */
    @Volatile
    private var accessToken: String? = null

    private val refreshLock = Any()

    val isLoggedIn: Boolean get() = store.hasRefreshToken()

    /**
     * 内存里是否已有可用的 access token。
     *
     * 为什么需要这个判据（★ 真机实测根因）：
     *   HeartbeatService 的循环周期是 10 秒（为了让「1 分钟后响」的闹钟及时注册），
     *   而它的每一轮都会调 restoreSession() —— 那是**完整的 refresh 轮换**。
     *   实测后果：refresh token 每 10 秒被轮换一次（后端 4 分钟收到 70 次
     *   /auth/refresh，而 heartbeat 只有 11 次）。
     *
     *   这是致命的：网页层（localStorage）与原生层各持一把 refresh token，
     *   原生层高频轮换会让网页层那份迅速变成「已吊销令牌」。一旦网页层拿旧
     *   令牌去刷新，后端的复用检测就会判定「令牌泄露」→ **吊销该账号全部令牌**
     *   → 后台服务永久 401 → 心跳停（家人列表显示离线）+ 闹钟永不注册（到点不响）。
     *
     *   access 是内存字段，进程重启后为 null —— 所以「access 为空」恰好等于
     *   「本进程还没拿到令牌」，此时才需要且只需要恢复一次。
     */
    fun hasValidAccess(): Boolean = !accessToken.isNullOrBlank()

    // ---------- 核心请求 ----------

    /**
     * 发请求，自动带令牌；401 时自动刷新一次并重试。
     * @throws ApiException 业务或网络错误
     */
    private fun requestSync(
        method: String,
        path: String,
        body: JSONObject? = null,
        needAuth: Boolean = true,
        retried: Boolean = false,
    ): JSONObject {
        val url = baseUrl + path
        Log.i(TAG, "→ $method $url")   // 便于验证 baseUrl 注入是否正确（P0 修复验证）
        val builder = Request.Builder().url(url)
        if (body != null) {
            builder.method(method, body.toString().toRequestBody(JSON))
        } else {
            builder.method(method, null)
        }
        if (needAuth) {
            accessToken?.let { builder.header("Authorization", "Bearer $it") }
        }

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw ApiException(-1, "响应格式异常：${text.take(120)}")
            }

            // 令牌过期 → 刷新一次再重试（只重试一次，避免死循环）
            if (resp.code == 401 && needAuth && !retried) {
                if (refreshTokenSync()) {
                    return requestSync(method, path, body, needAuth, retried = true)
                }
            }

            val code = json.optInt("code", -1)
            if (resp.code !in 200..299 || code != 0) {
                throw ApiException(
                    code,
                    json.optString("message", "请求失败（HTTP ${resp.code}）"),
                    resp.code,
                )
            }

            // ★ data 有两种形态，必须都处理：
            //   · 对象 {"list":[...]} / 单个对象
            //   · 数组 [...]            —— /families、/devices 用这种
            //
            // 原实现只做 optJSONObject，遇到数组会得到 null → 返回空对象，
            // 于是列表永远是空的。表现为「账号下没有本机设备」，
            // 导致心跳不发（显示离线）+ 闹钟不同步（到点不响）。
            // 这是实测踩到的真问题。
            val dataNode = json.opt("data")
            return when (dataNode) {
                null -> JSONObject()
                is JSONObject -> dataNode
                is JSONArray -> JSONObject().put("list", dataNode)
                else -> JSONObject().put("value", dataNode)
            }
        }
    }

    /**
     * 刷新令牌（**单飞**：并发调用会串行化）。
     *
     * 为什么必须单飞：后端 refresh 是一次性轮换，两个并发刷新
     * 会让第二个用「已被用掉的」令牌 → 触发泄露检测 → 全部令牌吊销。
     */
    private fun refreshTokenSync(): Boolean = synchronized(refreshLock) {
        val rt = store.getRefreshToken() ?: return false
        return try {
            val body = JSONObject().put("refresh_token", rt)
            Log.i(TAG, "→ POST ${baseUrl}/auth/refresh")   // P0 修复验证
            val req = Request.Builder()
                .url(baseUrl + "/auth/refresh")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = JSONObject(text)
                if (resp.code in 200..299 && json.optInt("code", -1) == 0) {
                    val d = json.optJSONObject("data") ?: return false
                    accessToken = d.optString("access_token")
                    val newRt = d.optString("refresh_token")
                    if (newRt.isNotBlank()) {
                        store.saveRefreshToken(newRt)
                        // ★ 同步回写网页层（单一事实来源）。
                        //
                        // 网页层持有自己的一份 refresh_token（localStorage）。
                        // 若不回写：原生刷新后旧令牌已被后端吊销，网页那份随即失效，
                        // 网页再用它刷新就会被判「已吊销令牌复用」= 疑似泄露，
                        // 导致**该账号全部令牌被吊销** → 后台服务永久 401
                        // → listAlarms 永不成功 → 闹钟一条都不注册（实测根因）。
                        com.trustalarm.app.bridge.NativeBridge
                            .pushRefreshTokenToWeb(newRt)
                    }
                    Log.i(TAG, "令牌已刷新")
                    true
                } else {
                    // 刷新失败 = 会话彻底失效，清本地避免反复重试
                    Log.w(TAG, "刷新失败：${json.optString("message")}")
                    clearSession()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "刷新异常", e)
            false
        }
    }

    // ---------- 认证 ----------

    suspend fun login(phone: String, password: String): Session = withContext(Dispatchers.IO) {
        val body = JSONObject().put("phone", phone).put("password", password)
        val data = requestSync("POST", "/auth/login", body, needAuth = false)
        val session = Session(
            accessToken = data.optString("access_token"),
            refreshToken = data.optString("refresh_token"),
            userUid = data.optJSONObject("user")?.optString("uid") ?: "",
            nickname = data.optJSONObject("user")?.optString("nickname") ?: "",
            phone = phone,
        )
        accessToken = session.accessToken
        store.save(session)
        session
    }

    /** 启动时恢复会话（用本地 refresh 换新 access） */
    suspend fun restoreSession(): Boolean = withContext(Dispatchers.IO) {
        if (!store.hasRefreshToken()) return@withContext false
        refreshTokenSync()
    }

    fun clearSession() {
        accessToken = null
        store.clear()
    }

    fun currentNickname(): String = store.getNickname()

    fun currentUserUid(): String = store.getUserUid()

    // ---------- 通用 HTTP 表面（供 Repository 使用）----------
    //
    // 为什么需要这一组：历史上 ApiClient 只暴露「按业务命名」的方法
    // （listFamilies / listAlarms / …），每加一个页面就要在 ApiClient 里
    // 加一个方法 —— 于是 ApiClient 膨胀到 477 行，且 UI 层的接口需求
    // 与网络层混在一起。
    //
    // 现在分成两层：
    //   · ApiClient   —— 只管「发请求 + 令牌 + 错误」，暴露通用的
    //                    get/post/patch/delete（下面这组）
    //   · Repository  —— 只管「URL 拼装 + 字段解析」，按域给强类型方法
    //
    // 原有的 listFamilies/findMyDeviceUid 等方法**保留不动**：
    // 它们被 HeartbeatService 等服务层依赖，且 findMyDeviceUid 里的
    // 设备匹配策略有过两轮真机踩坑，是唯一权威实现，不能重写。

    suspend fun get(path: String, needAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { requestSync("GET", path, null, needAuth) }

    suspend fun post(
        path: String,
        body: JSONObject? = null,
        needAuth: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) { requestSync("POST", path, body, needAuth) }

    suspend fun patch(path: String, body: JSONObject? = null, needAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { requestSync("PATCH", path, body, needAuth) }

    suspend fun put(path: String, body: JSONObject? = null, needAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { requestSync("PUT", path, body, needAuth) }

    suspend fun delete(path: String, needAuth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) { requestSync("DELETE", path, null, needAuth) }

    // ---------- 会话读写（供 Repository 使用）----------

    fun getRefreshToken(): String? = store.getRefreshToken()

    /**
     * 接管一对新令牌（登录/注册成功后调用）。
     *
     * 为什么单独一个方法而不是直接改 accessToken：access 是私有 volatile 字段，
     * 且 refresh 落盘要走 TokenStore。集中在这里可以保证
     * 「access 进内存、refresh 进磁盘」这个配对不会被拆散。
     */
    fun adoptTokens(access: String, refresh: String) {
        accessToken = access
        if (refresh.isNotBlank()) store.saveRefreshToken(refresh)
    }

    /** 保存当前登录用户资料（登录后调用，供 UI 展示昵称） */
    fun saveUser(uid: String, nickname: String, phone: String) {
        store.save(Session(
            accessToken = accessToken.orEmpty(),
            refreshToken = store.getRefreshToken().orEmpty(),
            userUid = uid,
            nickname = nickname,
            phone = phone,
        ))
    }

    /**
     * 拼 `?refresh_token=...` 查询串（`/auth/sessions` 需要它来标记 is_current）。
     * 返回空串表示没有 refresh token，调用方直接拼接即可。
     */
    fun refreshTokenQuery(): String {
        val rt = store.getRefreshToken()
        return if (rt.isNullOrBlank()) "" else "?refresh_token=$rt"
    }

    // ---------- 业务接口 ----------

    /** 我参与的家庭 */
    suspend fun listFamilies(): List<JSONObject> = withContext(Dispatchers.IO) {
        val data = requestSync("GET", "/families")
        // 注意：/families 返回的是数组，data 被包成 {"list": ...} 的情况要兼容
        parseList(data)
    }

    /**
     * 查出「本机」的设备 uid（本机名下、平台为 android 的那台）。
     *
     * ## ★ 为什么这段逻辑反复出问题（真机实测两次踩坑）
     *
     * 一个账号名下完全可能有**多台** android 设备。历史实现按顺序尝试
     * 「机型匹配 → 设备名匹配 → 取第一台候选」，但：
     *
     *   · 绑定时**没填 model** 的设备（真机常见）机型匹配必然失败
     *   · 用户没改过设备名时，名称匹配也常落空
     *   · 于是退化成「取遍历到的第一台」—— 而 listFamilies() 的顺序
     *     与「哪个才是当前手机」毫无关系
     *
     * 实测（账号 13800138000 / 13900139000 各一次）：
     *   名下有两台 android，第一台是**早已废弃的旧绑定**，
     *   结果心跳发到旧设备、闹钟从旧设备拉 →
     *   真机永远「从未心跳」（显示离线）+ 闹钟列表为空（永不注册、不响）。
     *
     * ## 现在的策略（按可靠度严格递减）
     *
     *   1. **本地已存的 device_uid** —— 用户在这台手机上绑过就直接用
     *      （由 resolveDeviceUid 先校验归属，最可信，不在这里重复）
     *   2. **机型完全一致**（Build.MODEL == device.model）
     *   3. **设备名一致**（本地存的名字 == device_name）
     *   4. **最近心跳过的那台** ★ 新增，比「第一台」可靠得多：
     *      真正在用的手机一定有近期心跳；废弃绑定则早已停跳。
     *      这解决了「model/name 都没填」时选错设备的核心问题。
     *   5. 最后才退回「第一台候选」（并明确告警）
     */
    suspend fun findMyDeviceUid(): String = withContext(Dispatchers.IO) {
        try {
            val myModel = android.os.Build.MODEL.orEmpty()
            val savedName = store.getDeviceName()
            // 收集全部候选，最后统一按可靠度挑选（不再「边遍历边返回」）
            val cands = mutableListOf<JSONObject>()
            for (f in listFamilies()) {
                val fuid = f.optString("uid")
                if (fuid.isBlank()) continue
                for (d in listDevices(fuid)) {
                    val isMine = d.optBoolean("owner_is_me", false) &&
                        d.optString("platform").equals("android", ignoreCase = true)
                    if (isMine && d.optString("uid").isNotBlank()) cands.add(d)
                }
            }
            if (cands.isEmpty()) return@withContext ""

            // 2) 机型完全一致
            if (myModel.isNotBlank()) {
                cands.firstOrNull {
                    it.optString("model").equals(myModel, ignoreCase = true)
                }?.let {
                    Log.i(TAG, "按机型匹配到本机设备 ${it.optString("uid").take(8)}（$myModel）")
                    return@withContext it.optString("uid")
                }
            }
            // 3) 设备名一致
            if (savedName.isNotBlank()) {
                cands.firstOrNull { it.optString("device_name") == savedName }?.let {
                    Log.i(TAG, "按设备名匹配到本机设备 ${it.optString("uid").take(8)}（$savedName）")
                    return@withContext it.optString("uid")
                }
            }
            // 4) ★ 最近绑定优先于最近心跳。
            //
            //    为什么顺序是这样（一个自我强化的陷阱）：
            //      旧的「取第一台」选错设备后，心跳会打到那台错设备上，
            //      于是它有了「近期心跳」。若把「最近心跳」排在前面，
            //      错误选择会**自我强化** —— 每轮都更确信那台是错的设备。
            //      实测就遇到：模拟器把心跳发给了 dev49（旧绑定），
            //      dev49 因此有了新鲜心跳，而真机 dev50 永远「从未心跳」。
            //
            //    bind_at 不受这个反馈回路影响：它只在用户真正执行绑定时
            //    写入。用户通常先在这台手机上绑定、再开始用它，
            //    所以「最近绑定」是更可靠的线索。
            val newest = cands
                .filter { it.optLong("bind_at", 0L) > 0L }
                .maxByOrNull { it.optLong("bind_at", 0L) }
            val fresh = cands
                .filter { it.optLong("last_heartbeat_at", 0L) > 0L }
                .maxByOrNull { it.optLong("last_heartbeat_at", 0L) }

            if (newest != null) {
                // 若最近绑定的那台与最近心跳的那台**不同**，用最近绑定的，
                // 并把差异记进日志（这是排查「选错设备」最关键的线索）。
                val nUid = newest.optString("uid")
                if (fresh != null && fresh.optString("uid") != nUid) {
                    Log.w(
                        TAG,
                        "绑定/心跳线索冲突：采用最近绑定的 ${nUid.take(8)}"
                            + "（${newest.optString("device_name")}），"
                            + "忽略最近心跳的 ${fresh.optString("uid").take(8)}"
                            + "（心跳可能是误报造成）",
                    )
                } else {
                    Log.i(
                        TAG,
                        "按最近绑定匹配到本机设备 ${nUid.take(8)}"
                            + "（${newest.optString("device_name")}）",
                    )
                }
                return@withContext nUid
            }

            // 5) 无绑定时间线索（老数据）→ 退回最近心跳
            if (fresh != null) {
                val ago = System.currentTimeMillis() - fresh.optLong("last_heartbeat_at", 0L)
                Log.i(
                    TAG,
                    "按最近心跳匹配到本机设备 ${fresh.optString("uid").take(8)}"
                        + "（${fresh.optString("device_name")}，${ago / 1000}s 前）",
                )
                return@withContext fresh.optString("uid")
            }
            // 6) 保底
            val fb = cands.first().optString("uid")
            Log.w(
                TAG,
                "无法确定本机设备（无机型/名称/绑定/心跳线索），退回第一台 ${fb.take(8)}"
                    + "；建议在「本地闹钟」页重新绑定本机",
            )
            fb
        } catch (e: Exception) {
            Log.w(TAG, "findMyDeviceUid 失败：${e.message}")
            ""
        }
    }

    /**
     * 创建家庭（自助建家）。
     *
     * 为什么要这个：单人用户第一次装 App 时没有任何家庭，
     * 而「绑定设备」又必须先有家庭 —— 若不自助创建，用户会被卡死在
     * 「你还没有家庭，请去网页版」这一步，App 完全不可用。
     */
    suspend fun createFamily(name: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name)
        val f = requestSync("POST", "/families", body)
        f.optString("uid")
    }

    /** 某家庭下的设备 */
    suspend fun listDevices(familyUid: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val data = requestSync("GET", "/devices?family_uid=$familyUid")
        parseList(data)
    }

    /** 绑定本机为新设备 */
    suspend fun bindDevice(familyUid: String, name: String, model: String): DeviceInfo =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("family_uid", familyUid)
                .put("device_name", name)
                .put("platform", "android")
                .put("model", model)
            val d = requestSync("POST", "/devices/bind", body)
            DeviceInfo.fromJson(d)
        }

    /** 某设备上的闹钟 */
    suspend fun listAlarms(deviceUid: String): List<AlarmItem> = withContext(Dispatchers.IO) {
        val data = requestSync("GET", "/devices/$deviceUid/alarms")
        val arr = data.optJSONArray("list") ?: return@withContext emptyList()
        (0 until arr.length()).map { AlarmItem.fromJson(arr.getJSONObject(it)) }
    }

    /**
     * 校验某个 device_uid 是否**确实属于当前账号**。
     *
     * 为什么需要（★ 实测根因）：
     *   本地存的 device_uid 可能是历史遗留的错误值（旧版用「取第一台
     *   android 设备」的粗逻辑补全，账号下有多台设备时会选错）。
     *   用错值去 listAlarms 永远返回空 → 闹钟一条都不注册 → 到点不响。
     *   所以本地有值也必须回头校验一次。
     *
     * @return true=是本账号的设备；false=明确不是（可安全替换）；
     *         null=无法判定（网络/接口异常，**不要**据此清掉本地绑定）
     */
    suspend fun isMyDevice(deviceUid: String): Boolean? = withContext(Dispatchers.IO) {
        if (deviceUid.isBlank()) return@withContext false
        try {
            // 直接打该设备的闹钟列表：能返回 code=0 即说明这是本账号可见的设备
            requestSync("GET", "/devices/$deviceUid/alarms")
            true
        } catch (e: ApiException) {
            // 404/403 → 明确不是我的设备；其余（5xx 等）→ 无法判定
            when (e.httpStatus) {
                403, 404 -> false
                else -> null
            }
        } catch (e: Exception) {
            // 网络异常 → 无法判定，保留原值
            Log.w(TAG, "校验 device_uid 失败（网络）：${e.message}")
            null
        }
    }

    /** 心跳上报（让家人看到本机在线） */
    suspend fun heartbeat(deviceUid: String) {
        withContext(Dispatchers.IO) {
            try {
                // ★ 带上机型：后端在 model 缺失时回填，用于「本机识别」的机型匹配。
                //   老版本绑定时不写 model，导致匹配永远失效、退化成「取第一台」
                //   → 本地闹钟页选错设备（真机实测根因）。心跳是设备唯一的定期
                //   上报通道，最适合补齐这个档案。
                val body = JSONObject()
                    .put("app_version", com.trustalarm.app.BuildConfig.VERSION_NAME)
                    .put("model", android.os.Build.MODEL.orEmpty())
                requestSync("POST", "/devices/$deviceUid/heartbeat", body)
            } catch (e: Exception) {
                Log.w(TAG, "心跳失败：${e.message}")
            }
        }
    }

    /** 回执：确认/拒绝闹钟 */
    suspend fun confirmAlarm(alarmUid: String, approve: Boolean) {
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("approve", approve)
            requestSync("POST", "/alarms/$alarmUid/confirm", body)
        }
    }

    // ---------- 工具 ----------

    /** 后端有的接口返回裸数组、有的包 {list,total}，统一处理 */
    /**
     * 把后端返回的列表归一成 List<JSONObject>。
     *
     * 后端有两种列表返回形态，必须都支持：
     *   · data 是数组            → /families、/devices 用这种
     *   · data = {"list": [...]} → /alarms、/local-alarms 用这种
     *
     * 为什么强调：原实现**只读 data.list**，导致 /families 与 /devices
     * 永远解析出空列表 —— 表现为「账号下没有本机设备」，
     * 进而心跳不发（显示离线）、闹钟不同步（到点不响）。
     * 这是实测踩到的真问题，不是理论情况。
     */
    private fun parseList(data: JSONObject): List<JSONObject> {
        // 形态一：data 直接是数组
        data.optJSONArray("list")?.let { arr ->
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        // 形态二：调用方把整个 data 当对象传进来后再取数组的情况，
        // 由 parseArray 处理；这里兜底再探一层常见键名。
        for (key in arrayOf("items", "data", "rows")) {
            data.optJSONArray(key)?.let { arr ->
                return (0 until arr.length()).map { arr.getJSONObject(it) }
            }
        }
        return emptyList()
    }
}

class ApiException(val code: Int, message: String, val httpStatus: Int = 0) :
    Exception(message)
