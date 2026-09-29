"""Check the Android snapshot UI strings against the Android locale packs.

Reuses the desktop checker (tests/js/verify_i18n.py) pointed at the snapshot so
the standalone build cannot ship missing keys, redundant keys or hardcoded CJK.

Usage:  python android/tools/verify_ui_i18n.py
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT_WEB = ROOT / 'android/app/src/main/python/web'


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.path.insert(0, str(ROOT / 'tests/js'))
    import verify_i18n

    node = shutil.which('node')
    if not node:
        print('node is required to load the locale packs')
        return 2
    verify_i18n.WEB = SNAPSHOT_WEB
    verify_i18n.LOCALE_DIR = SNAPSHOT_WEB / 'static' / 'locales'
    verify_i18n.NODE = node
    try:
        verify_i18n.main()
    except SystemExit as exit_code:
        return exit_code.code or 0
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
