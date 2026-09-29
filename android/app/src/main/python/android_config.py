"""Android-private configuration defaults and validation."""
import os
from pathlib import Path
import re

import yaml

_MAC = re.compile(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\Z')


def device_ready(mac, token):
    return bool(isinstance(mac, str) and _MAC.fullmatch(mac.strip()) and
                isinstance(token, str) and re.fullmatch(r'[0-9a-fA-F]{24}', token.strip()))


def prepare_config(files_dir):
    directory = Path(files_dir).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / 'config.yaml'
    data = yaml.safe_load(path.read_text(encoding='utf-8')) if path.exists() else {}
    if not isinstance(data, dict):
        data = {}
    defaults = {
        'ble': {'mac': '', 'token': '', 'ble_key': '', 'scan_timeout': 15},
        'mqtt': {'enabled': False, 'host': '', 'port': 1883},
        'bemfa': {'enabled': False, 'uid': ''},
        'server': {'port': 18199, 'log_level': 'info', 'history_retention_days': 2,
                   'settings_refresh_interval': 10.0},
    }
    for section, values in defaults.items():
        if not isinstance(data.get(section), dict):
            data[section] = {}
        for key, value in values.items():
            data[section].setdefault(key, value)
    # Cloud integrations (MQTT/Bemfa) stay user-configurable on Android.
    # Storage and binding belong to Android, never user-controlled directories.
    data['server']['host'] = '127.0.0.1'
    data['server']['history_db_path'] = str(directory / 'port_history.db')
    temporary = path.with_suffix('.tmp')
    temporary.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding='utf-8')
    os.replace(temporary, path)
    return path


def validate_update(data):
    if not isinstance(data, dict):
        return '配置必须是对象'
    numeric = {
        ('mqtt', 'port'): (1, 65535), ('server', 'port'): (1, 65535),
        ('ble', 'scan_timeout'): (1, 120),
        ('server', 'history_retention_days'): (1, 3650),
        ('server', 'command_timeout'): (1, 120),
        ('server', 'settings_refresh_interval'): (1, 3600),
        ('server', 'reconnect_base_delay'): (0.1, 3600),
        ('server', 'reconnect_max_delay'): (1, 86400),
        ('mqtt', 'keepalive'): (5, 3600),
    }
    for section, values in data.items():
        if section not in ('ble', 'mqtt', 'bemfa', 'server') or not isinstance(values, dict):
            return '配置分组格式不正确'
        for key, value in values.items():
            if key in ('enabled', 'modified') and not isinstance(value, bool):
                return f'{section}.{key} 必须是开关值'
            bounds = numeric.get((section, key))
            if bounds:
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not bounds[0] <= value <= bounds[1]:
                    return f'{section}.{key} 数值无效'
                if key in ('port', 'history_retention_days', 'keepalive', 'scan_timeout') and int(value) != value:
                    return f'{section}.{key} 必须是整数'
            if key not in ('enabled', 'modified') and not bounds and not isinstance(value, str):
                return f'{section}.{key} 必须是文字'
            if section == 'ble' and key in ('token', 'ble_key') and value and '****' not in value:
                length = 24 if key == 'token' else 32
                if not re.fullmatch(r'[0-9a-fA-F]{' + str(length) + '}', value):
                    return f'{key} 应为 {length} 位十六进制字符'
            if section == 'ble' and key == 'mac' and value and not _MAC.fullmatch(value):
                return 'MAC 地址格式不正确'
    return None
