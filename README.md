# 信任闹钟 · Android 客户端

家庭远程群组闹钟 App 的 Android 客户端。家人之间可以互相设置闹钟，
到点手机自动亮屏、全屏响铃、震动，直到用户亲手关闭。

> 本项目为**客户端（Android App）**。后端服务（FastAPI）不在本仓库。

---

## 一、这是什么

普通闹钟只能自己设、自己关。信任闹钟让**家人**也能给你的手机设闹钟：

- 儿子在城里上班，给老家爸妈设「早上 6:30 起床」「中午 12:00 吃药」
- 妈妈给上学的孩子设「该写作业了」
- 一家人互相提醒，谁忘了设，家人在自己手机上就能帮忙设上

到点时，被设闹钟的手机会**自动亮屏 + 全屏响铃 + 震动**，直到用户亲手点
「关闭闹钟」才会停。

---

## 二、功能详情

### 账号与家庭
- 手机号注册 / 登录（8–64 位，含字母和数字）
- JWT 令牌鉴权，支持令牌自动刷新、多设备登录会话管理、远程踢下线
- 创建家庭、邀请成员、退出 / 移除成员
- 家庭角色：群主（owner）/ 普通成员（member）

### 设备
- 绑定本机为闹钟接收设备
- 设备心跳保活（保持在线状态）
- 设备型号上报、设备信息查看

### 闹钟
- **本地闹钟**：自己给自己设，直接走到点响铃
- **远程闹钟**：家人下发到你的设备，按「信任开关」决定是否需要确认
  - 信任开关打开 → 免确认直接生效
  - 未打开 → 推送确认弹窗，需本人点「同意」
- 精确闹钟调度（`AlarmManager` + 精确闹钟权限）
- 到点全屏响铃界面（返回键无效，必须点关闭）、震动、通知
- 息屏 / 后台 / 重启后仍能唤醒（`BootReceiver` 重新注册）
- 同一设备 1 分钟最多下发 2 条（防骚扰限流）

### 权限模型（家人之间能看到什么）
- **信任 ≠ 查看**：设为信任成员只代表能给你设闹钟，不代表能翻看你的内容
- 查看他人内容需对方**单独授权**
- 闹钟只有创建人和群主能修改 / 删除

### 消息与日志
- 应用内消息通知（家庭邀请、闹钟下发结果等）
- 操作日志、家庭日志

### 更新
- App 内检查更新，下载新版本 APK 并安装

### 技术特点
- Kotlin + Jetpack Compose（部分界面）+ 传统 View
- WebView 复用网页版界面（与网页版同源，永不漂移）
- OkHttp 直接调 REST API，JSON 交互
- 心跳保活服务，App 在后台也能接收远程闹钟

---

## 三、下载与更新服务器

App 通过后端接口检查版本、下载更新包。

**服务器地址**

```
http://101.133.229.168
```

**接口**

| 用途 | 方法 | 地址 |
| --- | --- | --- |
| 检查最新版本 | GET | `http://101.133.229.168/api/v1/app/version` |
| 下载最新 APK | GET | `http://101.133.229.168/api/v1/app/download` |
| 后端服务地址（业务 API） | — | `http://101.133.229.168/api/v1` |

`/api/v1/app/version` 返回示例：

```json
{
  "version_code": 30,
  "version_name": "1.2.7",
  "notes": "本次更新说明……",
  "file_name": "trustalarm-1.2.7-30.apk",
  "size": 14107807,
  "sha256": "25c497c024106b18aac625d45c59e93285712273ec2d85d2f4af18aa5a79283a",
  "download_url": "/api/v1/app/download"
}
```

App 启动或用户手动触发时，用本机 `versionCode` 与接口返回的 `version_code`
比对，有新版则提示更新。

---

## 四、构建

前置：JDK 17、Android SDK 34。

```bash
# 1) 配置 SDK 路径（本机专属，不进仓库）
echo 'sdk.dir=/path/to/android-sdk' > local.properties

# 2) 同步网页版到 assets（界面源码在项目 web/ 目录）
python sync_web.py

# 3) 构建
./gradlew assembleDebug     # debug 包，默认后端 http://10.0.2.2:8848
./gradlew assembleRelease   # release 包，默认后端 http://101.133.229.168
```

指定后端地址覆盖默认值：

```bash
./gradlew assembleRelease -PapiBase=http://your-host:port
```

### 签名

release 签名从 gradle 属性或环境变量读取，**密钥不进仓库**：

```bash
./gradlew assembleRelease \
  -PksPath=/path/to/keystore.jks \
  -PksPass=xxx -PksAlias=xxx -PksKeyPass=xxx
```

或设环境变量 `TA_KS_PATH` / `TA_KS_PASS` / `TA_KS_ALIAS` / `TA_KS_KEY_PASS`。
未提供时 release 退回 debug 签名（仅供本地测试）。

---

## 五、测试

```bash
./gradlew test              # 纯 JVM 单元测试
./gradlew connectedAndroidTest -Pphone=... -Ppassword=...   # 契约验证（需活后端）
```

---

## 六、安装后必做的授权

闹钟要能正常响铃，用户必须授权三项（App 内会引导）：

1. **通知权限**
2. **精确闹钟**（设置 → 应用 → 信任闹钟 → 闹钟和提醒 → 允许）
3. **电池优化白名单**（设置 → 应用 → 信任闹钟 → 电池 → 不受限制）

国产 ROM（小米 / 华为 / OPPO / vivo）还需额外允许「自启动」。

---

## 七、演示账号说明

App 登录页提供**演示账号快捷按钮**，用于功能演示与联调测试：

| 手机号 | 角色 |
| --- | --- |
| 13700137000 | 群主（爸爸） |
| 13800138000 | 成员（儿子） |
| 13900139000 | 成员（妈妈） |

> ⚠️ **这些演示账号及其口令是公开的测试数据，仅存在于演示 / 开发环境，
> 与任何真实用户账号、服务器登录密码、签名密钥完全无关。**
> 生产环境请勿使用演示账号，正式用户请自行注册。

---

## 八、目录结构

```
app/src/main/
  java/com/trustalarm/app/
    alarm/       闹钟调度、响铃 Activity/Service、开机自启
    bridge/      原生 ↔ 网页 JS 桥
    data/        API 客户端、数据模型、令牌存储
    service/     心跳服务（保持设备在线）
    ui/          Compose 界面（login/device/family/... 按功能分包）
    update/      版本检查与 APK 下载
  assets/web/    内嵌网页版（由 web/ 同步生成，勿手改）
  AndroidManifest.xml
app/src/test/        纯 JVM 单元测试（秒级）
app/src/androidTest/ 契约验证测试（跑真机/模拟器，打活后端）
```

---

## 九、说明

- `assets/web/` 是从项目 `web/` 目录同步的生成副本，请勿直接修改；
  改界面请改 `web/` 后运行 `sync_web.py`。
- 项目路径含中文时，构建需 `android.overridePathCheck=true`（已在 `gradle.properties` 中设置）。
- 本仓库**不包含**：后端源码、签名密钥（keystore）、本机配置（local.properties）。

## 十、License

未指定。
