#!/usr/bin/env python3
"""Validate, sign and publish a tagged Android release."""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path
from package_source import ROOT, build_archive, project_version

SIGNING_SECRETS = ('ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?'


def run(*args, root=ROOT, check=True):
    return subprocess.run([str(x) for x in args], cwd=root, text=True, capture_output=True, check=check)


def release_notes(root, version):
    text = (root / 'CHANGELOG.md').read_text()
    match = re.search(r'^##\s+\[?' + re.escape(version) + r'\]?(?=\s|$)[^\n]*\n(.*?)(?=^##\s|\Z)', text, re.M | re.S)
    if not match or not match[1].strip():
        raise ValueError('Add this version to CHANGELOG.md before releasing')
    return (match[1].strip() + '\n\n'
            'Basata sul lavoro di [WorldLiveTV / WLTV Group](https://github.com/WorldLiveTV/WorldLiveTV.github.io). '
            'Licenza GPL-3.0-or-later. Il sorgente corrispondente è allegato alla release.\n')


def metadata(root, tag):
    version = project_version(root)
    if len(version) > 100 or not re.fullmatch(VERSION, version) or tag != f'v{version}':
        raise ValueError('Tag must be v<versionName>, for example v0.2.1')
    commit = run('git', 'rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}', root=root).stdout.strip()
    head = run('git', 'rev-parse', 'HEAD', root=root).stdout.strip()
    if commit != head:
        raise ValueError('Checkout must match the release tag')
    if run('git', 'status', '--porcelain', '--untracked-files=normal', root=root).stdout.strip():
        raise ValueError('Commit source changes before releasing')
    release_notes(root, version)
    return {'version': version, 'tag': tag, 'commit': commit, 'prerelease': '-' in version}


def check_signing(env):
    missing = [key for key in SIGNING_SECRETS if not env.get(key)]
    if missing:
        raise ValueError('Configure repository secrets: ' + ', '.join(missing))
    try:
        content = base64.b64decode(re.sub(r'\s+', '', env['ANDROID_KEYSTORE_BASE64']), validate=True)
    except (ValueError, TypeError) as error:
        raise ValueError('ANDROID_KEYSTORE_BASE64 is not valid base64') from error
    if not content or len(content) > 2 * 1024 * 1024:
        raise ValueError('Invalid keystore size')
    return content


def sign(root, output_dir, sdk):
    content = check_signing(os.environ)
    version = project_version(root)
    if not re.fullmatch(VERSION, version):
        raise ValueError('Invalid versionName')
    tools = sdk / 'build-tools/36.0.0'
    unsigned = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
    output_dir.mkdir(parents=True, exist_ok=True)
    signed = output_dir / f'LumeTV-{version}.apk'
    with tempfile.TemporaryDirectory(prefix='lumetv-signing-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        key = Path(directory) / 'release.jks'
        key.write_bytes(content)
        key.chmod(0o600)
        run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', os.environ['ANDROID_KEY_ALIAS'],
            '--ks-pass', 'env:ANDROID_KEYSTORE_PASSWORD', '--key-pass', 'env:ANDROID_KEY_PASSWORD',
            '--out', signed, unsigned, root=root)
        verification = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', signed, root=root).stdout
    cert = re.search(r'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})', verification)
    if not cert:
        raise ValueError('Unable to verify signing certificate')
    if re.search(r'Signer #1 certificate DN:.*CN=Android Debug(?:,|$)', verification):
        raise ValueError('Use a dedicated release key instead of the Android debug certificate')
    badging = run(tools / 'aapt2', 'dump', 'badging', signed, root=root).stdout
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    config = (root / 'app/build.gradle.kts').read_text()
    expected_code = re.search(r'versionCode\s*=\s*([0-9]+)', config)[1]
    expected_sdk = re.search(r'minSdk\s*=\s*([0-9]+)', config)[1]
    sdk_version = re.search(r"^(?:sdkVersion|minSdkVersion):'([0-9]+)'$", badging, re.M)
    if not package or package.groups() != ('it.lumetv.app', expected_code, version) or 'application-debuggable' in badging or not sdk_version or sdk_version[1] != expected_sdk:
        raise ValueError('Signed APK must be the non-debuggable Lume TV release for this version')
    (output_dir / 'SIGNING-CERTIFICATE.txt').write_text(f'Lume TV {version}\nCertificate SHA-256: {cert[1].lower()}\n')
    print(f'Signed and verified {signed.name}')


def assets(output_dir, version):
    return [output_dir / f'LumeTV-{version}.apk', output_dir / f'LumeTV-{version}-source.zip',
            output_dir / 'SIGNING-CERTIFICATE.txt', output_dir / 'update.json']


def update_manifest(root, output_dir):
    version = project_version(root)
    config = (root / 'app/build.gradle.kts').read_text()
    code = re.search(r'versionCode\s*=\s*([0-9]+)', config)
    sdk = re.search(r'minSdk\s*=\s*([0-9]+)', config)
    if not re.fullmatch(VERSION, version) or not code or not sdk or not 0 < int(code[1]) <= 2147483647 or not 0 < int(sdk[1]) <= 1000:
        raise ValueError('Invalid Android version or minimum SDK for update metadata')
    apk = output_dir / f'LumeTV-{version}.apk'
    if not 0 < apk.stat().st_size <= 128 * 1024 * 1024:
        raise ValueError('APK size is outside the updater limit')
    return {'schema': 1, 'applicationId': 'it.lumetv.app', 'versionName': version,
            'versionCode': int(code[1]), 'minSdk': int(sdk[1]), 'prerelease': '-' in version,
            'apk': {'name': apk.name, 'size': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}}


def verify_apk_notices(root, apk):
    legal = root / 'app/src/main/assets'
    names = ['LICENSE.txt', 'THIRD_PARTY_NOTICES.md', 'DEPENDENCY_NOTICES.txt']
    names += [p.relative_to(legal).as_posix() for p in (legal / 'licenses').glob('*.txt')]
    with zipfile.ZipFile(apk) as archive:
        for name in names:
            if 'assets/' + name not in archive.namelist() or archive.read('assets/' + name) != (legal / name).read_bytes():
                raise ValueError(f'APK legal notice differs from corresponding source: {name}')


def prepare(root, output_dir):
    version = project_version(root)
    verify_apk_notices(root, output_dir / f'LumeTV-{version}.apk')
    build_archive(root, output_dir)
    (output_dir / 'update.json').write_text(json.dumps(update_manifest(root, output_dir), indent=2) + '\n')
    paths = assets(output_dir, version)
    checksums = ''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in paths)
    (output_dir / 'SHA256SUMS').write_text(checksums)
    (output_dir / 'release-notes.md').write_text(release_notes(root, version))
    print('Prepared APK, corresponding source, certificate fingerprint, update metadata and checksums')


