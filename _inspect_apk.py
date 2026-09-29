"""Inspect a built APK: verify the drag-fix UI files are inside the Chaquopy payload."""
import io
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else 'Cuktech-1.1.1-android.14.apk'

z = zipfile.ZipFile(APK)
imy = zipfile.ZipFile(io.BytesIO(z.read('assets/chaquopy/app.imy')))
html = imy.read('web/phone.html').decode('utf-8')
js = imy.read('web/static/phone.js').decode('utf-8')
css = imy.read('web/static/phone.css').decode('utf-8')
meta = z.read('AndroidManifest.xml')
print('connect data-card:', 'data-card="connect"' in html)
print('js v15:', 'phone.js?v=15' in html, '| css v16:', 'phone.css?v=16' in html)
print('default order const:', 'CARD_DEFAULT_ORDER' in js)
print('css all-cards select-none:', '.bottom-view .card {' in css)
print('versionName bytes present:', '1.1.1-android.14'.encode() in meta or 'android.14' in str(meta[:200]))
