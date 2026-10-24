#!/usr/bin/env python3
"""Require successful instrumentation and actual before/after state and signing-certificate equality."""
from pathlib import Path
import json
import re
import subprocess

root=Path("build-output")
for name in ("upgrade-seed.txt","upgrade-verify.txt"):
    text=(root/name).read_text()
    assert re.search(r"OK \(\s*1 test\s*\)",text),name+": instrumentation did not report success"
    assert "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text,name
lines=(root/"upgrade-verify.txt").read_text().splitlines()
values=[json.loads(line.split("darsbridge_upgrade=",1)[1]) for line in lines if "darsbridge_upgrade=" in line]
assert values,"Missing actual instrumentation upgrade proof"
proof=values[-1]
assert proof["application_id"]=="com.darsbridge.browser"
assert proof["before_version_code"]==10 and proof["after_version_code"]==11
assert proof["data_sha_before"]==proof["data_sha_after"]
assert proof["cert_sha_before"]==proof["cert_sha_after"]
assert proof["non_debuggable"] is True and proof["passed"] is True
baseline="e39650a2778a531226d8b214f9ba76658cac3944"
assert subprocess.check_output(["git","-C","baseline-v010","rev-parse","HEAD"],text=True).strip()==baseline
badging=(root/"baseline-badging.txt").read_text()
assert "package: name='com.darsbridge.browser' versionCode='10' versionName='0.10.0'" in badging
assert "application-debuggable" not in badging
assert proof["after_version_name"]=="0.11.0"
proof.update(before_source_sha=baseline,before_version_name="0.10.0",source_upgrade="tested 0.10 source to new 0.11 source")
(root/"UPGRADE_PROOF.json").write_text(json.dumps(proof,ensure_ascii=False,indent=2)+"\n")
print(json.dumps(proof,ensure_ascii=False,indent=2))
