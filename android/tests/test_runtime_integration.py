"""Run the real on-device engine and HTTP routes with only BLE hardware absent."""
import json
import asyncio
from pathlib import Path
import subprocess
import socket
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

PYTHON_DIR = Path(__file__).resolve().parents[1] / 'app/src/main/python'


class AbsentHardware:
    def __init__(self):
        self.scans = 0
        self.probes = []
        self.resets = 0
        self.visible = 0

    def probe(self, timeout_ms):
        self.probes.append(timeout_ms)
        return self.visible

    def reset(self):
        self.resets += 1

    def scan(self, *args):
        self.scans += 1
        return ''

    def disconnect(self):
        pass

    def close(self):
        pass

    def isConnected(self):
        return False


class RuntimeIntegrationTests(unittest.TestCase):
    def test_http_restart_keeps_ui_configuration_and_history_available(self):
        self.run_isolated('runtime')

    def test_occupied_saved_port_falls_back_to_available_loopback_port(self):
        self.run_isolated('occupied-port')

    def test_recovery_notice_reaches_runtime_status_and_sse_init(self):
        self.run_isolated('recovery-notice')

    def run_isolated(self, scenario):
        # -I excludes this checkout and PYTHONPATH; use a separate working
        # directory so src imports cannot accidentally resolve from the repo.
        with tempfile.TemporaryDirectory() as cwd:
            result = subprocess.run(
                [sys.executable, '-I', '-X', 'utf8', str(Path(__file__).resolve()), '--isolated-worker', scenario],
                cwd=cwd, capture_output=True, text=True, encoding='utf-8', timeout=90,
            )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


def exercise_runtime(self):
        module = PYTHON_DIR / 'android_runtime.py'
        self.assertTrue(module.exists(), 'On-device runtime not implemented')
        sys.path.insert(0, str(PYTHON_DIR))
        import android_runtime
        native = AbsentHardware()
        with tempfile.TemporaryDirectory() as folder:
            try:
                url = android_runtime.start(folder, native)
                self.assertTrue(url.startswith('http://127.0.0.1:'))
                def request(path, data=None):
                    payload = None if data is None else json.dumps(data).encode()
                    req = urllib.request.Request(url + path, data=payload,
                                                 headers={'Content-Type': 'application/json'})
                    with urllib.request.urlopen(req, timeout=10) as response:
                        return response.read()
                self.assertTrue(json.loads(request('/api/health'))['ok'])
                config = json.loads(request('/api/config'))['config']
                self.assertFalse(config['mqtt']['enabled'])
                self.assertFalse(config['bemfa']['enabled'])
                self.assertEqual(request('/phone.html'), (PYTHON_DIR / 'web/phone.html').read_bytes())
                assets = ('/static/locales/en.js', '/static/plugin_imgs/main_card_ad1204u_unconnected.png')
                for asset in assets:
                    self.assertEqual(request(asset), (PYTHON_DIR / 'web' / asset.lstrip('/')).read_bytes())
                request('/api/web-language', {'language': 'en'})
                with self.assertRaises(urllib.error.HTTPError) as rejected:
                    request('/api/config', {'config': {'ble': {'token': 'bad'}}})
                self.assertEqual(rejected.exception.code, 400)
                result = json.loads(request('/api/config', {'config': {'server': {'log_level': 'warning'}}}))
                self.assertTrue(result['ok'])
                request('/api/config', {'config': {'mqtt': {'enabled': False, 'topic_prefix': 'test/charger'}, 'bemfa': {'enabled': False}}})
                time.sleep(1.8)
                self.assertTrue(json.loads(request('/api/health'))['ok'])
                for asset in assets:
                    self.assertEqual(request(asset), (PYTHON_DIR / 'web' / asset.lstrip('/')).read_bytes())
                self.assertEqual(json.loads(request('/api/web-language'))['language'], 'en')
                self.assertEqual(json.loads(request('/api/config'))['config']['server']['log_level'], 'warning')
                self.assertEqual(json.loads(request('/api/config'))['config']['mqtt']['topic_prefix'], 'test/charger')
                modes = json.loads(request('/api/port-modes', {'port': 'c2', 'permanent': True}))
                self.assertEqual(modes['permanent_ports'], ['c2'])
                self.assertEqual(native.scans, 0, 'Missing credentials must never initiate a BLE scan')
                android_runtime.stop()
                self.assertFalse(json.loads(android_runtime.status())['running'])
                # Android can stop and start the Service while keeping the Python process.
                original_url = url
                url = android_runtime.start(folder, native)
                self.assertEqual(url, original_url, 'Restart must retain the WebView localStorage origin')
                for asset in assets:
                    self.assertEqual(request(asset), (PYTHON_DIR / 'web' / asset.lstrip('/')).read_bytes())
                self.assertEqual(json.loads(request('/api/web-language'))['language'], 'en')
                self.assertEqual(json.loads(request('/api/port-modes'))['permanent_ports'], ['c2'])
                self.assertTrue((Path(folder) / 'port_history.db').exists())
                import cuktech_ble.protocol
                import src.cuktech_ble.protocol
                self.assertEqual(Path(cuktech_ble.protocol.__file__).resolve(),
                                 Path(src.cuktech_ble.protocol.__file__).resolve())
                self.assertEqual(Path(cuktech_ble.protocol.__file__).resolve(),
                                 PYTHON_DIR / 'src/cuktech_ble/protocol.py')
            finally:
                android_runtime.stop()
                sys.path.remove(str(PYTHON_DIR))


