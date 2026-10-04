# 信任闹钟 · Android 客户端

家庭远程群组闹钟 App 的 Android 客户端。家人之间可以互相设置闹钟，
到点手机自动亮屏、全屏响铃、震动，直到用户亲手关闭。

## 功能

- 手机号注册登录，多家庭成员管理
- 绑定本机为闹钟接收设备
- 接收家人下发的远程闹钟（可配置信任免确认）
- 本地闹钟与远程闹钟统一展示
- 精确闹钟调度：到点全屏响铃 + 震动，息屏也能唤醒
- 家庭 / 设备 / 信任关系 / 查看授权管理
- 消息通知、操作日志
- App 内置 WebView 界面（与网页版同源），支持自动更新检查

## 技术栈

- Kotlin + Android SDK（minSdk 26 / targetSdk 34）
- Jetpack Compose（部分界面）+ 传统 View
- 网络：OkHttp（直接调后端 REST API，JSON）
- 界面：WebView + WebViewAssetLoader 复用网页版（`assets/web/`）
- 后端：FastAPI + SQLite/PostgreSQL（独立仓库）

## 目录结构

```
app/src/main/
  java/com/trustalarm/app/
    alarm/       闹钟调度、响铃 Activity/Service、开机自启
    bridge/      原生 ↔ 网页 JS 桥
    data/        API 客户端、数据模型、令牌存储
    service/     心跳服务（保持设备在线）
    ui/          Compose 界面（login/device/family/... 按功能分包）
  assets/web/    内嵌网页版（由 web/ 同步生成，勿手改）
  AndroidManifest.xml
app/src/test/        纯 JVM 单元测试（秒级）
app/src/androidTest/ 契约验证测试（跑真机/模拟器，打活后端）
```

## 构建

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

release 签名从 gradle 属性或环境变量读取，密钥不进仓库：

```bash
./gradlew assembleRelease \
  -PksPath=/path/to/keystore.jks \
  -PksPass=xxx -PksAlias=xxx -PksKeyPass=xxx
```

或设环境变量 `TA_KS_PATH` / `TA_KS_PASS` / `TA_KS_ALIAS` / `TA_KS_KEY_PASS`。
未提供时 release 退回 debug 签名（仅供本地测试）。

## 测试

```bash
./gradlew test              # 纯 JVM 单测
./gradlew connectedAndroidTest -Pphone=... -Ppassword=...   # 契约验证（需活后端）
```

## 安装后必做的授权

闹钟要能正常响铃，用户必须授权三项（App 内会引导）：

1. 通知权限
2. 精确闹钟（「闹钟和提醒」）
3. 电池优化白名单

国产 ROM 还需允许「自启动」。

## 说明

- `assets/web/` 是从项目 `web/` 目录同步的生成副本，请勿直接修改；
  改界面请改 `web/` 后运行 `sync_web.py`。
- 项目路径含中文时，构建需 `android.overridePathCheck=true`（已在 gradle.properties 中设置）。
