#!/usr/bin/env python3
"""Create release evidence from actual JUnit/lint reports, never an assumed green status."""
from pathlib import Path
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET

root=Path('darsbridge-v09/project/app/build')
def totals(pattern):
    reports=sorted(root.glob(pattern))
    if not reports: raise RuntimeError('Missing test reports: '+pattern)
    result=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in reports:
        node=ET.parse(path).getroot()
        for key in result: result[key]+=int(node.attrib.get(key,0))
    if result['failures'] or result['errors'] or result['skipped']:
        raise RuntimeError('Tests did not all pass: '+str(result))
    return result
unit=totals('test-results/testDebugUnitTest/TEST-*.xml')
android=totals('outputs/androidTest-results/connected/debug/TEST-*.xml')
assert unit['tests']>=71 and android['tests']>=17,(unit,android)
lint=(root/'reports/lint-results-debug.txt').read_text()
summary=re.search(r'(\d+) errors, (\d+) warnings',lint)
assert summary and int(summary[1])==0,'Missing or failing lint result'
apk=Path('build-output/DarsBridge-0.9.apk')
manifest={
    'version':'0.9.0','code_sha':os.environ['GITHUB_SHA'],
    'run_url':os.environ['GITHUB_SERVER_URL']+'/'+os.environ['GITHUB_REPOSITORY']+'/actions/runs/'+os.environ['GITHUB_RUN_ID'],
    'unit':unit,'android':android,'lint':{'errors':int(summary[1]),'warnings':int(summary[2])},
    'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'abi':'arm64-v8a','signing':'debug; separate app id; not a stable release key',
    'phone_validation':'Pending user test; CI cannot validate the user\'s internet',
    'limits':['Not Tor Browser','No OS kill switch or universal no-leak guarantee',
              'DNS evidence is SOCKS hostname handoff, not a packet capture of all DNS',
              'WebRTC guard is defense in depth; only named document cases were tested',
              'Established sockets, all provider traffic and real-world Tor throughput are not fully measured']
}
Path('build-output/release-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
report=f'''# گزارش ساخت درس‌گذر ۰.۹

کد: `{manifest['code_sha']}`  
اجرای آزمون: {manifest['run_url']}

- آزمون واحد: {unit['tests']} موفق؛ شکست و خطا و skipped: صفر.
- آزمون اندروید: {android['tests']} موفق؛ شکست و خطا و skipped: صفر.
- lint: صفر خطا و {manifest['lint']['warnings']} هشدار.
- APK: مخصوص ARM64، با امضای آزمایشی و شناسهٔ جدا از ۰.۸.
- SHA256 فایل APK: `{manifest['apk_sha256']}`

آزمون‌های جدید مسیر پراکسی، خرابی پراکسی، HTTPS و WebSocket به مقصد کنترل‌شده،
تحویل نام دامنه به SOCKS، محافظ WebRTC پیش از اسکریپت صفحه و در فریم پیمایش‌شده،
عدم ارسال مجدد فرم در بازیابی و بازگشت از پس‌زمینه، و لغو بازیابی با توقف دستی را بررسی می‌کنند.
آزمون‌های قبلی تم، جست‌وجو، کتابخانه و پشتیبان حفظ شده‌اند.

آزمون DNS در این اجرا مشاهدهٔ نام دامنه در درخواست SOCKS است؛ ثبت همهٔ بسته‌های DNS نیست.
آزمون WebRTC و مقصد مستقیم محدود به سناریوهای ثبت‌شده است و اثبات نبود هر نوع نشت نیست.
موتور WebView جایگزین Tor Browser نیست. ترافیک خود موتور و اتصال‌های از قبل برقرارشده
به‌طور جامع اندازه‌گیری نشده‌اند. سرعت و پخش واقعی YouTube روی اینترنت کاربر باید روی گوشی آزمایش شود.
'''
Path('build-output/CI_REPORT_FA.md').write_text(report)
print(json.dumps(manifest,ensure_ascii=False,indent=2))
