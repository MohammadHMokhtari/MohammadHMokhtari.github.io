#!/usr/bin/env python3
"""Collect real test results and an unsigned release input for private offline signing."""
from pathlib import Path
import hashlib,json,os,re,ssl,subprocess,xml.etree.ElementTree as ET,zipfile

root=Path("darsbridge-v010")
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
assert unit["tests"]>=71 and android["tests"]>=27,(unit,android)
lint={}
for variant in ("debug","release"):
    text=(build/f"reports/lint-results-{variant}.txt").read_text()
    summary=re.search(r"(\d+) errors, (\d+) warnings",text)
    if not summary and "No issues found" in text:summary=(None,"0","0")
    assert summary and int(summary[1])==0,text[-1200:]
    lint[variant]={"errors":int(summary[1]),"warnings":int(summary[2])}
upgrade=json.loads((out/"UPGRADE_PROOF.json").read_text())
assert upgrade["passed"] is True
apk=out/"DarsBridge-0.10-unsigned.apk"
jar=out/"apksigner.jar"
assert subprocess.run(["java","-jar",str(jar),"verify",str(apk)],capture_output=True).returncode!=0,"Distribution input was unexpectedly signed"
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    native=[n for n in z.namelist() if n.startswith("lib/")]
    assert native and all(n.startswith("lib/arm64-v8a/") for n in native)
    assert "lib/arm64-v8a/libtor.so" in native and "lib/arm64-v8a/libgojni.so" in native
badging=(out/"release-badging.txt").read_text()
assert re.search(r"package: name='com.darsbridge.browser' versionCode='10' versionName='0.10.0'",badging)
assert "application-debuggable" not in badging
assert "sdkVersion:'26'" in badging
public=json.loads((root/"signing/PUBLIC_SIGNING.json").read_text())
der=ssl.PEM_cert_to_DER_cert((root/"signing/release-cert.pem").read_text())
assert hashlib.sha256(der).hexdigest()==public["certificate_sha256"]
manifest={
    "version":"0.10.0","version_code":10,"application_id":"com.darsbridge.browser","debuggable":False,
    "code_sha":os.environ["GITHUB_SHA"],
    "run_url":os.environ["GITHUB_SERVER_URL"]+"/"+os.environ["GITHUB_REPOSITORY"]+"/actions/runs/"+os.environ["GITHUB_RUN_ID"],
    "unit":unit,"android":android,"release_upgrade_tests":2,"upgrade_proof":upgrade,"lint":lint,
    "abi":"arm64-v8a","unsigned_apk_file":apk.name,"unsigned_apk_sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),
    "apksigner_jar_sha256":hashlib.sha256(jar.read_bytes()).hexdigest(),
    "expected_release_certificate_sha256":public["certificate_sha256"],
    "stage":"UNSIGNED_INPUT_VERIFIED_PRIVATE_SIGNING_PENDING",
    "phone_validation":"Pending user installation; 0.9 remains the working reference",
    "limits":["Not Tor Browser; no OS kill switch or universal no-leak guarantee",
              "Upgrade test uses a disposable CI key on a non-debuggable release variant; production key never enters CI",
              "Migration from 0.9 requires its local JSON backup; website sessions and route selection are not imported"]
}
(out/"RELEASE_INPUT.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n")
report=f"""# گزارش ساخت درس‌گذر ۰.۱۰

کد آزموده‌شده: {manifest['code_sha']}
اجرای ساخت: {manifest['run_url']}

- آزمون واحد: {unit['tests']} موفق؛ شکست و خطا و skipped صفر.
- آزمون اندروید: {android['tests']} موفق؛ شکست و خطا و skipped صفر.
- دو آزمون جدا برای نصب نسخهٔ release با کد ۱۰، ثبت داده، نصب نسخهٔ release با کد ۱۱ با adb install -r، و تأیید باقی‌ماندن داده‌ها موفق شدند.
- هر دو نسخهٔ آزمون آپدیت غیرقابل‌دیباگ بودند و یک گواهی امضای موقتِ یکسان داشتند. کلید خصوصی انتشار در این آزمون استفاده نشده است.
- checksum محتوای کتابخانه و نشانک‌ها پیش و پس از آپدیت یکسان است؛ تنظیمات و داده‌های خصوصی نیز بررسی شدند.
- lint debug: صفر خطا و {lint['debug']['warnings']} هشدار؛ lint release: صفر خطا و {lint['release']['warnings']} هشدار.

این مرحله ورودی unsigned مخصوص ARM64 را تولید می‌کند. امضای نهایی با کلید خصوصی پایدار، جدا از GitHub انجام و در SIGNED_RELEASE.json ثبت می‌شود.
شناسهٔ ثابت برنامه com.darsbridge.browser است؛ انتقال اولیه از ۰.۹ با فایل پشتیبان انجام می‌شود.
پخش واقعی ویدئو و شبکهٔ گوشی کاربر هنوز باید در ۰.۱۰ تأیید شوند. ادعای ضدنشت کامل یا افزایش قطعی سرعت وجود ندارد.
"""
(out/"CI_REPORT_FA.md").write_text(report)
print(json.dumps(manifest,ensure_ascii=False,indent=2))
