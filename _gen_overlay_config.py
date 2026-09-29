"""Regenerate android/overlays/web/config.html = web/config.html + Android keepAlive deltas.

The overlay must always be a fresh copy of the web page plus the two Android-only
blocks below; regenerating (instead of hand-editing) keeps it from drifting stale
(the locale files went stale this way once already).
"""
import io

WEB = 'web/config.html'
OVERLAY = 'android/overlays/web/config.html'

KEEPALIVE_ROW = (
    '            <div class="config-row">\n'
    '                <div><div class="config-label" data-i18n="config.keepAlive">\u540e\u53f0\u8fd0\u884c</div>'
    '<div class="config-hint" data-i18n="config.keepAliveHint">'
    '\u79bb\u5f00\u5e94\u7528\u540e\u4fdd\u6301\u84dd\u7259\u8fde\u63a5\uff0c\u91cd\u542f\u624b\u673a\u540e\u81ea\u52a8\u6062\u590d</div></div>\n'
    '                <label class="toggle"><input type="checkbox" id="android_keep_alive" '
    'onchange="toggleKeepAlive(this.checked)"><span class="toggle-slider"></span></label>\n'
    '            </div>\n'
)

KEEPALIVE_JS = (
    '\n'
    '// \u2500\u2500 Android \u4e13\u5c5e\uff1a\u540e\u53f0\u8fd0\u884c\u5f00\u5173\uff08window.AndroidSettings \u7531 App \u6ce8\u5165\uff09\u2500\u2500\n'
    'const keepAlive = document.getElementById(\'android_keep_alive\');\n'
    'if (keepAlive && window.AndroidSettings) keepAlive.checked = AndroidSettings.isKeepAliveEnabled();\n'
    'function toggleKeepAlive(enabled) {\n'
    '    if (window.AndroidSettings) AndroidSettings.setKeepAlive(enabled);\n'
    '}\n'
)

HTML_ANCHOR = '            </div>\n        </div>\n\n        <!-- Status -->'
JS_ANCHOR = 'loadConfig();\ninitLogLevel();\n'


def main():
    s = io.open(WEB, encoding='utf-8').read()
    assert s.count(HTML_ANCHOR) == 1, 'HTML anchor not unique'
    assert s.count(JS_ANCHOR) == 1, 'JS anchor not unique'
    s = s.replace(HTML_ANCHOR, '            </div>\n' + KEEPALIVE_ROW + '        </div>\n\n        <!-- Status -->')
    s = s.replace(JS_ANCHOR, JS_ANCHOR + KEEPALIVE_JS)
    io.open(OVERLAY, 'w', encoding='utf-8', newline='').write(s)
    print('overlay config.html regenerated:', len(s), 'bytes')


if __name__ == '__main__':
    main()
