#!/usr/bin/env python3
"""Build and package a continuously signed release from committed sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = '67db8e3df1bc1e0be42e3f78958688a32c8444d23c817974dbb84016f0700329'


def run(*args, capture=False):
    return subprocess.run([str(value) for value in args], cwd=ROOT, check=True,
                          text=True, capture_output=capture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-ref", default="HEAD")
    args = parser.parse_args()
    if run("git", "rev-parse", args.source_ref, capture=True).stdout != run("git", "rev-parse", "HEAD", capture=True).stdout:
        raise SystemExit("Build checkout must match the source revision")
    if not os.environ.get('SIMEJI_CLEAN_SIGNING_PROPERTIES'):
        raise SystemExit('Set SIMEJI_CLEAN_SIGNING_PROPERTIES to the private UTF-8 signing properties file')
    run('git', 'diff', '--exit-code', 'HEAD')
    if run('git', 'ls-files', '--others', '--exclude-standard', capture=True).stdout.strip():
        raise SystemExit('Commit release sources first')
    run('./gradlew', ':app:testDebugUnitTest', ':app:assembleRelease', ':app:lintRelease')
    tools = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')) / 'build-tools/37.0.0'
    output = ROOT / 'app/build/outputs/apk/release'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    entry = metadata['elements'][0]
    assert metadata['variantName'] == 'release'
    version = entry['versionName']
    apk = output / entry['outputFile']
    with zipfile.ZipFile(apk) as archive:
        props = archive.read('META-INF/xposed/module.prop').decode()
        assert 'minApiVersion=102' in props and 'targetApiVersion=102' in props
        assert archive.read('META-INF/xposed/scope.list').decode().strip() == 'com.adamrocker.android.input.simeji'
        assert 'assets/xposed_init' not in archive.namelist()
        assert 'assets/THIRD_PARTY_NOTICES.txt' in archive.namelist()
    badging = run(tools / 'aapt2', 'dump', 'badging', apk, capture=True).stdout
    assert "name='net.ryuya_dev.simejiclean'" in badging
    assert "targetSdkVersion:'36'" in badging and 'application-debuggable' not in badging
    signature = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, capture=True).stdout
    assert CERTIFICATE in signature
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    destination = ROOT / 'artifacts/release' / version
    destination.mkdir(parents=True, exist_ok=True)
    signed = destination / f'SimejiClean-{version}-release.apk'
    shutil.copy2(apk, signed)
    source = destination / f'SimejiClean-{version}-source.zip'
    run('git', 'archive', '--format=zip', f'--prefix=SimejiClean-{version}/',
        f'--output={source}', 'HEAD')
    (destination / 'verification.txt').write_text(signature + '\nRelease variant; target SDK 36; compile SDK 37.2; 16 KiB aligned.\n')
    release = {'schema': 1, 'packageName': 'net.ryuya_dev.simejiclean',
               'versionCode': entry['versionCode'], 'versionName': version, 'apk': signed.name,
               'sha256': hashlib.sha256(signed.read_bytes()).hexdigest()}
    (destination / 'release-metadata.json').write_text(json.dumps(release, indent=2) + '\n')
    files = [signed, source, destination / 'verification.txt', destination / 'release-metadata.json']
    (destination / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in files))
    print(signed)


if __name__ == '__main__':
    main()
