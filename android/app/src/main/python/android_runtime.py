"""On-device lifecycle: Python engine + loopback HTTP, owned by Android Service."""
import asyncio
import json
import errno
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import socket
import sys
import threading
import yaml

from android_config import device_ready, prepare_config, validate_update

_lock = threading.RLock()
_thread = None
_loop = None
_shutdown = None
_ready = threading.Event()
_url = ''
_error = ''
_running = False
_log_handler = None
_LOG = logging.getLogger('cuktech_android')


def start(files_dir, bridge):
    """Start once; return only after the internal HTTP listener is ready."""
    global _thread, _error, _url
    with _lock:
        if _running:
            return _url
        if _thread is None or not _thread.is_alive():
            _ready.clear()
            _error = ''
            _url = ''
            _thread = threading.Thread(target=_run, args=(str(files_dir), bridge),
                                       name='cuktech-engine', daemon=True)
            _thread.start()
    if not _ready.wait(60):
        stop()
        raise RuntimeError('本地服务启动超时，请重试')
    with _lock:
        if _error:
            raise RuntimeError(_error)
        if not _running:
            raise RuntimeError('本地服务已停止')
        return _url


def status():
    with _lock:
        payload = {'running': _running, 'url': _url, 'error': _error}
    notice = _runtime_notice()
    if notice:
        payload['notice'] = notice
    return json.dumps(payload, ensure_ascii=False)


def _runtime_notice():
    """当前蓝牙自愈提示码（由 BLEManager 提供），供通知栏展示。"""
    try:
        import ha_server as engine
        server = getattr(engine, '_server', None)
        return getattr(getattr(server, 'ble', None), 'notice', '') or ''
    except Exception:
        return ''


def stop():
    global _thread
    with _lock:
        thread, loop, signal = _thread, _loop, _shutdown
    if loop and signal and not loop.is_closed():
        loop.call_soon_threadsafe(signal.set)
    if thread and thread is not threading.current_thread():
        thread.join(25)
        if thread.is_alive():
            raise RuntimeError('本地服务仍在停止，请稍后重试')
    with _lock:
        if _thread is thread and (thread is None or not thread.is_alive()):
            _thread = None


def _run(files_dir, bridge):
    global _error, _running, _loop, _shutdown, _log_handler
    try:
        asyncio.run(_serve(files_dir, bridge))
    except Exception as exc:
        _LOG.exception('Android engine stopped with an error')
        with _lock:
            _error = str(exc)
    finally:
        if _log_handler:
            logging.getLogger().removeHandler(_log_handler)
            _log_handler.close()
            _log_handler = None
        with _lock:
            _running = False
            _loop = None
            _shutdown = None
        _ready.set()


def _configure_logging(directory):
    global _log_handler
    root = logging.getLogger()
    if _log_handler:
        root.removeHandler(_log_handler)
        _log_handler.close()
    _log_handler = RotatingFileHandler(Path(directory) / 'app.log', maxBytes=1024*1024,
                                       backupCount=2, encoding='utf-8')
    _log_handler.setFormatter(logging.Formatter('%(asctime)s %(name)s %(levelname)s %(message)s'))
    root.addHandler(_log_handler)
    # 发布版无法 run-as 读取私有目录，同时输出到 stderr（Chaquopy 转发到 logcat）便于 adb 排查
    if not any(getattr(h, '_cuktech_logcat', False) for h in root.handlers):
        logcat = logging.StreamHandler(sys.stderr)
        logcat._cuktech_logcat = True
        logcat.setFormatter(logging.Formatter('%(name)s %(levelname)s %(message)s'))
        root.addHandler(logcat)


async def _serve(files_dir, bridge):
    global _loop, _shutdown, _url, _running
    path = prepare_config(files_dir)
    os.environ['CUKTECH_CONFIG_PATH'] = str(path)
    os.environ['CUKTECH_HISTORY_DB_PATH'] = str(Path(files_dir) / 'port_history.db')
    _configure_logging(files_dir)
    from bleak import configure_bridge
    configure_bridge(bridge)
    from aiohttp import web
    # Keep src.cuktech_ble imports intact while exposing the same source tree
    # as cuktech_ble. Use the package's importer paths (including Chaquopy's
    # asset paths), rather than assuming an ordinary filesystem installation.
    import src
    for package_path in reversed(list(src.__path__)):
        if package_path not in sys.path:
            sys.path.insert(0, package_path)
    import ha_server as engine

    # A stable loopback origin preserves the existing WebView localStorage.
    # Reserve the port before creating Server/CORS state.
    listener = _open_listener(files_dir, path)
    port = listener.getsockname()[1]
    os.environ['CUKTECH_SERVER_PORT'] = str(port)
    lifecycle = asyncio.Lock()
    stopping = asyncio.Event()
    _install_adapters(engine, bridge, files_dir, lifecycle, stopping)
    engine.reset_server()
    # The imported desktop app is a route template. Construct a fresh app on
    # each service start so aiohttp never reuses an app bound to a closed loop.
    template = engine.app
    app = web.Application(middlewares=list(template.middlewares))
    # Resources retain the original regexes, route names and method mappings.
    # canonical is a URL template and drops constraints such as {tail:.*}, so
    # rebuilding routes from it would break every nested static asset URL.
    for resource in template.router.resources():
        app.router.register_resource(resource)
    app.on_startup.append(engine.on_startup)
    app.on_shutdown.append(engine.on_shutdown)
    engine.app = app
    runner = web.AppRunner(app, access_log=None, shutdown_timeout=2)
    with _lock:
        _loop = asyncio.get_running_loop()
        _shutdown = stopping
    try:
        await runner.setup()
        await web.SockSite(runner, listener).start()
        with _lock:
            _url = f'http://127.0.0.1:{port}'
            _running = True
        _ready.set()
        await stopping.wait()
    finally:
        with _lock:
            _running = False
        async with lifecycle:
            await runner.cleanup()
            _cleanup_cloud_sessions(engine)
        listener.close()
        await asyncio.to_thread(bridge.disconnect)
        engine.reset_server()


