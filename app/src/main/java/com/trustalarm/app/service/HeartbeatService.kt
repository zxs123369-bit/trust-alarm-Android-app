package com.trustalarm.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.trustalarm.app.alarm.AlarmNotifier
import com.trustalarm.app.alarm.AlarmScheduler
import com.trustalarm.app.data.ApiClient
import com.trustalarm.app.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台常驻服务：**心跳上报 + 闹钟同步注册**。
 *
 * ## 为什么一个服务干两件事
 *
 * 这两件事有同一个前提（已登录 + 已绑定设备）和同一个节奏（每分钟一次），
 * 且都**必须在 App 界面不存在时也能跑**。分成两个服务只会浪费一个前台通知。
 *
 * ## 心跳为什么需要
 *   后端把「120 秒无心跳」判定为设备离线（PRD）。
 *   设备离线时，家人给这台设备下发闹钟会走「通道失败 → 重试队列」，
 *   而不是直接送达。保持心跳 = 保证家人的闹钟能顺利送达，
 *   也保证家人在列表里看到的是「在线」。
 *
 * ## 闹钟同步为什么需要（★ 用户实测反馈的缺陷）
 *
 *   改造为 WebView 壳之后，闹钟注册一度**只在打开「本地闹钟」网页时才发生**。
 *   后果：
 *     · 家人设闹钟时，若设备主人没打开过那个页面 → 系统里根本没这个闹钟 → 不响
 *     · 打开过之后再新增的闹钟 → 不会重新注册 → 不响
 *     · 清了后台 → 网页定时器没了，系统闹钟又没注册 → 双重不响
 *
 *   所以闹钟同步必须由**原生后台循环**负责，不能依赖任何网页页面。
 *   这也是当初做原生壳的根本理由 —— 网页做不到「后台活着」。
 *
 * ## 为什么用前台服务
 *   Android 8+ 后台服务会被限制/杀死。前台服务带常驻通知，
 *   可以长期存活（用户可见，也符合"设备在线"的语义）。
 *
 * 周期 60 秒（后端离线阈值 120 秒，留出容错空间）。
 */
class HeartbeatService : Service() {

