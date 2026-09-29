# Android UI 覆盖文件

`app/src/main/python/web/` 里的绝大部分文件由 `tools/sync_engine.py` 从仓库根的 `web/` 原样复制。本目录下的文件镜像同样的相对路径，在复制完成后覆盖对应副本，用来承载独立 Android 版特有的界面差异：手机端没有 MQTT / 巴法云，且运行在触摸 WebView 中。

因此修改这些界面必须同时改 `web/`（共享部分）与本目录（Android 差异），否则下一次 `sync_engine.py` 会把 Android 差异覆盖掉。`tools/sync_engine.py --check` 会校验副本是否等于“来源 + 覆盖”。

| 覆盖文件 | 与桌面版的差异 |
|---|---|
| `web/index.html` | 移除 MQTT、Bemfa 状态徽标及其 tooltip 容器 |
| `web/static/app.js` | 移除 MQTT/Bemfa 徽标更新、`/api/bemfa` 轮询与质量 tooltip 渲染 |
| `web/config.html` | 移除 MQTT 与巴法云配置卡片；新增“后台运行”开关（`window.AndroidSettings` 桥，默认开启）；补充下拉框与焦点样式 |
| `web/static/index.css` | 触摸优化：去除点击高亮、`focus-visible` 焦点环、原生下拉框外观与箭头 |
| `web/static/phone.css` | 同上（手机页面样式） |
| `web/static/locales/en.js` | 删除 MQTT/Bemfa 文案，新增 `config.keepAlive`、`config.keepAliveHint` |
| `web/static/locales/zh-CN.js` | 同上 |

`bleak/`、`android_config.py`、`android_runtime.py`、`src/__init__.py` 属于 Android 适配层，直接写在副本目录中，不参与复制，清单里标记为 `adapter`。
