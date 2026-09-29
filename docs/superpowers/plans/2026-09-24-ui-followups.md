# UI follow-ups requested after on-device testing

Requests raised while testing the APK on a real phone (vivo V2231A, Android 16, user build):

1. 默认白色背景 - the phone and config pages must open in the light (white) theme by default, and the choice must stick.
2. BLE 设备增加提示，引导用户点击获取二维码 - the BLE device section must make the QR/credential flow discoverable.
3. 功率曲线时间可修改 - the phone power chart must expose its time window instead of a fixed live window.

## Root causes found

- `web/static/phone.js` hard-codes `isDark = true` and never persists it; `web/config.html` defaults to dark and only honours a saved `light`; `web/static/app.js` defaults to `ha-dark`. The two phone pages therefore disagree and always restart dark.
- `--accent` is used by `web/config.html` and `web/static/phone.css` but is never defined for those pages, so the QR and save buttons render with no background and the QR entry point is nearly invisible.
- The phone chart is a client-only buffer: `PHONE_CHART_MAX = 150` points at a 2 s interval (5 minutes shown, 10 minutes retained) with no UI control. The desktop page instead selects 30/60/90/120/1440 minutes and reads `/api/chart`.

## Changes

- Light becomes the default theme on the phone page, the config page and the desktop page, and is applied on load before the first render. The two mobile pages share `cuktech-config-theme` so they always agree; the desktop page keeps its own pre-existing `cuktech-theme` key.
- The Android native shell switches to a light app theme and window so the frame matches the white page.
- `--accent` and `--accent-rgb` are defined for the phone pages, restoring the QR/save buttons and the protocol-button styling.
- The BLE device card gains a prominent hint that opens the Xiaomi QR modal.
- The power chart gains a range selector: live (existing window) plus 30/60/120/1440 minutes served from `/api/chart`, persisted under `cuktech-phone-chart-range`.

Desktop Python/engine code is untouched; only web assets and the Android adapter change.