    companion object {
        private const val TAG = "HeartbeatService"
        private const val INTERVAL_MS = 60_000L
        private const val NOTIF_ID = 1001
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        /**
         * ★ 闹钟同步的「快周期」（毫秒）。
         *
         * 为什么不能只有 60 秒（用户实测「刚设的闹钟不响」的根因）：
         *   实测：20:45:36 设了 20:46:00 的闹钟 —— 只提前 23 秒。
         *   而同步周期是 60 秒。若创建时恰好错过上一轮，下一轮到达时
         *   闹钟时间已过，`shouldSchedule` 判定「已过期」→ **永不注册** →
         *   到点不响。用户看到的就是「刚设的闹钟没响」。
         *
         *   家人之间互相设闹钟时，「设 1~2 分钟后」是极高频用法，
         *   60 秒的粒度必然漏掉一部分。
         *
         * 现在：心跳仍按 60 秒（后端离线阈值 120 秒，够用），
         *      但**闹钟同步按 10 秒**跑 —— 保证「1 分钟后」的闹钟也能被
         *      及时注册。代价是每 10 秒一次本地列表拉取（局域网、开销很小）。
         */
        private const val SYNC_INTERVAL_MS = 10_000L

        /**
         * 启动服务（幂等）。
         *
         * 多处都会调：绑定设备成功后、界面进入时、开机广播后。
         * 重复调用安全 —— onStartCommand 里 startLoop() 有 isActive 保护。
         */
        fun start(context: Context) {
            val i = Intent(context, HeartbeatService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, HeartbeatService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        /**
         * 立刻同步一次闹钟（不等下一个周期）。
         *
         * 用途：收到「有新闹钟」的消息、或用户手动刷新时，
         * 希望马上就注册，而不是最多等 60 秒。
         */
        fun syncNow(context: Context) {
            syncJob?.cancel()
            syncJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val api = ApiClient(context.applicationContext)
                    val store = TokenStore(context.applicationContext)
                    // 与主循环同样必须先恢复会话：本方法可能在任何时机被网页调用，
                    // 那时进程可能是刚起来的，accessToken 还是 null。
                    //
                    // 注意：这里每次都会 new 一个 ApiClient（access 必然为 null），
                    // 所以不能用 hasValidAccess() 短路 —— 那会永远为 false。
                    // 本方法是**事件驱动**（网页显式调用），不是周期循环，不存在
                    // 「每 10 秒轮换一次」的问题；但仍加一次空值保护，避免
                    // refresh_token 缺失时白跑一次刷新。
                    if (!store.hasRefreshToken()) {
                        Log.w(TAG, "立即同步：未登录，跳过")
                        return@launch
                    }
                    if (!api.restoreSession()) {
                        Log.w(TAG, "立即同步：会话恢复失败，跳过")
                        return@launch
                    }
                    syncAlarms(context.applicationContext, api, store)
                } catch (e: Exception) {
                    Log.w(TAG, "立即同步失败：${e.message}")
                }
            }
        }

        private var syncJob: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val store = TokenStore(this)
                val name = store.getDeviceName().ifBlank { "本机" }
                // 必须在 5 秒内调用 startForeground，否则 ANR
                startForeground(NOTIF_ID, AlarmNotifier.heartbeatNotification(this, name))
                startLoop()
            }
        }
        // 被系统杀死后自动重启 —— 心跳与闹钟同步都不能断
        return START_STICKY
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        // ★ 顺带挂上周期性重排链（幂等）。
        //   为什么在这里也做一次：服务被拉起有多种时机（绑定设备、进入页面、
        //   开机、START_STICKY 重启），任何一次都可能是「应用刚从 stopped
        //   状态恢复」的时刻 —— 此时补一条周期链，闹钟才不会静默丢失。
        AlarmScheduler.scheduleRearm(applicationContext)
        loop = scope.launch {
            val api = ApiClient(applicationContext)
            val store = TokenStore(applicationContext)
            // 上次上报心跳的时间戳 —— 用于「同步快跑、心跳慢跑」的双速循环
            var lastHeartbeatAt = 0L
            while (isActive) {
                try {
                    if (!store.hasRefreshToken()) {
                        Log.i(TAG, "未登录，跳过本轮")
                    } else {
                        // ★ 关键：只有「内存里没有可用 access」时才去恢复会话。
                        //
                        // 为什么必须有恢复这一步（用户实测「闹钟没有注册到手机本地」的根因）：
                        //   ApiClient 的 accessToken 是**内存字段**，进程重启后即为 null。
                        //   本服务常由 START_STICKY 重启、或开机由 BootReceiver 拉起，
                        //   那都是**新进程**，accessToken 必然是 null。
                        //
                        //   原实现只查 hasRefreshToken() 就直接调业务接口，靠
                        //   requestSync 的「401 自动刷新」兜底 —— 而那条兜底是危险的：
                        //   刷新令牌是**一次性轮换 + 复用检测**（token_service.rotate），
                        //   一旦本地这把与服务端最新值不一致，凭它去刷新会被判定为
                        //   「令牌泄露」，后端**吊销该账号全部令牌**。
                        //
                        //   ★ 但「每轮都恢复」是另一个极端、同样致命（真机实测根因）：
                        //   本循环周期 10 秒，若每轮都调 restoreSession()（=完整轮换），
                        //   refresh token 就每 10 秒被换一次 —— 实测后端 4 分钟收到 70 次
                        //   /auth/refresh（heartbeat 仅 11 次）。网页层 localStorage 里
                        //   那份会迅速变成「已吊销令牌」，两边一竞态就触发复用检测 →
                        //   全账号吊销 → 永久 401 → 心跳停（显示离线）+ 闹钟不注册。
                        //
                        //   所以判据用 hasValidAccess()：access 为空 ⇔ 本进程还没拿到
                        //   令牌（进程刚起/刚开机）→ 才恢复一次；拿到后就用内存里的
                        //   access，过期由 requestSync 的 401 单次重试兜底。
                        if (!api.hasValidAccess() && !api.restoreSession()) {
                            Log.w(TAG, "会话恢复失败（令牌已失效），本轮跳过。"
                                + "需用户在 App 内重新登录 —— 在此之前闹钟无法注册。")
                        } else {
                            // device_uid 可能只在「绑定设备」流程里写过；若缺失
                            // （设备早已绑定 / 在网页版绑的），这里自动补全，
                            // 否则心跳与闹钟同步都会永久跳过。
                            //
                            // ★ 还有第二种坏情况：**本地存了一个错的 device_uid**。
                            //   历史实现用「取第一台 android 设备」的粗逻辑补全过，
                            //   若账号名下有多台设备，就可能把某个僵尸设备的 uid
                            //   写进本地，此后每轮都同步那台不存在的设备
                            //   （症状：日志一直是「列表 0 条」，闹钟永不注册）。
                            //   所以本地有值时也要校验它确实属于本账号、且是本机。
                            val deviceUid = resolveDeviceUid(api, store)
                            if (deviceUid.isNotBlank()) {
                                // 1) 心跳按 60 秒（后端离线阈值 120 秒，足够）
                                val now = System.currentTimeMillis()
                                if (now - lastHeartbeatAt >= INTERVAL_MS) {
                                    api.heartbeat(deviceUid)
                                    lastHeartbeatAt = now
                                }
                                // 2) ★ 闹钟同步按 10 秒（快周期）
                                //    原因见 SYNC_INTERVAL_MS 的注释：60 秒会漏掉
                                //    「设 1 分钟后响」这种高频用法。
                                syncAlarms(applicationContext, api, store)
                            } else {
                                Log.i(TAG, "账号下没有本机设备，跳过（需先在网页/App 绑定设备）")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 单轮失败不能让循环退出 —— 下一轮继续
                    Log.w(TAG, "本轮异常：${e.message}")
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * 确定本机 device_uid —— **先校验本地值，不可信才重新推断**。
 *
 * 为什么不能直接用本地存的值（★ 实测根因）：
 *   历史版本的兜底逻辑是「取第一台 owner_is_me 的 android 设备」。
 *   一个账号名下如果有**多台** android 设备（比如换过手机、网页版建过），
 *   它可能把**僵尸设备**的 uid 写进本地。此后每轮心跳与闹钟同步都对着
 *   那台不存在的设备做：
 *     · 家人列表里看不到真机在线
 *     · listAlarms(僵尸设备) 永远空 → **闹钟一条都不注册** → 到点不响
 *   实测症状就是日志恒为「闹钟同步：列表 0 条」，而后端明明有闹钟数据。
 *
 * 所以：本地有值也要验证它「确实属于我」；不属于就重新推断并覆盖。
 * 验证失败的常见原因是网络抖动 —— 那种情况本次**保留原值**（不要因为
 * 一次网络错误就把用户的绑定抹掉），只在**确认不属于我**时才替换。
 */
private suspend fun resolveDeviceUid(api: ApiClient, store: com.trustalarm.app.data.TokenStore): String {
    val saved = store.getDeviceUid()
    if (saved.isNotBlank()) {
        when (api.isMyDevice(saved)) {
            true -> return saved
            false -> {
                // 明确不属于本账号 → 本地值已失效，重新推断
                Log.w(
                    "HeartbeatService",
                    "本地 device_uid ${saved.take(8)} 不属于当前账号，重新推断",
                )
            }
            null -> {
                // 无法判定（网络/接口异常）→ 保持原值，避免误清绑定
                Log.w("HeartbeatService", "无法校验 device_uid，暂用本地值 ${saved.take(8)}")
                return saved
            }
        }
    }

    val found = try {
        api.findMyDeviceUid()
    } catch (e: Exception) {
        Log.w("HeartbeatService", "查询本机设备失败：${e.message}")
        ""
    }
    if (found.isNotBlank()) {
        store.saveDevice(found, store.getDeviceName().ifBlank { "我的手机" })
        Log.i("HeartbeatService", "已补全 device_uid = ${found.take(8)}")
    } else {
        Log.w("HeartbeatService", "推断本机设备失败（账号下可能没有 android 设备）")
    }
    return found
}

/**
 * 拉取本机闹钟列表并**全量注册**到系统 AlarmManager。
 *
 * 为什么是「全量」而不是「增量」：
 *   注册本身是幂等的（同一 uid 的 PendingIntent requestCode 固定，
 *   否则重复 setExact 只是覆盖），所以全量重放既简单又不会漏。
 *   增量则需要维护本地 diff，反而容易出错。
 *
 * 已过期的闹钟由 AlarmScheduler 自行跳过（它内部会判 triggerAt <= now）。
 *
 * ## device_uid 的确定（★ 曾经的重复实现是隐蔽 bug）
 *
 * 这里**不再自己解析 device_uid**，而是复用 [resolveDeviceUid]。
 *
 * 为什么强调：早先本函数内部自带一份「本地为空就问后端」的逻辑，
 * 与主循环的解析逻辑**重复**。当主循环已把正确的 device_uid 落盘、
 * 而本函数又用旧逻辑覆盖时，两边会互相打架 —— 实测表现为
 * 「主循环修好了 device_uid，但同步仍对着僵尸设备，列表恒为 0 条」。
 * 同一件事只能有一个实现。
 */
private suspend fun syncAlarms(context: Context, api: ApiClient, store: TokenStore) {
    val deviceUid = resolveDeviceUid(api, store)
    if (deviceUid.isBlank()) {
        // 显式记一条：device_uid 拿不到 = 永远不会注册，必须可见
        Log.w("HeartbeatService", "闹钟同步跳过：拿不到 device_uid")
        return
    }

    val alarms = try {
        api.listAlarms(deviceUid)
    } catch (e: Exception) {
        // 这条以前只有 warn 且很短，导致「闹钟没注册」在日志里几乎看不出原因。
        // 拉不到列表 = 一个都不会注册，必须把原因写清楚。
        Log.w("HeartbeatService", "拉取闹钟失败，本轮不注册任何闹钟：${e.message}")
        return
    }

    var registered = 0
    var skippedPast = 0
    var skippedUnconfirmed = 0
    var skippedFired = 0
    alarms.forEach { item ->
        // ★ 已经响过的绝不重复注册。
        //   为什么必须显式判：90 秒宽限期让「刚过期」的闹钟仍可注册，
        //   而 AlarmReceiver 到点后会清掉注册记录 —— 若不额外记「已触发」，
        //   下一轮同步（10 秒一次）会把它再注册一遍，导致重复响铃。
        //   实测：同一闹钟在 20:54:44 与 20:54:54 各响一次。
        if (AlarmScheduler.isFired(context, item.uid)) {
            skippedFired++
            return@forEach
        }
        if (item.shouldSchedule) {
            if (AlarmScheduler.schedule(context, item)) registered++
        } else if (item.triggerAtMillis <= System.currentTimeMillis()) {
            skippedPast++
        } else {
            skippedUnconfirmed++
        }
    }
    // 顺手清理过旧的触发记录，避免表无限增长
    AlarmScheduler.pruneFired(context)
    // 每轮都汇报总数，便于确认「到底有没有在注册」——
    // 原实现只在 registered > 0 时打日志，于是「一个都没注册」时完全静默，
    // 这正是本问题长期没被发现的原因。
    Log.i(
        "HeartbeatService",
        "闹钟同步：列表 ${alarms.size} 条 → 已注册 $registered，"
            + "已过期跳过 $skippedPast，未确认跳过 $skippedUnconfirmed，"
            + "已响过跳过 $skippedFired",
    )
}
