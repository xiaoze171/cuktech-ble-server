"""Real protocol parsing with only the GATT radio replaced by a byte sink."""
import asyncio
import struct

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESCCM

from src.cuktech_ble.controller import CuktechBLEController
from src.cuktech_ble.protocol import CHAR_CMD_RECV


PORT_PUSH = bytes.fromhex('0f20380004010201000450010b19c9')
PROTOCOL_PUSH = bytes.fromhex('10203900040102110004500100006407')
GET_RESULT = bytes.fromhex('1120010003010205000000045004000000')
SET_ACK = bytes.fromhex('0b20010001010205000000')
SET_RESULT = bytes.fromhex('1120010004010205000000045004000000')


class GattSink:
    is_connected = True

    def __init__(self):
        self.writes = []

    async def write_gatt_char(self, uuid, data, response=False):
        self.writes.append((uuid, bytes(data), response))


def controller_with_frames(*plaintexts):
    ctrl = CuktechBLEController('AA:BB:CC:DD:EE:FF', bytes(12))
    ctrl.client = GattSink()
    ctrl.authenticated = True
    ctrl._session_keys = {'dev_key': bytes(range(16)), 'dev_iv': b'\x01\x02\x03\x04'}
    receive = ctrl._make_notify_handler('cmd_recv')
    for counter, plaintext in enumerate(plaintexts, 1):
        nonce = b'\x01\x02\x03\x04' + bytes(4) + struct.pack('<I', counter)
        encrypted = AESCCM(bytes(range(16)), tag_length=4).encrypt(nonce, plaintext, None)
        receive(CHAR_CMD_RECV, b'\x00\x00\x02\x00' + struct.pack('<H', counter) + encrypted)
    return ctrl


def capture_pushes(ctrl):
    received = []

    async def on_push(plaintext):
        received.append(plaintext)

    ctrl.on_push = on_push
    return received


@pytest.mark.asyncio
async def test_draining_before_a_command_delivers_all_four_ports_and_protocol_push():
    ports = [PORT_PUSH[:7] + bytes([piid]) + PORT_PUSH[8:] for piid in range(1, 5)]
    ctrl = controller_with_frames(*ports, PROTOCOL_PUSH)
    received = capture_pushes(ctrl)

    await ctrl._drain_pending_pushes()

    assert received == ports + [PROTOCOL_PUSH]
    assert ctrl.client.writes == [(CHAR_CMD_RECV, b'\x00\x00\x03\x00', False)] * 5
    assert ctrl.get_pending_notify('cmd_recv') is None


@pytest.mark.asyncio
async def test_get_delivers_live_push_once_before_returning_its_response():
    ctrl = controller_with_frames(PORT_PUSH, PROTOCOL_PUSH, GET_RESULT)
    received = capture_pushes(ctrl)

    result = await ctrl._recv_get_response(2, 5, timeout=0.2)

    assert result['value'] == 4
    assert received == [PORT_PUSH, PROTOCOL_PUSH]
    assert ctrl.client.writes == [(CHAR_CMD_RECV, b'\x00\x00\x03\x00', False)] * 3
    assert ctrl.get_pending_notify('cmd_recv') is None


@pytest.mark.asyncio
async def test_set_does_not_swallow_an_unrelated_live_port_push():
    ctrl = controller_with_frames(SET_ACK, PORT_PUSH, SET_RESULT)
    received = capture_pushes(ctrl)

    result = await ctrl._recv_set_response(2, 5, timeout=0.2)

    assert result['value'] == 4
    assert received == [PORT_PUSH]
    assert len(ctrl.client.writes) == 3
    assert ctrl.get_pending_notify('cmd_recv') is None


@pytest.mark.asyncio
async def test_initial_push_drain_delivers_data_without_waiting_for_settings():
    ctrl = controller_with_frames(PORT_PUSH)
    received = capture_pushes(ctrl)
    # End the startup drain immediately after its first message.
    original_wait = ctrl.wait_notify

    async def wait_notify(name, timeout=5.0):
        if ctrl._notify_queues[name].empty():
            return None
        return await original_wait(name, timeout)

    ctrl.wait_notify = wait_notify
    await ctrl._drain_device_push()

    assert received == [PORT_PUSH]
    assert ctrl.init_push_frames == []  # Already delivered, never replay stale V/I later.


@pytest.mark.asyncio
async def test_stale_get_results_are_not_forwarded_as_live_pushes():
    ctrl = controller_with_frames(GET_RESULT, PORT_PUSH)
    received = capture_pushes(ctrl)
    await ctrl._drain_pending_pushes()
    assert received == [PORT_PUSH]


@pytest.mark.asyncio
async def test_controller_reuses_scanned_device_and_wakes_waiters_on_disconnect(monkeypatch):
    from types import SimpleNamespace
    from src.cuktech_ble import controller

    device = SimpleNamespace(address='AA:BB:CC:DD:EE:FF')
    clients = []

    class Client(GattSink):
        mtu_size = 247

        def __init__(self, target, **kwargs):
            super().__init__()
            self.target = target
            self.kwargs = kwargs
            clients.append(self)

        async def connect(self):
            pass

        async def start_notify(self, uuid, callback):
            pass

    monkeypatch.setattr(controller, 'BleakClient', Client)
    ctrl = CuktechBLEController(device.address, bytes(12))
    await ctrl.connect(device=device, timeout=8.0)
    assert clients[0].target is device  # A MAC string would trigger another WinRT scan.
    assert clients[0].kwargs['timeout'] == 8.0
    waiting = asyncio.create_task(ctrl.wait_notify('cmd_recv', timeout=30.0))
    await asyncio.sleep(0)
    clients[0].is_connected = False
    clients[0].kwargs['disconnected_callback'](clients[0])
    with pytest.raises(ConnectionError, match='disconnect'):
        await asyncio.wait_for(waiting, timeout=0.2)
