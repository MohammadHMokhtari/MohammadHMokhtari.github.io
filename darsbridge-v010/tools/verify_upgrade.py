#!/usr/bin/env python3
"""Require successful instrumentation and actual before/after state and signing-certificate equality."""
from pathlib import Path
import json
import re

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
(root/"UPGRADE_PROOF.json").write_text(json.dumps(proof,ensure_ascii=False,indent=2)+"\n")
print(json.dumps(proof,ensure_ascii=False,indent=2))
