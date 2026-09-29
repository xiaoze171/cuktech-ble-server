"""Exercise the Android adapter at its actual native transport boundary."""
import asyncio
import importlib.util
import json
from pathlib import Path
import queue
import threading
import unittest

MODULE = Path(__file__).parents[1] / 'app/src/main/python/bleak/__init__.py'


def load_adapter():
    spec = importlib.util.spec_from_file_location('android_ble_transport', MODULE)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class NativeTransport:
    def __init__(self):
        self.connected = False
        self.events = queue.Queue()
        self.written = []
        self.disconnections = 0
        self.connect_timeouts = []

    def scan(self, mac, timeout):
        return json.dumps({'address': mac, 'name': 'CUKTECH'})

    def connect(self, mac, timeout):
        self.connect_timeouts.append(timeout)
        self.connected = True

    def isConnected(self):
        return self.connected

    def getMtu(self):
        return 247

    def read(self, uuid, timeout):
        return [-1, -128, 0, 127]

    def write(self, uuid, data, response, timeout):
        self.written.append((uuid, bytes(data), response))

    def notify(self, uuid, enable, timeout):
        pass

    def poll(self, timeout):
        try:
            return self.events.get(timeout=timeout / 1000)
        except queue.Empty:
            return ''

    def disconnect(self):
        self.disconnections += 1
        self.connected = False


class AndroidBleTransportTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.assertTrue(MODULE.exists(), 'Android BLE adapter has not been implemented')
        self.bleak = load_adapter()
        self.native = NativeTransport()
        self.bleak.configure_bridge(self.native)

    async def test_notification_bytes_dispatched_on_asyncio_thread(self):
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF')
        await client.connect()
        received = asyncio.get_running_loop().create_future()
        thread_id = threading.get_ident()
        uuid = '0000001b-0000-1000-8000-00805f9b34fb'
        await client.start_notify(uuid, lambda sender, data: received.set_result((bytes(data), threading.get_ident())))
        self.native.events.put(json.dumps({'uuid': uuid, 'data': '0080ff'}))
        self.assertEqual(await asyncio.wait_for(received, 2), (b'\x00\x80\xff', thread_id))
        await client.disconnect()

    async def test_native_signed_bytes_and_write_mode_preserved(self):
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF')
        await client.connect()
        self.assertEqual(bytes(await client.read_gatt_char('firmware')), b'\xff\x80\x00\x7f')
        await client.write_gatt_char('command', b'\xff\x00\x80', response=False)
        self.assertEqual(self.native.written, [('command', b'\xff\x00\x80', False)])
        self.assertEqual(client.mtu_size, 247)
        await client.disconnect()

    async def test_stopped_notifications_are_not_delivered(self):
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF')
        await client.connect()
        received = []
        await client.start_notify('port', lambda sender, data: received.append(data))
        await client.stop_notify('port')
        self.native.events.put(json.dumps({'uuid': 'port', 'data': '01'}))
        await asyncio.sleep(0.3)
        self.assertEqual(received, [])
        await client.disconnect()

    async def test_invalid_address_rejected_before_scan(self):
        with self.assertRaises(ValueError):
            await self.bleak.BleakScanner.find_device_by_address('XX:XX:XX:XX:XX:XX', timeout=1)

    async def test_cancelled_connection_releases_native_transport(self):
        started = threading.Event()
        release = threading.Event()
        def slow_connect(mac, timeout):
            started.set()
            release.wait(2)
        self.native.connect = slow_connect
        original_disconnect = self.native.disconnect
        def disconnect():
            original_disconnect()
            release.set()
        self.native.disconnect = disconnect
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF')
        task = asyncio.create_task(client.connect())
        await asyncio.to_thread(started.wait, 1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertGreater(self.native.disconnections, 0)

    async def test_connect_uses_callers_deadline_for_fast_direct_attempt(self):
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF', timeout=8.0)
        try:
            await client.connect()
            self.assertEqual(self.native.connect_timeouts, [8000])
        finally:
            await client.disconnect()

    async def test_link_loss_notifies_controller_once_on_asyncio_thread(self):
        loop = asyncio.get_running_loop()
        events = []
        thread_id = threading.get_ident()
        client = self.bleak.BleakClient(
            'AA:BB:CC:DD:EE:FF',
            disconnected_callback=lambda value: events.append((value, threading.get_ident())),
        )
        try:
            await client.connect()
            self.native.connected = False
            deadline = loop.time() + 1
            while not events and loop.time() < deadline:
                await asyncio.sleep(0.01)
            self.assertEqual(events, [(client, thread_id)])
            await client.disconnect()
            await asyncio.sleep(0)
            self.assertEqual(len(events), 1)
        finally:
            await client.disconnect()

    async def test_deliberate_disconnect_does_not_report_unexpected_loss(self):
        events = []
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF', disconnected_callback=events.append)
        await client.connect()
        await client.disconnect()
        self.assertEqual(events, [])

    async def test_cancellation_waits_until_native_connect_releases_before_reuse(self):
        loop = asyncio.get_running_loop()
        started = asyncio.Event()
        release = threading.Event()
        finished = threading.Event()

        def slow_connect(mac, timeout):
            loop.call_soon_threadsafe(started.set)
            release.wait(2)
            # The worker returns only after native cleanup finishes.
            import time
            time.sleep(0.05)
            finished.set()

        original_disconnect = self.native.disconnect

        def disconnect():
            original_disconnect()
            release.set()

        self.native.connect = slow_connect
        self.native.disconnect = disconnect
        client = self.bleak.BleakClient('AA:BB:CC:DD:EE:FF')
        task = asyncio.create_task(client.connect())
        try:
            await asyncio.wait_for(started.wait(), 1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertTrue(finished.is_set(), 'Old native worker can still affect the next connection')
        finally:
            release.set()


if __name__ == '__main__':
    unittest.main()
