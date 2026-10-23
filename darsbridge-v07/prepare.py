#!/usr/bin/env python3
"""Materialize readable 0.7 sources without modifying the working 0.6 baseline.

No network access. All input bytes are committed and SHA256 pinned. The expanded
unified diff and complete generated project are included in the build artifacts.
"""
from pathlib import Path
import base64
import gzip
import hashlib
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
BASE = HERE.parent / 'darsbridge-v06'
OUT = HERE / 'project'
BASE_DIGEST = '5061b9a65f6e1fcca0e9c0ebda8c710e636e97530977bc409ad93bf6fa09baf0'
GZIP_DIGEST = '3b4417d1341b205e5ab0e35fee4da35a785b1f7726584ed787f847b315c7402c'
PATCH_DIGEST = '6afbf1bef9c20fa91efbcaca85b29df122287d7d41023c427e1ec0c417f11778'


def directory_digest(root: Path) -> str:
    h = hashlib.sha256()
    for p in sorted(root.rglob('*')):
        if p.is_file():
            h.update(p.relative_to(root).as_posix().encode() + b'\0' + p.read_bytes() + b'\0')
    return h.hexdigest()


def main() -> None:
    if directory_digest(BASE) != BASE_DIGEST:
        raise RuntimeError('Working baseline differs from the reviewed 0.6 snapshot')
    pieces = []
    for index in range(4):
        text = (HERE / 'patch' / f'part{index:02d}.b64').read_text().strip()
        if index == 3:
            # Restore a single missing character from text-tool serialization.
            # Only the known uploaded blob is repaired; the final gzip and plain
            # patch must BOTH match their independently computed SHA256 hashes.
            blob = text.encode()
            git_sha = hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest()
            if git_sha == '263a94570b4d281c46894ea3ad1926485ae401cf':
                if text.count('HuoS7RxUrh') != 1:
                    raise RuntimeError('Unexpected patch transcription')
                text = text.replace('HuoS7RxUrh', 'HuoS7RxXUrh')
        pieces.append(text)
    zipped = base64.b64decode(''.join(pieces), validate=True)
    if hashlib.sha256(zipped).hexdigest() != GZIP_DIGEST:
        raise RuntimeError('Compressed source patch checksum mismatch')
    patch = gzip.decompress(zipped)
    if hashlib.sha256(patch).hexdigest() != PATCH_DIGEST:
        raise RuntimeError('Expanded source patch checksum mismatch')
    if OUT.exists():
        shutil.rmtree(OUT)
    shutil.copytree(BASE, OUT)
    patch_path = HERE / 'changes.generated.patch'
    patch_path.write_bytes(patch)
    subprocess.run(['patch', '--batch', '--forward', '-p1', '-i', str(patch_path)], cwd=OUT, check=True)
    shutil.copyfile(HERE / 'README_FA.md', OUT / 'README_FA.md')
    native = Path('app/src/main/java/com/darsbridge/app/SnowflakeTransport.java')
    if (BASE / native).read_bytes() != (OUT / native).read_bytes():
        raise RuntimeError('Known-working Snowflake bridge configuration was unexpectedly changed')
    print('Verified baseline and patch; readable 0.7 project is ready at', OUT)


if __name__ == '__main__':
    main()
