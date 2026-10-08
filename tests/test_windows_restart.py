"""Exercise the Windows launcher without starting the server or touching BLE."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock
import venv

import pytest


@pytest.mark.skipif(sys.platform != 'win32', reason='Windows batch launcher')
@pytest.mark.parametrize('first_exit, expected_runs', [(75, 2), (1, 1), (0, 1)])
def test_launcher_only_restarts_explicit_requests(tmp_path, first_exit, expected_runs):
    root = Path(__file__).resolve().parents[1]
    shutil.copyfile(root / 'start.bat', tmp_path / 'start.bat')
    (tmp_path / 'config.yaml').write_text('{}', encoding='utf-8')
    venv.EnvBuilder(with_pip=False).create(tmp_path / '.venv')
    (tmp_path / 'ha_server.py').write_text(
        'from pathlib import Path\nimport sys\n'
        'p = Path("runs.txt")\n'
        'n = int(p.read_text()) + 1 if p.exists() else 1\n'
        'p.write_text(str(n))\n'
        f'sys.exit({first_exit} if n == 1 else 0)\n', encoding='utf-8')
    result = subprocess.run(['cmd.exe', '/d', '/c', str(tmp_path / 'start.bat')],
                            cwd=tmp_path, capture_output=True, timeout=30,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    assert int((tmp_path / 'runs.txt').read_text()) == expected_runs
    assert result.returncode == (1 if first_exit == 1 else 0)


@pytest.mark.asyncio
@pytest.mark.parametrize('managed', [False, True])
async def test_windows_restart_honors_launcher_contract(monkeypatch, managed):
    import ha_server

    class Restarted(BaseException):
        pass

    calls = []

    def exit_process(code):
        calls.append(('exit', code))
        raise Restarted

    def reexec(executable, args):
        calls.append(('exec', executable))
        raise Restarted

    server = ha_server.Server.__new__(ha_server.Server)
    server.ble = SimpleNamespace(request_stop=AsyncMock())
    server.mqtt_client = server.bemfa = None
    server.history = SimpleNamespace(close=lambda: None)
    monkeypatch.setattr(ha_server, 'get_server', lambda: server)
    monkeypatch.setattr(ha_server.sys, 'platform', 'win32')
    monkeypatch.setattr(ha_server.os, '_exit', exit_process)
    monkeypatch.setattr(ha_server.os, 'execv', reexec)
    monkeypatch.setenv('CUKTECH_LAUNCHER_RESTART', '1' if managed else '0')
    with pytest.raises(Restarted):
        await server._restart()
    assert calls == [('exit', 75)] if managed else calls == [('exec', sys.executable)]