def publish(root, output_dir, tag, repository):
    meta = metadata(root, tag)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected OWNER/REPOSITORY')
    paths = assets(output_dir, meta['version'])
    expected = ''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in paths)
    if (output_dir / 'SHA256SUMS').read_text() != expected:
        raise ValueError('Release asset checksum mismatch')
    if json.loads((output_dir / 'update.json').read_text()) != update_manifest(root, output_dir):
        raise ValueError('Update metadata does not match the APK and Android version')
    existing = run('gh', 'release', 'view', tag, '--repo', repository, '--json', 'isDraft', root=root, check=False)
    uploads = [*paths, output_dir / 'SHA256SUMS']
    if existing.returncode == 0:
        if not json.loads(existing.stdout)['isDraft']:
            raise ValueError('Release already published; use a new version instead of replacing its APK')
        run('gh', 'release', 'upload', tag, *uploads, '--repo', repository, '--clobber', root=root)
    else:
        flags = ['--prerelease'] if meta['prerelease'] else []
        run('gh', 'release', 'create', tag, *uploads, '--repo', repository, '--verify-tag', '--draft',
            '--title', f"Lume TV {meta['version']}", '--notes-file', output_dir / 'release-notes.md', *flags, root=root)
    flags = ['--latest=false'] if meta['prerelease'] else ['--latest']
    run('gh', 'release', 'edit', tag, '--repo', repository, '--draft=false',
        f"--prerelease={str(meta['prerelease']).lower()}", *flags, root=root)
    print(f'Published {repository} {tag}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('metadata', 'check-signing', 'sign', 'prepare', 'publish'))
    parser.add_argument('--tag', default=os.environ.get('RELEASE_TAG', ''))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'dist')
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '/nonexistent'))
    parser.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY', ''))
    args = parser.parse_args()
    try:
        if args.command == 'metadata':
            values = metadata(ROOT, args.tag)
            print(json.dumps(values))
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                    output.write('version=' + values['version'] + '\n')
        elif args.command == 'check-signing':
            check_signing(os.environ)
            print('Signing secrets configured')
        elif args.command == 'sign': sign(ROOT, args.output_dir, args.sdk)
        elif args.command == 'prepare': prepare(ROOT, args.output_dir)
        elif args.command == 'publish': publish(ROOT, args.output_dir, args.tag, args.repository)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Release failed: {error}\n')


if __name__ == '__main__':
    main()
