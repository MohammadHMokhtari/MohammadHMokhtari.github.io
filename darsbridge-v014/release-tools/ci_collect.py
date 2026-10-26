#!/usr/bin/env python3
"""Collect real test results and an unsigned release input for private offline signing."""
from pathlib import Path
import hashlib,json,os,re,ssl,subprocess,xml.etree.ElementTree as ET,zipfile

root=Path("darsbridge-v014")
build=root/"project/app/build"
out=Path("build-output")
def totals(pattern):
    reports=sorted(build.glob(pattern))
    assert reports,"Missing test results: "+pattern
    result=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in reports:
        node=ET.parse(path).getroot()
        for key in result:result[key]+=int(node.attrib.get(key,0))
    assert not any(result[k] for k in ("failures","errors","skipped")),result
    return result
unit=totals("test-results/testDebugUnitTest/TEST-*.xml")
android=totals("outputs/androidTest-results/connected/debug/TEST-*.xml")
assert unit["tests"]>=101 and android["tests"]>=56,(unit,android)
lint={}
for variant in ("debug","release"):
    text=(build/f"reports/lint-results-{variant}.txt").read_text()
    summary=re.search(r"(\d+) errors, (\d+) warnings",text)
    if not summary and "No issues found" in text:summary=(None,"0","0")
    assert summary and int(summary[1])==0,text[-1200:]
    lint[variant]={"errors":int(summary[1]),"warnings":int(summary[2])}
upgrade=json.loads((out/"UPGRADE_PROOF.json").read_text())
assert upgrade["passed"] is True
apk=out/"DarsBridge-0.14-unsigned.apk"
jar=out/"apksigner.jar"
assert subprocess.run(["java","-jar",str(jar),"verify",str(apk)],capture_output=True).returncode!=0,"Distribution input was unexpectedly signed"
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    native=[n for n in z.namelist() if n.startswith("lib/")]
    assert native and all(n.startswith("lib/arm64-v8a/") for n in native)
    assert "lib/arm64-v8a/libtor.so" in native and "lib/arm64-v8a/libgojni.so" in native
badging=(out/"release-badging.txt").read_text()
assert re.search(r"package: name='com.darsbridge.browser' versionCode='14' versionName='0.14.0'",badging)
assert "application-debuggable" not in badging
assert "sdkVersion:'26'" in badging
public=json.loads((root/"signing/PUBLIC_SIGNING.json").read_text())
der=ssl.PEM_cert_to_DER_cert((root/"signing/release-cert.pem").read_text())
assert hashlib.sha256(der).hexdigest()==public["certificate_sha256"]
manifest={
    "version":"0.14.0","version_code":14,"application_id":"com.darsbridge.browser","debuggable":False,
    "code_sha":os.environ["GITHUB_SHA"],
    "run_url":os.environ["GITHUB_SERVER_URL"]+"/"+os.environ["GITHUB_REPOSITORY"]+"/actions/runs/"+os.environ["GITHUB_RUN_ID"],
    "unit":unit,"android":android,"release_upgrade_tests":2,"upgrade_proof":upgrade,"lint":lint,
    "abi":"arm64-v8a","unsigned_apk_file":apk.name,"unsigned_apk_sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),
    "apksigner_jar_sha256":hashlib.sha256(jar.read_bytes()).hexdigest(),
    "expected_release_certificate_sha256":public["certificate_sha256"],
    "stage":"UNSIGNED_INPUT_VERIFIED_PRIVATE_SIGNING_PENDING",
    "phone_validation":"Pending user installation; 0.13 is visibly running with tabs in the user screenshot; new 0.14 features still need phone validation",
    "limits":["Not Tor Browser; no OS kill switch or universal no-leak guarantee",
              "Upgrade test uses a disposable CI key on a non-debuggable release variant; production key never enters CI",
              "Migration from 0.9 requires its local JSON backup; website sessions and route selection are not imported",
              "Direct IP checks are explicit diagnostics; a TCP or HTTPS response is not a general Internet gateway",
              "IPv4 candidate ranges are sampled, not exhaustively scanned; browser routing is not changed by a result",
              "Saved tabs restore URL/title, not page state or chat messages; site account/session controls access",
              "First explicit connection after upgrading establishes the remembered connection request"]
}
(out/"RELEASE_INPUT.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n")
report=f"""# گزارش ساخت درس‌گذر ۰.۱۴

کد آزموده‌شده: {manifest['code_sha']}
اجرای ساخت: {manifest['run_url']}

- واحد: {unit['tests']} موفق؛ اندروید: {android['tests']} موفق؛ دو مرحلهٔ ارتقای انتشار موفق. شکست، خطا و skipped صفر.
- منوی لینک با لمس طولانی واقعی در WebView آزمایش شد: تب مقصد جدا و صفحهٔ جست‌وجوی اصلی حفظ شد؛ لمس کوتاه همچنان در همان تب است. تب پس‌زمینه قبل از انتخاب هیچ درخواست صفحه‌ای نمی‌فرستد و سقف ۸ تب حفظ است.
- لینک تصویر از href گرفته شد و src تصویر کپی نشد. بازکردن منو کلیپ‌بورد را تغییر نداد؛ کپی فقط با انتخاب صریح انجام شد. انتخاب متن معمولی اندروید حفظ ماند.
- اشتراک فقط Intent متن لینک را به chooser می‌دهد؛ chooser در آزمون متوقف شد و چیزی به برنامه‌ای ارسال نشد. منو با تغییر تب و خروج از برنامه بسته شد.
- نوار اتصال جمع‌وجور، جزئیات بازشونده و منوی سه‌نقطه بدون شروع اتصال آزمایش شدند. تصاویر خانه روشن/تاریک و منوی لینک و نوار مرورگر ثبت شدند.
- ارتقا از سورس واقعی ۰.۱۳ به ۰.۱۴ روی release غیرقابل‌دیباگ با گواهی موقت CI موفق بود. کتابخانه، نشانک، تنظیمات، دو تب، تاریخچه و درخواست ذخیره‌شدهٔ اتصال حفظ شدند. SHA فایل browser-v013.json قبل و بعد یکسان است. فقط fixture آزمون پایه تغییر کرد، نه کد اجرایی آن.
- تمام آزمون‌های قبلی مرورگر، مسیر، جلوگیری از بازپخش POST و IP/TLS نیز اجرا شدند. منوی لینک هیچ اعتماد TLS، پراکسی یا تنظیم Tor را تغییر نمی‌دهد.
- lint debug: {lint['debug']['errors']} خطا و {lint['debug']['warnings']} هشدار؛ release: {lint['release']['errors']} خطا و {lint['release']['warnings']} هشدار.

رابط مشابه الگوهای مرورگرهای رایج است؛ کل قابلیت‌ها یا حفاظت‌های Chrome/Opera/Tor Browser پیاده‌سازی نشده‌اند. تب‌های پس‌زمینه برای حفظ سیاست اتصال فقط هنگام انتخاب بارگذاری می‌شوند. URL منو به HTTPS محدود است و اسکریپت، فایل و intent اجرا نمی‌کند. اشتراک فقط با انتخاب کاربر و از طریق chooser اندروید است.

ورودی unsigned ARM64 ساخته شد؛ کلید خصوصی انتشار وارد CI نمی‌شود و امضای محصول جدا انجام می‌شود. بررسی روی گوشی کاربر هنوز در انتظار نصب است.
"""
(out/"CI_REPORT_FA.md").write_text(report)
print(json.dumps(manifest,ensure_ascii=False,indent=2))