def exercise_recovery_notice(self):
    sys.path.insert(0, str(PYTHON_DIR))
    import android_runtime
    native = AbsentHardware()
    with tempfile.TemporaryDirectory() as folder:
        try:
            url = android_runtime.start(folder, native)
            import ha_server
            manager = ha_server._server.ble

            async def check_radio():
                # Exercise recovery without starting the charger's connection loop.
                manager._stop_event.clear()
                manager._scan_fail_streak = manager.BLE_STUCK_SCAN_FAILURES
                manager._last_ble_probe = 0
                await manager._check_bluetooth_stuck()

            def probe():
                asyncio.run_coroutine_threadsafe(check_radio(), android_runtime._loop).result(timeout=10)

            def initial_event():
                with urllib.request.urlopen(url + '/api/events', timeout=5) as response:
                    return json.loads(response.readline().decode('utf-8').removeprefix('data: '))

            probe()
            self.assertEqual(native.probes, [4000])
            self.assertEqual(native.resets, 1)
            self.assertEqual(json.loads(android_runtime.status())['notice'], 'ble_stuck_need_radio_reset')
            self.assertEqual(initial_event()['notice'], 'ble_stuck_need_radio_reset')
            native.visible = 3
            probe()
            self.assertEqual(json.loads(android_runtime.status()).get('notice', ''), '')
            self.assertEqual(initial_event().get('notice', ''), '')
            self.assertEqual(native.resets, 1)
        finally:
            android_runtime.stop()
            sys.path.remove(str(PYTHON_DIR))


def exercise_occupied_port(self):
    sys.path.insert(0, str(PYTHON_DIR))
    import android_runtime
    with tempfile.TemporaryDirectory() as folder, socket.socket() as occupied:
        occupied.bind(('127.0.0.1', 0))
        occupied.listen(1)
        port = occupied.getsockname()[1]
        (Path(folder) / 'runtime-port.json').write_text(json.dumps({'port': port}), encoding='utf-8')
        try:
            url = android_runtime.start(folder, AbsentHardware())
            self.assertTrue(url.startswith('http://127.0.0.1:'))
            self.assertNotEqual(url, f'http://127.0.0.1:{port}')
            with urllib.request.urlopen(url + '/api/health', timeout=10) as response:
                self.assertTrue(json.loads(response.read())['ok'])
            android_runtime.stop()
            self.assertEqual(android_runtime.start(folder, AbsentHardware()), url,
                             'A dynamically chosen fallback port must also persist across restart')
        finally:
            android_runtime.stop()
            sys.path.remove(str(PYTHON_DIR))


if __name__ == '__main__':
    if sys.argv[1:2] == ['--isolated-worker']:
        scenarios = {'runtime': exercise_runtime, 'occupied-port': exercise_occupied_port,
                     'recovery-notice': exercise_recovery_notice}
        scenarios[sys.argv[2]](unittest.TestCase())
    else:
        unittest.main()
