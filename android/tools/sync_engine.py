"""Explicitly refresh the standalone Android snapshot from this checkout.

Never copies local credentials/databases. Files under `android/overlays`
mirror snapshot paths and are applied on top of the copied sources, so
running this script always reproduces the shipped Android snapshot instead
of reverting the Android-only UI changes.
"""
import hashlib
import json
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'android/app/src/main/python'
OVERLAYS = ROOT / 'android/overlays'
MANIFEST = ROOT / 'android/engine-manifest.json'
MODULES = ['ha_server.py', 'ble_manager.py', 'config.py', 'state.py',
           'state_protocol_v2.py', 'history.py', 'energy.py', 'downsample.py',
           'bemfa_client.py', 'xiaomi_cloud.py']
# Authored inside android/app/src/main/python; copied from no desktop source.
ADAPTERS = ['android_config.py', 'android_runtime.py', 'bleak/__init__.py', 'src/__init__.py']


def source_files():
    files = [(ROOT / name, TARGET / name) for name in MODULES]
    files += [(p, TARGET / p.relative_to(ROOT)) for p in (ROOT / 'src/cuktech_ble').glob('*.py')]
    files += [(p, TARGET / p.relative_to(ROOT)) for p in (ROOT / 'web').rglob('*') if p.is_file()]
    return sorted(files, key=lambda pair: pair[1].as_posix())


def overlay_files():
    if not OVERLAYS.is_dir():
        return []
    mirrored = (p for p in OVERLAYS.rglob('*') if p.is_file() and p.suffix != '.md')
    return sorted(((p, TARGET / p.relative_to(OVERLAYS)) for p in mirrored),
                  key=lambda pair: pair[1].as_posix())


def inventory():
    """Snapshot layout: every destination with its expected bytes and provenance."""
    entries = {}
    for source, destination in source_files():
        entries[destination.relative_to(TARGET).as_posix()] = {
            'source': source.relative_to(ROOT).as_posix(),
            'sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'overlay': False,
        }
    for source, destination in overlay_files():
        key = destination.relative_to(TARGET).as_posix()
        entry = entries.get(key)
        if entry is None:
            raise ValueError(f'Overlay {key} has no matching source file to override')
        entry['overlay'] = True
        entry['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
    for key in ADAPTERS:
        if key in entries:
            raise ValueError(f'Android adapter {key} also exists as a copied source file')
        entries[key] = {
            'source': (TARGET / key).relative_to(ROOT).as_posix(),
            'sha256': hashlib.sha256((TARGET / key).read_bytes()).hexdigest(),
            'adapter': True,
        }
    return entries


def write_manifest(entries):
    MANIFEST.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def sync():
    files = source_files()
    overlays = overlay_files()
    for source, destination in files + overlays:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    # Migrate the earlier flattened snapshot. Only known generated Python
    # modules are removed, after checking every resolved path stays in Android.
    android_python = TARGET.resolve()
    for source in (ROOT / 'src/cuktech_ble').glob('*.py'):
        obsolete = (TARGET / 'cuktech_ble' / source.name).resolve()
        if not obsolete.is_relative_to(android_python):
            raise ValueError(f'Obsolete snapshot escapes Android directory: {obsolete}')
        obsolete.unlink(missing_ok=True)
    obsolete_package = TARGET / 'cuktech_ble'
    if obsolete_package.is_dir() and not any(obsolete_package.iterdir()):
        obsolete_package.rmdir()
    entries = inventory()
    write_manifest(entries)
    overlays_applied = sum(1 for entry in entries.values() if entry.get('overlay'))
    print(f'Copied {len(files)} engine and UI files ({overlays_applied} Android overlays); '
          'no device credentials included.')


def check():
    entries = inventory()
    problems = []
    for key, entry in entries.items():
        path = TARGET / key
        if not path.is_file():
            problems.append(f'missing {key}')
        elif hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            detail = 'stale Android overlay' if entry.get('overlay') else 'copy drifted from source'
            problems.append(f'{detail}: {key}')
    expected = set(entries)
    for path in TARGET.rglob('*'):
        if path.is_file() and '__pycache__' not in path.parts:
            key = path.relative_to(TARGET).as_posix()
            if key not in expected:
                problems.append(f'unexpected file in snapshot: {key}')
    if problems:
        print('\n'.join(problems))
        return 1
    overlays_applied = sum(1 for entry in entries.values() if entry.get('overlay'))
    print(f'Snapshot matches sources and the {overlays_applied} Android overlays ({len(entries)} files).')
    return 0


if __name__ == '__main__':
    if sys.argv[1:2] == ['--check']:
        raise SystemExit(check())
    sync()
