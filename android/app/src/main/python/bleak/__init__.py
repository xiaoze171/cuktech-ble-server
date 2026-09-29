"""Small Bleak-compatible transport backed exclusively by Android BluetoothGatt.

The existing MiOT controller owns framing, authentication and encryption. This
module moves bytes only; native callbacks are dispatched on the asyncio loop.
"""
import asyncio
import inspect
import json
import logging
import math
import re
from types import SimpleNamespace

_bridge = None
_LOGGER = logging.getLogger('cuktech_android_ble')
_MAC = re.compile(r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\Z')


class BleakError(Exception):
    pass


def configure_bridge(bridge):
    global _bridge
    _bridge = bridge


def _native():
    if _bridge is None:
        raise BleakError('Android 蓝牙尚未初始化')
    return _bridge


def _address(value):
    value = str(value).strip().upper()
    if not _MAC.fullmatch(value):
        raise ValueError('请先在配置页面填写有效的充电器 MAC 地址')
    return value


async def _native_call(native, method, *args):
    """Do not leave a cancelled worker free to affect the next GATT session."""
    worker = asyncio.create_task(asyncio.to_thread(method, *args))
    try:
        return await asyncio.shield(worker)
    except asyncio.CancelledError:
        await asyncio.to_thread(native.disconnect)
        try:
            await asyncio.shield(worker)
        except Exception:
            pass  # disconnect releases the native wait with an expected error
        finally:
            # Also cover cancellation before the worker entered native code.
            await asyncio.to_thread(native.disconnect)
        raise


class BleakScanner:
    @staticmethod
    async def find_device_by_address(address, timeout=10.0, **kwargs):
        address = _address(address)
        native = _native()
        raw = await _native_call(native, native.scan, address, max(1, int(float(timeout) * 1000)))
        if not raw:
            return None
        return SimpleNamespace(**json.loads(str(raw)))


class BleakClient:
    def __init__(self, address, **kwargs):
        self.address = _address(getattr(address, 'address', address))
        self._native = _native()
        self._callbacks = {}
        self._poll_task = None
        self._timeout = float(kwargs.get('timeout', 30.0))
        if not math.isfinite(self._timeout) or not 0 < self._timeout <= 120:
            raise ValueError('Bluetooth timeout must be > 0 and <= 120 seconds')
        self._disconnected_callback = kwargs.get('disconnected_callback')
        self._disconnecting = False
        self._loss_reported = False

    @property
    def is_connected(self):
        return bool(self._native.isConnected())

    @property
    def mtu_size(self):
        return int(self._native.getMtu())

    async def connect(self, **kwargs):
        self._disconnecting = False
        self._loss_reported = False
        try:
            await _native_call(self._native, self._native.connect, self.address,
                               max(1, int(self._timeout * 1000)))
        except BaseException:
            await asyncio.to_thread(self._native.disconnect)
            raise
        self._poll_task = asyncio.create_task(self._poll_notifications())
        return True

    async def disconnect(self):
        self._disconnecting = True
        self._callbacks.clear()
        task, self._poll_task = self._poll_task, None
        if task:
            task.cancel()
        await asyncio.to_thread(self._native.disconnect)
        if task:
            try:
                await task
            except asyncio.CancelledError:
                pass
        return True

    async def start_notify(self, uuid, callback, **kwargs):
        key = str(uuid).lower()
        self._callbacks[key] = callback
        try:
            await _native_call(self._native, self._native.notify, str(uuid), True, 10000)
        except BaseException:
            self._callbacks.pop(key, None)
            raise

    async def stop_notify(self, uuid):
        self._callbacks.pop(str(uuid).lower(), None)
        if self.is_connected:
            await _native_call(self._native, self._native.notify, str(uuid), False, 5000)

    async def write_gatt_char(self, uuid, data, response=False):
        await _native_call(self._native, self._native.write, str(uuid), bytes(data), bool(response), 10000)

    async def read_gatt_char(self, uuid):
        result = await _native_call(self._native, self._native.read, str(uuid), 10000)
        return bytearray(int(value) & 255 for value in result)

    async def _poll_notifications(self):
        try:
            while self.is_connected:
                # poll waits on a condition, so a notification wakes it at once;
                # 200ms is only its idle/disconnect check, not delivery latency.
                worker = asyncio.create_task(asyncio.to_thread(self._native.poll, 200))
                try:
                    raw = await asyncio.shield(worker)
                except asyncio.CancelledError:
                    await asyncio.shield(worker)
                    raise
                if not raw:
                    continue
                item = json.loads(str(raw))
                callback = self._callbacks.get(item['uuid'].lower())
                if callback:
                    try:
                        result = callback(item['uuid'], bytearray.fromhex(item['data']))
                        if inspect.isawaitable(result):
                            await result
                    except Exception:
                        _LOGGER.exception('BLE notification callback failed')
        except asyncio.CancelledError:
            raise
        except Exception:
            _LOGGER.exception('BLE notification transport stopped')
            await asyncio.to_thread(self._native.disconnect)
        finally:
            if not self._disconnecting and not self._loss_reported:
                self._loss_reported = True
                if self._disconnected_callback:
                    self._disconnected_callback(self)

    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, *args):
        await self.disconnect()
