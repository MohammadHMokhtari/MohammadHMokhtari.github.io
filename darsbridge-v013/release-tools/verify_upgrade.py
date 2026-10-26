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
assert proof["before_version_code"]==12 and proof["after_version_code"]==13
assert proof["data_sha_before"]==proof["data_sha_after"]
assert proof["cert_sha_before"]==proof["cert_sha_after"]
assert proof["non_debuggable"] is True and proof["passed"] is True
baseline="ed3c582b772f98b6f4ab003733fb98666b058bcb"
assert subprocess.check_output(["git","-C","baseline-v012","rev-parse","HEAD"],text=True).strip()==baseline
badging=(root/"baseline-badging.txt").read_text()
assert "package: name='com.darsbridge.browser' versionCode='12' versionName='0.12.0'" in badging
assert "application-debuggable" not in badging
assert proof["after_version_name"]=="0.13.0"
changed=subprocess.check_output(["git","-C","baseline-v012","diff","--name-only"],text=True).splitlines()
assert changed==["darsbridge-v012/project/app/src/upgradeTest/java/com/darsbridge/app/ReleaseUpgradeTest.java"],changed
proof["baseline_runtime_unchanged"]=True
proof.update(before_source_sha=baseline,before_version_name="0.12.0",source_upgrade="tested 0.12 runtime source to new 0.13 source")
(root/"UPGRADE_PROOF.json").write_text(json.dumps(proof,ensure_ascii=False,indent=2)+"\n")
print(json.dumps(proof,ensure_ascii=False,indent=2))
