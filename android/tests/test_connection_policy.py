"""Connection strategy without requiring an Android JVM or a BLE radio."""
import asyncio
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'android/app/src/main/python/android_runtime.py'


class ConnectionPolicyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        import sys
        sys.path.insert(0, str(MODULE.parent))
        try:
            spec = importlib.util.spec_from_file_location('runtime_policy_test', MODULE)
            runtime = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(runtime)
        finally:
            sys.path.remove(str(MODULE.parent))

        import ble_manager
        from state import ChargerState
        engine = SimpleNamespace(Server=type('Server', (), {}))
        runtime._install_adapters(engine, object(), '', asyncio.Lock(), asyncio.Event())
        self.calls = []
        self.fail_direct = False
        self.entered = None
        owner = self

        class Controller:
            on_push = None
            init_push_frames = []

            def __init__(self, *args):
                pass

            async def connect(self, device=None, timeout=30.0):
                owner.calls.append(('connect', device, timeout))
                if device is None:
                    if owner.entered:
                        owner.entered.set()
                        await asyncio.Event().wait()
                    if owner.fail_direct:
                        raise ConnectionError('direct connection timed out')

            async def disconnect(self):
                owner.calls.append(('disconnect',))

        async def find_device(address, timeout):
            self.calls.append(('scan', address))
            return self.device

        self.device = SimpleNamespace(address='AA:BB:CC:DD:EE:FF')
        config = SimpleNamespace(
            ble=SimpleNamespace(scan_timeout=15),
            server=SimpleNamespace(reconnect_base_delay=1.0, reconnect_max_delay=300.0),
        )
        self.manager = engine.BLEManager(self.device.address, '00' * 12, ChargerState(), config)
        self.patches = [
            patch.object(ble_manager, 'CuktechBLEController', Controller),
            patch('bleak.BleakScanner.find_device_by_address', side_effect=find_device),
        ]
        for patcher in self.patches:
            patcher.start()
            self.addCleanup(patcher.stop)

    async def test_known_address_connects_without_waiting_for_scan(self):
        await self.manager._open_connection()
        self.assertEqual(self.calls, [('connect', None, 8.0)])

    async def test_failed_direct_attempt_cleans_up_before_scan_fallback(self):
        self.fail_direct = True
        await self.manager._open_connection()
        self.assertEqual([call[0] for call in self.calls], ['connect', 'disconnect', 'scan', 'connect'])
        self.assertIs(self.calls[-1][1], self.device)

    async def test_stop_during_direct_attempt_never_starts_scan_fallback(self):
        self.entered = asyncio.Event()
        task = asyncio.create_task(self.manager._open_connection())
        await asyncio.wait_for(self.entered.wait(), 1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual([call[0] for call in self.calls], ['connect', 'disconnect'])


if __name__ == '__main__':
    unittest.main()
