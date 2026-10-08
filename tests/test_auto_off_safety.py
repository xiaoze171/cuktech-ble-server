"""Automatic shutdown must respect policy changes and device readback."""
import asyncio

import pytest

from ble_manager import BLEManager
from config import BLEConfig, Config
from state import ChargerState


class Device:
    def __init__(self, reported_mask=14):
        self.reported_mask = reported_mask
        self.read_mask = 15
        self.writes = []
        self.on_read = None

    async def send_miot_command(self, siid, piid, value=None):
        assert (siid, piid) == (2, 16)
        if value is None:
            if self.on_read:
                self.on_read()
            return {'value': self.read_mask}
        self.writes.append(value)
        return {'value': self.reported_mask}


def manager():
    config = Config(ble=BLEConfig(mac='AA:BB:CC:DD:EE:FF', token='00' * 12))
    result = BLEManager(config.ble.mac, config.ble.token, ChargerState(), config)
    result.ctrl = Device()
    result.state.settings['16'] = 15
    result.state.ports[1].active = True
    return result


def arm(mgr, source):
    if source == 'full_off':
        mgr.set_full_off({'c1': 'once'})
        mgr._maybe_arm_full_off(1, 1000.0)
    else:
        mgr.set_charge_limits({'c1': {'wh': 1, 'mode': 'always'}})
        mgr._energy_states[1].is_charging = True
        mgr._energy_states[1].session_wh = 2
        mgr._enforce_charge_limit(1, 1000.0)


def cancel(mgr, source, change):
    if change == 'permanent':
        mgr.set_permanent_ports(['c1'])
    elif source == 'full_off':
        mgr.set_full_off({})
        if change == 'reenable':
            mgr.set_full_off({'c1': 'once'})
    else:
        mgr.set_charge_limits({'c1': 0 if change != 'raise_limit' else 20})
        if change == 'reenable':
            mgr.set_charge_limits({'c1': {'wh': 1, 'mode': 'always'}})


@pytest.mark.asyncio
@pytest.mark.parametrize('source', ['full_off', 'limit'])
@pytest.mark.parametrize('change', ['disable', 'permanent', 'reenable'])
@pytest.mark.parametrize('during_read', [False, True])
async def test_cancelled_auto_off_cannot_reach_device(source, change, during_read):
    mgr = manager()
    arm(mgr, source)
    if during_read:
        mgr.ctrl.on_read = lambda: cancel(mgr, source, change)
    else:
        cancel(mgr, source, change)
    await mgr._process_commands()
    assert mgr.ctrl.writes == []
    assert mgr.state.ports[1].active is True


@pytest.mark.asyncio
async def test_raising_limit_cancels_old_shutdown():
    mgr = manager()
    arm(mgr, 'limit')
    cancel(mgr, 'limit', 'raise_limit')
    await mgr._process_commands()
    assert mgr.ctrl.writes == []


@pytest.mark.asyncio
@pytest.mark.parametrize('source', ['full_off', 'limit'])
async def test_current_auto_off_still_executes(source):
    mgr = manager()
    arm(mgr, source)
    await mgr._process_commands()
    assert mgr.ctrl.writes == [14]
    assert mgr.state.ports[1].active is False


@pytest.mark.asyncio
async def test_manual_off_remains_available_for_permanent_port():
    mgr = manager()
    mgr.set_permanent_ports(['c1'])
    await mgr.cmd_queue.put(('port', ('c1', 'off'), None))
    await mgr._process_commands()
    assert mgr.ctrl.writes == [14]


@pytest.mark.asyncio
async def test_already_disabled_port_adopts_readback_and_confirms_once():
    mgr = manager()
    mgr.ctrl.read_mask = 14
    arm(mgr, 'full_off')
    await mgr._process_commands()
    mgr._enforce_full_off(1, 1001.0)
    assert mgr.ctrl.writes == []
    assert mgr.state.settings['16'] == 14
    assert mgr.state.ports[1].active is False
    assert mgr.full_off_mode(1) == ''
    assert mgr._full_off_pending[1] == 0.0


@pytest.mark.asyncio
async def test_conflicting_device_echo_does_not_fake_shutdown_or_consume_once():
    mgr = manager()
    mgr.ctrl.reported_mask = 15
    arm(mgr, 'full_off')
    await mgr._process_commands()
    mgr._enforce_full_off(1, 1001.0)
    assert mgr.state.settings['16'] == 15
    assert mgr.state.ports[1].active is True
    assert mgr.full_off_mode(1) == 'once'
    assert mgr._full_off_pending[1] == 1000.0


@pytest.mark.asyncio
@pytest.mark.parametrize('port, action, echoed', [('c1', 'off', 15), ('all', 'off', 15), ('all', 'on', 14)])
async def test_command_result_reports_conflicting_device_mask(port, action, echoed):
    mgr = manager()
    mgr.ctrl.reported_mask = echoed
    future = asyncio.get_running_loop().create_future()
    await mgr._handle_port_command((port, action), future)
    assert future.result()['ok'] is False
    assert future.result()['value'] == echoed
