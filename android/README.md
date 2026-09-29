# CUKTECH 独立 Android 版

最新交付 APK 统一放在项目根目录：`../Cuktech-1.1.0-android.apk`。蓝牙恢复与功率曲线的本轮验证见 [VALIDATION-2026-09-25.md](VALIDATION-2026-09-25.md)。Gradle 构建输出目录仅用于构建。

手机直接连接充电器，不需要电脑、NAS 或另行部署 BLE Server。

应用保留原项目的手机界面、其它管理页面、主题、语言、充电曲线、协议开关、端口控制、倒计时、设置、充电历史、电量统计及小米云设备凭据获取。Android 版不提供 MQTT 和巴法云设置。原业务引擎通过 Chaquopy 在应用进程内运行，蓝牙传输使用 Android BluetoothGatt，数据写入应用私有存储。

## 安装和使用

1. 安装项目根目录的 `Cuktech-1.1.0-android.apk`，允许附近设备/蓝牙权限；Android 11 及更早版本需要定位权限和开启系统定位以扫描 BLE。
2. 允许通知，便于查看后台连接状态及停止服务。
3. 从原界面左上角进入配置，输入 MAC 和 Token（24 位十六进制），或使用小米云扫码获取。BLE Key 可同时保存。
4. 保存配置后本地业务服务会自动重启，手机连接充电器。
5. 配置页的“后台运行”默认开启，可保持前台服务并在手机重启后恢复；关闭后离开应用即停止服务，回到应用会自动恢复。小米云凭据获取需要互联网；直接蓝牙控制不依赖互联网。

返回桌面后，启用后台运行时前台服务继续维持连接和记录。通知中的“停止”会断开蓝牙并停止记录；重新打开应用可恢复服务。卸载应用会删除本机配置和历史数据库。

Android 7.0+；APK 包含 arm64-v8a 和 x86_64。真实充电器的连接稳定性、固件差异和手机厂商后台限制，需要结合目标手机实测。构建成功及模拟器验证不能代替蓝牙硬件验证。

## 工程结构

- `app/src/main/java/com/cuktech/mobile/`：权限、WebView、前台服务、原生 GATT。
- `app/src/main/python/`：独立业务引擎副本、Android 生命周期/配置适配、bleak 兼容传输层。
- `app/src/main/python/web/`：原网页及全部本地资源。
- `overlays/`：Android 专属界面覆盖文件，镜像副本路径，在复制之后应用。
- `engine-manifest.json`：每个文件的复制来源、SHA-256，以及它是否为覆盖文件或适配层。
- `tools/sync_engine.py`：显式刷新副本并重新应用覆盖，不复制电脑上的配置和设备凭据。
- `tools/verify_ui_i18n.py`：检查副本界面用到的语言键是否齐全、有无冗余与硬编码中文。
- `tests/`、`app/src/test/`：Android 适配层与原生辅助组件测试。

仓库的上游自动同步已排除 `android/`，避免覆盖此独立版本。需要升级原业务引擎时，主动运行 `python android/tools/sync_engine.py` 并重新验证和打包。注意 `web/` 中同时存在 Android 覆盖的界面（去掉 MQTT/巴法云、触摸样式、后台运行开关）时，改动要落在 `web/` 与 `android/overlays/` 两处；`python android/tools/sync_engine.py --check` 可校验副本等于“来源 + 覆盖”。

## 构建

需要 JDK 17 或 21、Python 3.11、Android SDK Platform 35 / Build Tools 35.0.0。Gradle Wrapper 固定 8.13；AGP 8.13.0，Chaquopy 17.0.0。

在 `android/local.properties` 指定本机路径（使用正斜杠）：

```properties
sdk.dir=C:/Android/Sdk
python.executable=C:/Python311/python.exe
```

在 `android/` 执行：

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

发布 APK 使用本地 `signing.properties`：

```properties
storeFile=../.codex/cuktech-release.jks
storePassword=your-local-password
keyAlias=cuktech
keyPassword=your-local-password
```

这些文件已加入忽略规则。保存生成 APK 时使用的密钥和密码，后续升级必须继续使用同一签名。

```powershell
.\gradlew.bat :app:assembleRelease
```

未提供签名配置时 release 构建是未签名产物，不能直接安装。交付的 APK 已完成本地签名。

## 验证

在仓库根目录使用已安装原项目运行依赖的虚拟环境 Python（隔离集成测试不会读取用户级 site-packages）：

```powershell
python -m unittest discover -s android/tests -v
python android/tools/sync_engine.py --check
python android/tools/verify_ui_i18n.py
node tests/js/i18n_runtime_test.js
node tests/js/smoke_test.js
```

首次构建需要下载 Android Python 原生轮子；版本固定以匹配可用的 Android ABI。桌面项目仍可独立运行，Android 修改不影响其原文件。

本应用默认只在手机回环地址监听内部 HTTP，不对局域网开放。不要把界面上的服务器端口当作电脑服务地址使用。

外壳只在“手机界面/配置页”之间导航（服务端 `/index.html` 返回 404，仪表盘由 `/` 按 User-Agent 分发）。服务端静态资源带 7 天 `immutable` 缓存，原生外壳在每次安装后清理一次 WebView 资源缓存，保证覆盖安装不会继续显示旧界面。
