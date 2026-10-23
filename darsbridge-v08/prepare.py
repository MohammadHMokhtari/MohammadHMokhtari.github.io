#!/usr/bin/env python3
"""Reproducibly derive DarsBridge 0.8 from tested 0.7 source, without changing 0.7."""
from pathlib import Path
import hashlib
import base64
import gzip
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
BASE = HERE.parent / 'darsbridge-v07' / 'project'
OUT = HERE / 'project'
BASE_DIGEST = 'b834959b39951ec5436b867dd11d924ae17a5f7acff92b8cbe7c3c559978195b'
GZIP_DIGEST = '29c2eca9dd5aa3e3400a34dfb80219cd01573bf4aeb7bbe5f1cf7210c5fa1f05'
PATCH_DIGEST = '2f98c2f43960954829de411c1e38d93537dc2f37704c7c6c3b12e4e78d652b77'

def directory_digest(directory):
    h=hashlib.sha256()
    for p in sorted(directory.rglob('*')):
        if p.is_file():
            h.update(p.relative_to(directory).as_posix().encode()+b'\0'+p.read_bytes()+b'\0')
    return h.hexdigest()

def main():
    if not BASE.is_dir():
        raise RuntimeError('Build v0.7 base first: python3 darsbridge-v07/prepare.py')
    if directory_digest(BASE)!=BASE_DIGEST:
        raise RuntimeError('The tested 0.7 baseline has changed unexpectedly')
    data=base64.b64decode((HERE/'patch.b64').read_text().strip(),validate=True)
    if hashlib.sha256(data).hexdigest()!=GZIP_DIGEST:
        raise RuntimeError('v0.8 compressed source patch does not match expected checksum')
    patch=gzip.decompress(data)
    if hashlib.sha256(patch).hexdigest()!=PATCH_DIGEST:
        raise RuntimeError('v0.8 expanded patch does not match expected checksum')
    if OUT.exists():shutil.rmtree(OUT)
    shutil.copytree(BASE,OUT)
    patch_path=HERE/'changes.generated.patch'
    patch_path.write_bytes(patch)
    subprocess.run(['patch','--batch','--forward','-p1','-i',str(patch_path)],cwd=OUT,check=True)
    tor='app/src/main/java/com/darsbridge/app/SnowflakeTransport.java'
    if (OUT/tor).read_bytes()!=(BASE/tor).read_bytes():
        raise RuntimeError('The proven working Snowflake transport was modified')
    for name in ['Omnibox.java','MainActivity.java']:
        if not (OUT/'app/src/main/java/com/darsbridge/app'/name).is_file():
            raise RuntimeError('Missing required v0.8 file: '+name)
    print('Ready:',OUT,'(0.7 Snowflake preserved)')

if __name__=='__main__':main()
