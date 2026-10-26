#!/usr/bin/env python3
"""Sign a checksum-verified CI release with the private recovery bundle, outside CI.

No passwords or private material are printed. The recovery ZIP itself is private
signing authority: it contains both the encrypted PKCS12 and its credentials.
"""
from pathlib import Path
import argparse,hashlib,json,os,re,subprocess,tempfile,zipfile

p=argparse.ArgumentParser()
p.add_argument("--input-dir",required=True,type=Path)
p.add_argument("--recovery",required=True,type=Path)
p.add_argument("--public-config",required=True,type=Path)
p.add_argument("--expected-code-sha",required=True)
p.add_argument("--expected-version-code",required=True,type=int)
p.add_argument("--output",required=True,type=Path)
a=p.parse_args()
m=json.loads((a.input_dir/"RELEASE_INPUT.json").read_text())
public=json.loads(a.public_config.read_text())
assert m["code_sha"]==a.expected_code_sha,"Unexpected source commit"
assert m["application_id"]==public["application_id"]=="com.darsbridge.browser"
assert m["version_code"]==a.expected_version_code and m["debuggable"] is False
assert m["stage"]=="UNSIGNED_INPUT_VERIFIED_PRIVATE_SIGNING_PENDING"
assert m["expected_release_certificate_sha256"]==public["certificate_sha256"]
name=Path(m["unsigned_apk_file"])
assert name.name==str(name) and name.suffix==".apk","Unsafe APK input name"
apk=a.input_dir/name
jar=a.input_dir/"apksigner.jar"
for file,key in ((apk,"unsigned_apk_sha256"),(jar,"apksigner_jar_sha256")):
    assert hashlib.sha256(file.read_bytes()).hexdigest()==m[key],"CI input checksum mismatch"
assert m["unit"]["failures"]==m["android"]["failures"]==0 and m["upgrade_proof"]["passed"] is True
with zipfile.ZipFile(a.recovery) as z:
    credentials=json.loads(z.read("credentials.json"))
    key=z.read("release.p12")
assert credentials["application_id"]==public["application_id"]
assert credentials["certificate_sha256"]==public["certificate_sha256"],"Wrong release key bundle"
a.output.parent.mkdir(parents=True,exist_ok=True)
temporary_apk=a.output.with_name(a.output.stem+".signing.tmp.apk")
try:
    with tempfile.TemporaryDirectory(prefix="darsbridge-sign-") as private:
        store=Path(private)/"release.p12";store.write_bytes(key);store.chmod(0o600)
        env=dict(os.environ,DARS_SIGN_STORE_PASS=credentials["store_password"],DARS_SIGN_KEY_PASS=credentials["key_password"])
        command=["java","-jar",str(jar),"sign","--ks",str(store),"--ks-type",credentials["store_type"],
                 "--ks-key-alias",credentials["alias"],"--ks-pass","env:DARS_SIGN_STORE_PASS",
                 "--key-pass","env:DARS_SIGN_KEY_PASS","--min-sdk-version","26",
                 "--v1-signing-enabled","false","--v2-signing-enabled","true","--v3-signing-enabled","true",
                 "--v4-signing-enabled","false","--out",str(temporary_apk),str(apk)]
        result=subprocess.run(command,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if result.returncode:raise RuntimeError("Private signing failed; no key or password output is exposed")
    result=subprocess.run(["java","-jar",str(jar),"verify","--verbose","--print-certs","--min-sdk-version","26",str(temporary_apk)],
                          capture_output=True,text=True)
    assert result.returncode==0,"Signed APK verification failed"
    match=re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)",result.stdout)
    assert match and match[1].lower()==public["certificate_sha256"],"Signing certificate mismatch"
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in result.stdout
    assert "Verified using v3 scheme (APK Signature Scheme v3): true" in result.stdout
    with zipfile.ZipFile(temporary_apk) as z:
        assert z.testzip() is None
        native=[n for n in z.namelist() if n.startswith("lib/")]
        assert native and all(n.startswith("lib/arm64-v8a/") for n in native)
    temporary_apk.replace(a.output)
    final=dict(m)
    final.update(stage="SIGNED_RELEASE_VERIFIED_PHONE_TEST_PENDING",
                 apk_sha256=hashlib.sha256(a.output.read_bytes()).hexdigest(),apk_bytes=a.output.stat().st_size,
                 signing={"kind":"persistent private release key","certificate_sha256":public["certificate_sha256"],
                          "v2_verified":True,"v3_verified":True,"private_key_in_ci":False})
    (a.output.parent/"SIGNED_RELEASE.json").write_text(json.dumps(final,ensure_ascii=False,indent=2)+"\n")
    (a.output.parent/"SHA256SUMS.txt").write_text(final["apk_sha256"]+"  "+a.output.name+"\n")
    print(json.dumps({"apk":a.output.name,"apk_sha256":final["apk_sha256"],
                      "certificate_sha256":public["certificate_sha256"],"version_code":final["version_code"],
                      "application_id":final["application_id"],"v2_verified":True,"v3_verified":True},indent=2))
finally:
    if temporary_apk.exists():temporary_apk.unlink()
