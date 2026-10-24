#!/usr/bin/env python3
"""Collect real test results and an unsigned release input for private offline signing."""
from pathlib import Path
import hashlib,json,os,re,ssl,subprocess,xml.etree.ElementTree as ET,zipfile

root=Path("darsbridge-v012")
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
assert unit["tests"]>=87 and android["tests"]>=39,(unit,android)
lint={}
for variant in ("debug","release"):
    text=(build/f"reports/lint-results-{variant}.txt").read_text()
    summary=re.search(r"(\d+) errors, (\d+) warnings",text)
    if not summary and "No issues found" in text:summary=(None,"0","0")
    assert summary and int(summary[1])==0,text[-1200:]
    lint[variant]={"errors":int(summary[1]),"warnings":int(summary[2])}
upgrade=json.loads((out/"UPGRADE_PROOF.json").read_text())
assert upgrade["passed"] is True
apk=out/"DarsBridge-0.12-unsigned.apk"
jar=out/"apksigner.jar"
assert subprocess.run(["java","-jar",str(jar),"verify",str(apk)],capture_output=True).returncode!=0,"Distribution input was unexpectedly signed"
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    native=[n for n in z.namelist() if n.startswith("lib/")]
    assert native and all(n.startswith("lib/arm64-v8a/") for n in native)
    assert "lib/arm64-v8a/libtor.so" in native and "lib/arm64-v8a/libgojni.so" in native
badging=(out/"release-badging.txt").read_text()
assert re.search(r"package: name='com.darsbridge.browser' versionCode='12' versionName='0.12.0'",badging)
assert "application-debuggable" not in badging
assert "sdkVersion:'26'" in badging
public=json.loads((root/"signing/PUBLIC_SIGNING.json").read_text())
der=ssl.PEM_cert_to_DER_cert((root/"signing/release-cert.pem").read_text())
assert hashlib.sha256(der).hexdigest()==public["certificate_sha256"]
manifest={
    "version":"0.12.0","version_code":12,"application_id":"com.darsbridge.browser","debuggable":False,
    "code_sha":os.environ["GITHUB_SHA"],
    "run_url":os.environ["GITHUB_SERVER_URL"]+"/"+os.environ["GITHUB_REPOSITORY"]+"/actions/runs/"+os.environ["GITHUB_RUN_ID"],
    "unit":unit,"android":android,"release_upgrade_tests":2,"upgrade_proof":upgrade,"lint":lint,
    "abi":"arm64-v8a","unsigned_apk_file":apk.name,"unsigned_apk_sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),
    "apksigner_jar_sha256":hashlib.sha256(jar.read_bytes()).hexdigest(),
    "expected_release_certificate_sha256":public["certificate_sha256"],
    "stage":"UNSIGNED_INPUT_VERIFIED_PRIVATE_SIGNING_PENDING",
    "phone_validation":"Pending user installation; 0.11 is the phone-confirmed working reference",
    "limits":["Not Tor Browser; no OS kill switch or universal no-leak guarantee",
              "Upgrade test uses a disposable CI key on a non-debuggable release variant; production key never enters CI",
              "Migration from 0.9 requires its local JSON backup; website sessions and route selection are not imported",
              "Direct IP checks are explicit diagnostics; a TCP or HTTPS response is not a general Internet gateway",
              "IPv4 candidate ranges are sampled, not exhaustively scanned; browser routing is not changed by a result"]
}
(out/"RELEASE_INPUT.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n")
report=f"""# گزارش ساخت درس‌گذر ۰.۱۲

کد آزموده‌شده: {manifest['code_sha']}
اجرای ساخت: {manifest['run_url']}

- آزمون واحد: {unit['tests']} موفق؛ شکست، خطا و skipped صفر.
- آزمون اندروید: {android['tests']} موفق؛ شکست، خطا و skipped صفر.
- دو مرحلهٔ جدا برای داده‌گذاری روی سورس واقعی ۰.۱۱ و به‌روزرسانی با سورس ۰.۱۲ موفق شدند. داده، تنظیمات و گواهی یکسان ماندند. فقط fixture آزمونِ نسخهٔ پایه تعویض شده و کد اجرایی پایه تغییر نکرده است.
- آزمون TLS محلی، دامنهٔ اشتباه و گواهی نامعتبر را رد کرد. اتصال TCP یا TLS بدون پاسخ HTTP معتبر، موفقیت HTTPS شمرده نشد. لغو، بسته‌شدن سوکت و تغییر شبکه نیز آزمایش شدند.
- آزمون‌های جدید به IP عمومی متصل نمی‌شوند؛ HTTPS fixture محلی با گواهی آزمایشی جدا استفاده شد. سازندهٔ محصول از اعتماد پیش‌فرض سیستم استفاده می‌کند و CA آزمایشی وارد برنامهٔ انتشار نشده است.
- lint debug: صفر خطا و {lint['debug']['warnings']} هشدار؛ lint release: صفر خطا و {lint['release']['warnings']} هشدار.

جست‌وجوی IPv4 با اقدام روشن کاربر مستقیماً از شبکهٔ گوشی انجام می‌شود؛ VPN فعال مانع شروع است. محدوده‌ها حداکثر ۲۵۶ نمونه دارند، هم‌زمانی چهار و مهلت سه دقیقه است. سه پاسخ HTTPS کافی است تا جست‌وجو متوقف شود. نتیجهٔ TCP-only جدا و بدون ادعای HTTPS نمایش داده می‌شود. فهرست‌های ازپیش‌ذخیره‌شده و IPهای موفق قبلی فقط نامزد آزمون دوباره‌اند.

یافتن IP، پروکسی عمومی یا تضمین دسترسی به کل اینترنت نیست. نتیجه خودکار مسیر مرورگر را تغییر نمی‌دهد. این WebView جایگزین Tor Browser یا تضمین جلوگیری از تمام نشت‌ها نیست.

ورودی unsigned مخصوص ARM64 ساخته شد. امضای نهایی جدا از CI و با همان کلید خصوصی ثابت ۰.۱۱ انجام می‌شود. کارکرد نسخهٔ تازه روی گوشی کاربر هنوز تأیید نشده است.
"""
(out/"CI_REPORT_FA.md").write_text(report)
print(json.dumps(manifest,ensure_ascii=False,indent=2))