def _open_listener(files_dir, config_path):
    port_file = Path(files_dir) / 'runtime-port.json'
    config = yaml.safe_load(config_path.read_text(encoding='utf-8'))
    preferred = config.get('server', {}).get('port', 18199)
    try:
        preferred = json.loads(port_file.read_text(encoding='utf-8'))['port']
    except (OSError, ValueError, KeyError, TypeError):
        pass
    if isinstance(preferred, bool) or not isinstance(preferred, int) or not 1 <= preferred <= 65535:
        preferred = 18199
    for port in (preferred, 0):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', port))
            listener.listen(128)
            listener.setblocking(False)
            temporary = port_file.with_suffix('.tmp')
            temporary.write_text(json.dumps({'port': listener.getsockname()[1]}), encoding='utf-8')
            os.replace(temporary, port_file)
            return listener
        except OSError as error:
            listener.close()
            busy = error.errno in (errno.EADDRINUSE, getattr(errno, 'WSAEADDRINUSE', errno.EADDRINUSE))
            # Winsock may report an occupied port as WSAEACCES instead.
            busy = busy or (os.name == 'nt' and getattr(error, 'winerror', None) == 10013)
            if port and busy:
                continue
            raise


def _cleanup_cloud_sessions(engine):
    server = engine._server
    if server is None:
        return
    for client, timer in server._xiaomi_sessions.values():
        if timer:
            timer.cancel()
        session = getattr(client, 'session', None)
        if session:
            session.close()
    server._xiaomi_sessions.clear()


def _install_adapters(engine, bridge, files_dir, lifecycle, stopping):
    """Adapt OS boundaries without changing the copied protocol/UI sources."""
    from aiohttp import web
    from ble_manager import BLEManager
    # Keep the original class, even after the Service is restarted in-process.
    base_server = getattr(engine, '_android_base_server', engine.Server)
    engine._android_base_server = base_server

    class AndroidBLEManager(BLEManager):
        async def _open_connection(self):
            # Android can connect a known public MAC without first receiving an
            # advertisement. Bound the fast path, then scan to refresh the OS's
            # device record if it fails (permissions/radio errors still surface).
            _LOG.info('Trying direct GATT connection to configured charger')
            try:
                await self._connect_controller(timeout=8.0)
                return
            except asyncio.CancelledError:
                if self.ctrl:
                    await self.ctrl.disconnect()
                    self.ctrl = None
                raise
            except Exception as error:
                _LOG.info('Direct connection failed; falling back to scan: %s', error)
                if self.ctrl:
                    try:
                        await self.ctrl.disconnect()
                    finally:
                        self.ctrl = None
                if self._stop_event.is_set():
                    raise asyncio.CancelledError
            await super()._open_connection()

        async def start(self):
            if not device_ready(self.mac, self.config.ble.token):
                self._stop_event.set()
                return
            await super().start()

        @staticmethod
        def _should_restart_process(auth_fail_count):
            # Android must recover/retry in the foreground service, never kill
            # the whole app with the desktop supervisor's os._exit strategy.
            return False

        async def _force_disconnect_bluetooth(self):
            await asyncio.to_thread(bridge.disconnect)

        async def _stop_ble_scan(self):
            # Each native scan has its own cleanup, with no BlueZ subprocess.
            return None

        async def _probe_visible_ble_devices(self):
            # 无过滤扫描：能扫到任何 BLE 设备就说明本机蓝牙栈没卡死
            return await asyncio.to_thread(bridge.probe, 4000)

        async def _reset_local_bluetooth(self):
            # 释放全部原生 GATT/扫描句柄，让适配器重新建立会话
            await asyncio.to_thread(bridge.reset)

        async def request_stop(self):
            await super().request_stop()
            await asyncio.to_thread(bridge.disconnect)

    class AndroidServer(base_server):
        # MQTT/Bemfa 恢复为用户可配置（与桌面版一致）：不再剥离 /api/config
        # 响应与保存请求中的 mqtt/bemfa 字段。
        async def handle_config_get(self, request):
            return await super().handle_config_get(request)

        async def handle_enable(self, request):
            data = await request.json()
            if data.get('enabled', True) and not device_ready(self.config.ble.mac, self.config.ble.token):
                return web.json_response({'ok': False, 'error': '请先在配置页面填写设备 MAC 和 Token'}, status=400)
            return await super().handle_enable(request)

        async def handle_config_save(self, request):
            try:
                body = await request.json()
            except (ValueError, TypeError):
                return web.json_response({'ok': False, 'error': 'invalid JSON'}, status=400)
            error = validate_update(body.get('config', {})) if isinstance(body, dict) else '配置格式不正确'
            if error:
                return web.json_response({'ok': False, 'error': error}, status=400)
            return await super().handle_config_save(request)

        async def _restart(self):
            async with lifecycle:
                if stopping.is_set():
                    return
                app = engine.app
                await engine.on_shutdown(app)
                _cleanup_cloud_sessions(engine)
                prepare_config(files_dir)
                engine.reset_server()
                await engine.on_startup(app)

    engine.BLEManager = AndroidBLEManager
    engine.Server = AndroidServer
