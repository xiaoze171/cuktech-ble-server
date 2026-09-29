# Android 验证记录（2026-09-24）

交付文件：`dist/Cuktech-1.1.0-android.apk`

- 应用 ID：`com.cuktech.mobile`
- 版本：`1.1.0-android.1` / versionCode 1
- 最低 Android 7.0（API 24）；target/compile API 35
- ABI：arm64-v8a、x86_64
- 大小：29,880,686 字节（约 28.5 MiB）
- SHA-256：`5d14f16b956f8ba02b247604dc75a2e90c5633ac325dbe76f7fc178e9522edb8`
- APK Signature Scheme v2 验证通过，RSA 3072 位签名。
- 签名证书 SHA-256：`353a112b3ffcb41bcbc3f481cbb4ae84074c46f141aa94fdf315fb02a00235f7`（与上一版同一本地密钥）
- 发布清单未启用 debuggable；APK 未打包本机配置、数据库或签名密钥。

调试说明：测试模拟器为 `ro.debuggable=1` 的系统镜像，其 WebView 124 系统实现强制开启远程调试，即使应用明确传入 `false` 也不关闭。已核对对应 WebView 源码；这是测试镜像行为。应用本身只在 `FLAG_DEBUGGABLE` 下主动开启调试，发布包未设置该标志。

## 自动验证

| 验证 | 结果 |
|---|---|
| Gradle debug 编译与 Java 单元测试 | 构建成功，18/18 测试通过（GattOperationGate 3、KeepAlivePreferences 2、LocalUrlPolicy 5、NotificationBuffer 5、RuntimeGeneration 3） |
| Gradle release / lintVital / 签名 | 构建与关键 lint 检查通过，apksigner v2 校验成功 |
| Python Android 适配测试 | 12/12 通过（传输 5、配置 5、集成 2；集成用例以 `python -I` 在仓库外启动） |
| 原业务引擎测试（针对 Android 副本） | 279 通过，1 项原有 Windows 端口默认值断言失败 |
| 原前端脚本测试 | 24 项国际化 + 7 项页面冒烟通过 |
| Android 界面语言包检查 | 193 个键两套语言一致；178 个在用键全部有定义，无冗余键，无硬编码中文（`android/tools/verify_ui_i18n.py`） |
| 副本与覆盖一致性 | 75 个文件等于“来源 + 7 个 Android 覆盖 + 4 个适配层”（`android/tools/sync_engine.py --check`） |
| 打包内容比对 | APK 内 `assets/chaquopy/app.imy` 的 75 个业务/界面文件与副本 SHA-256 完全一致，其余为 Chaquopy 编译的 `.pyc` |
| 独立代码评审 | 两项包路径/静态路由问题已修复并复核，无未解决的重要发现 |

原有失败：`TestServerConfig::test_default_values` 固定期待 `8199`，原代码在 Windows 默认返回 `18199`。未修改原业务代码或测试来掩盖这一差异。

环境说明：本机 `config.yaml` 是 UTF-8 且含中文，而 `config.py` 用平台默认编码（本机 GBK）打开它，因此直接运行会在 `tests/test_config.py` 额外失败 4 项。以 `PYTHONUTF8=1` 运行时即为 279 通过 + 1 项原有失败。这是原仓库既有的编码问题与本机配置内容所致，不属于本次改动。

## 本轮改动（去云端界面、触摸样式、后台运行）

- 界面移除 MQTT 与巴法云：`index.html` 去掉两个状态徽标与 tooltip 容器；`app.js` 去掉徽标更新、`/api/bemfa` 轮询与质量浮层；`config.html` 去掉两段配置卡片；两套语言包删除 19 个相关键。`/api/config` 仍返回完整配置，但适配层会剥离 `mqtt`/`bemfa`，且配置保存时忽略这两段（`android_config.prepare_config` 强制关闭）。
- 触摸样式：去掉点击高亮，补 `focus-visible` 焦点环与原生下拉框外观（两套 CSS 与配置页）。
- 新增“后台运行”开关（默认开启）：配置页通过 `window.AndroidSettings` 桥读写 SharedPreferences；关闭后离开应用即停止前台服务，返回应用时自动恢复；开启时服务保持 `connectedDevice` 前台状态，并在 `BOOT_COMPLETED` 后自动恢复（`BootReceiver`，需先授予蓝牙权限）。
- 语言包只保留 Android 实际使用的键：178 个在用键全部有定义，无冗余键。

