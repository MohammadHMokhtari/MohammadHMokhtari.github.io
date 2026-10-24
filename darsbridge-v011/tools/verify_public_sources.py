#!/usr/bin/env python3
"""Public source gate: only the public certificate belongs in this tree."""
from pathlib import Path
import re
root=Path("darsbridge-v011")
files=[p for p in root.rglob("*") if p.is_file() and not any(n in ("build",".gradle","__pycache__") for n in p.relative_to(root).parts)]
for file in files:
    assert file.suffix.lower() not in (".p12",".jks",".keystore"),"Private keystore in public source"
    assert "PRIVATE" not in file.name and file.name!="credentials.json","Private recovery material in source"
    assert not re.search(br"(?m)^-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----",file.read_bytes()),"Private key in source"
print("Public-source gate passed:",len(files),"files; no private signing material")
