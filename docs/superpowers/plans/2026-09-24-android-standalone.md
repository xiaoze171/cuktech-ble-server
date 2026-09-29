# Android Standalone Implementation Plan

> **For agentic workers:** Use subagent-driven-development for independent Android components and review. Execute through APK delivery.

**Goal:** Deliver an installable Android APK running the charger functionality on the phone with the existing UI.
**Architecture:** Embedded Python business engine and local web assets, Android native GATT transport, foreground service and WebView.
**Tech Stack:** Java, Android SDK 35, Gradle 8.13, AGP 8.13, Chaquopy 17, Python 3.11, aiohttp, SQLite.
**Spec:** `docs/superpowers/specs/2026-09-24-android-standalone-design.md`

## Global Constraints

- Independent `android/` project; no changes to desktop Python or web sources.
- UI assets copied verbatim; functional Android additions belong to adapter files.
- All charger logic runs on the phone; HTTP listens only on 127.0.0.1.
- Android 7.0+, compile/target SDK 35, package com.cuktech.mobile, arm64-v8a and x86_64.
- Preserve MQTT, Bemfa, Xiaomi cloud, charging history, CSV export, settings, protocol controls and SSE.
- Real device behavior must not be claimed verified without a device.

## Task 1: Native BLE transport

**Files:** `android/app/src/main/java/com/cuktech/mobile/GattBridge.java`, transport helpers and Java tests.
**Produces:** constructor `GattBridge(Context)`; `String scan(String mac,int timeoutMs)` (JSON or empty); `void connect(String mac,int timeoutMs)`; `boolean isConnected()`; `int getMtu()`; `byte[] read(String uuid,int timeoutMs)`; `void write(String uuid,byte[] value,boolean response,int timeoutMs)`; `void notify(String uuid,boolean enable,int timeoutMs)`; `String poll(int timeoutMs)` (JSON {uuid,data}, data hex, or empty); `void disconnect()`; `void close()`.

- [x] Write tests for notification buffer ordering, overflow, disconnect reset, byte encoding where pure helpers apply; observe red then implement.
- [x] Implement permission checks, scanner cleanup, address validation, bounded operation waits, per-connection callback state, serialized GATT operations and MTU negotiation.
- [x] Compile against Android SDK and self-review lifecycle/error paths.

## Task 2: Android application shell

**Files:** `MainActivity.java`, `ChargerService.java`, manifest, resources, shell tests.
**Consumes:** GattBridge, Python module android_runtime.
**Python contract:** `start(files_dir: str, bridge) -> str` starts once and returns local base URL; `status() -> str` JSON {running,url,error}; `stop() -> None`.

- [x] Implement native permission flow, foreground service, background initialization, readiness/error screens and original phone page in WebView.
- [x] Support Android 12 BLE permissions, older location permission, notification permission, Bluetooth enable request, back navigation, external links and CSV export.
- [x] Persist data privately; foreground notification can stop service. Recover stopped runtime on resume/user retry, do not restart on every configuration change.
- [x] Add focused checks and review manifest/service behavior.

## Task 3: On-device Python runtime and parity

**Files:** `android/app/src/main/python/android_runtime.py`, `bleak/__init__.py`, copied business modules/assets, `android/tests/`.
**Consumes:** Native GattBridge exact methods above.

- [x] Tests: async GATT notifications arrive on event loop; writes preserve bytes; timeout/cancel closes connection; unconfigured BLE stays idle; database survives restart; configuration restart preserves HTTP service; invalid token cannot overwrite working config.
- [x] Implement native binding via injected bridge and to_thread. Reuse controller crypto/state code unchanged.
- [x] Copy all engine files and all web assets, record SHA-256 parity manifest. Android-only startup overrides configuration and restart lifecycle.
- [x] Run HTTP integration against copied runtime with hardware boundary fake, then original engine tests and front-end scripts.

## Task 4: Build and delivery

**Files:** Android Gradle project, build scripts, README, APK output, unit/instrumentation support.

- [x] Install required SDK/build dependencies into local tool cache; pin compatible Python native wheels.
- [x] Build debug APK then signed release APK with retained local key, no credentials committed.
- [x] Install/launch on emulator if available; check health, screenshots and runtime logs. Inspect APK signature, permissions, ABI, assets.
- [x] Independent final review, fix important findings, rerun impacted validation, deliver APK with precise verification limits.