## 模拟器实测（Android 15 / API 35，x86_64 google_apis）

安装签名 release 包（覆盖安装上一版）后：

- 冷启动进入手机界面，`/api/health` 返回 200，`components.ble=false`（未配置设备，不扫描、不伪造连接）。
- 手机界面与配置页均无 MQTT/巴法云文字或控件；桌面版仪表盘（桌面 UA 请求 `/`）也不再渲染这两个徽标。
- 配置页“后台运行”开关默认开启，文案随语言包正确显示，勾选状态与原生 `AndroidSettings` 读取一致。
- 关闭开关：按 Home 离开后前台服务停止，`/api/health` 不可达；重新回到应用自动恢复，health 恢复 200。
- 开启开关：按 Home 离开后 `ChargerService` 仍为 `connectedDevice` 前台状态（`types=0x00000010`），health 保持 200。
- 开关状态跨进程重启保留（重新进入配置页仍为关闭）。
- 重启模拟器：开关开启时 `BootReceiver` 自动拉起服务（intent `com.cuktech.mobile.START`，未手动打开应用），health 200；开关关闭时重启后没有 `ChargerService`。
- 证据截图留档于 `.codex/`（已被 git 排除）：`emu-main.png`、`emu-config-final.png`。

## 升级路径缺陷（已修复）

覆盖安装后，WebView 最初仍渲染旧界面：`/static/locales/en.js?v=3` 返回上一版内容（10919 字节、含 `mqttSection`），页面显示未翻译的 `config.keepAlive` 原文；`/static/phone.css?v=3` 同样是旧样式。原因是服务端对静态资源返回 `Cache-Control: public, max-age=604800, immutable`，而升级后资源内容变了、URL 没变，WebView 直接把旧响应当作 7 天内有效。

修复：原生外壳在每次安装后（用 `PackageInfo.lastUpdateTime` 判定）清理一次 WebView 资源缓存，不再需要为每个资源手动改版本号；不影响 localStorage 与站点数据。修复后同一 URL 取到的是新内容（9945 字节、含 `keepAlive`），配置页文案正常。桌面版仍保留原有长缓存策略。

## 真机实测（vivo V2231A，Android 16 / API 36）

上一版（12:33 构建，SHA-256 `230db068…`）实测记录仍然有效：

- 签名 release APK 通过系统安装校验，冷启动进入主界面，无白屏或闪烁。
- 页面默认以浅色（白色）主题打开，状态栏与导航栏同为白色，系统图标为深色。
- 主界面与配置页面的图片、语言包全部加载成功。
- 状态、曲线、配置、健康接口在真机上均返回 HTTP 200；SSE 收到 `init` 消息。
- 经 `adb forward` 回环访问引擎端口校验：`/api/health` 返回 `{"ok":true,...,"components":{"ble":false,...}}`。
- 未连接真实充电器，功率、电压、电流为空闲占位（`--W idle`），充电记录为空。

上述界面调整（浅色默认、二维码提示、功率曲线时间范围）的截图：`vivo-new-phone.png`、`vivo-new-config-top.png`、`vivo-qr-modal.png`、`vivo-chart-view.png`、`vivo-default-range.png`。

本轮 APK 尚未在真机上安装；去云端界面、触摸样式与“后台运行”只验证到模拟器。

## 尚未完成的账户/协议验证

真机已安装运行，但尚未连接真实充电器，也没有输入小米云、MQTT 或巴法云账户。因此真实 MiOT 认证、端口控制、实际充电数据、后台熄屏稳定性及云端联动仍未被证明。原协议和云逻辑已完整保留；需要在目标手机上配置设备后实测。

接口可达性补充：原路由只注册了 `/`、`/phone.html`、`/config.html` 与 `/static/*`，`/index.html` 直接请求返回 404（桌面版同样如此，仪表盘由 `/` 按 User-Agent 分发）。Android 外壳因此固定打开手机界面。
