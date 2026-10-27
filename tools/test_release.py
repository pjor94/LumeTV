import base64
import hashlib
import io
import json
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import release
from package_source import build_archive


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.output_capture = redirect_stdout(io.StringIO())
        self.output_capture.__enter__()
        self.addCleanup(self.output_capture.__exit__, None, None, None)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'repo'
        self.root.mkdir()
        self.output = self.root.parent / 'assets'
        files = {
            'app/build.gradle.kts': 'versionName = "0.2.1"\nversionCode = 3\nminSdk = 23\n',
            'CHANGELOG.md': '# Changelog\n\n## 0.2.1\n\n- Current change.\n\n## 0.2.0\n\n- Old change.\n',
            'LICENSE': 'License fixture', 'README.md': 'Readme fixture',
            'THIRD_PARTY_NOTICES.md': 'WorldLiveTV attribution fixture',
            'gradlew': '#!/bin/sh\n', 'gradle/wrapper/gradle-wrapper.jar': 'Wrapper fixture',
            'third_party/sources.lock.json': '{"schema":1,"artifacts":[]}',
            'third_party/README.md': 'Dependency source fixture',
            'app/src/main/assets/LICENSE.txt': 'License fixture',
            'app/src/main/assets/THIRD_PARTY_NOTICES.md': 'WorldLiveTV attribution fixture',
            'app/src/main/assets/DEPENDENCY_NOTICES.txt': 'Dependency notices fixture',
        }
        for name, text in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        (self.root / 'gradlew').chmod(0o755)
        shutil.copytree(release.ROOT / 'app/src/main/assets/licenses', self.root / 'app/src/main/assets/licenses')
        self.git('init', '-q')
        self.git('add', '.')
        self.commit()
        self.git('tag', 'v0.2.1')
        report = self.root / 'app/build/reports/release-dependencies.json'
        report.parent.mkdir(parents=True)
        report.write_text('[]')
        # Build reports are generated locally and never belong in a source archive.
        (self.root / '.git/info/exclude').write_text('app/build/\n')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode()

    def commit(self):
        self.git('-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'Test fixture')

    def test_tag_must_match_version_and_checked_out_commit(self):
        self.assertFalse(release.metadata(self.root, 'v0.2.1')['prerelease'])
        for tag in ('v0.2.10', 'main', 'v0.2.1;echo test'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.metadata(self.root, tag)
        (self.root / 'README.md').write_text('Another commit')
        self.git('add', 'README.md'); self.commit()
        with self.assertRaisesRegex(ValueError, 'Checkout'):
            release.metadata(self.root, 'v0.2.1')

    def test_uncommitted_source_cannot_be_released(self):
        (self.root / 'README.md').write_text('Uncommitted')
        with self.assertRaisesRegex(ValueError, 'Commit source'):
            release.metadata(self.root, 'v0.2.1')

    def test_only_explicit_version_suffixes_create_prereleases(self):
        config = self.root / 'app/build.gradle.kts'
        config.write_text('versionName = "0.2.2-beta.1"\nversionCode = 4\nminSdk = 23\n')
        changelog = self.root / 'CHANGELOG.md'
        changelog.write_text('## 0.2.2-beta.1\n\n- Technical test.\n\n' + changelog.read_text())
        self.git('add', '.')
        self.commit()
        self.git('tag', 'v0.2.2-beta.1')
        self.assertTrue(release.metadata(self.root, 'v0.2.2-beta.1')['prerelease'])
        self.output.mkdir()
        (self.output / 'LumeTV-0.2.2-beta.1.apk').write_bytes(b'APK fixture')
        self.assertTrue(release.update_manifest(self.root, self.output)['prerelease'])

    def test_notes_use_exact_version_and_credit_upstream(self):
        path = self.root / 'CHANGELOG.md'
        path.write_text('## 0.2.10\n\nWrong version.\n\n' + path.read_text())
        notes = release.release_notes(self.root, '0.2.1')
        self.assertIn('Current change.', notes)
        self.assertNotIn('Wrong version.', notes)
        self.assertNotIn('Old change.', notes)
        self.assertIn('WorldLiveTV / WLTV Group', notes)
        with self.assertRaises(ValueError):
            release.release_notes(self.root, '0.3.0')

    def test_signing_requires_all_secrets_without_printing_their_values(self):
        with self.assertRaises(ValueError) as failure:
            release.check_signing({'ANDROID_KEYSTORE_PASSWORD': 'private-password'})
        self.assertNotIn('private-password', str(failure.exception))
        env = dict.fromkeys(release.SIGNING_SECRETS, 'configured')
        env['ANDROID_KEYSTORE_BASE64'] = 'invalid!private-content'
        with self.assertRaises(ValueError) as failure:
            release.check_signing(env)
        self.assertNotIn('private-content', str(failure.exception))
        env['ANDROID_KEYSTORE_BASE64'] = base64.b64encode(b'key fixture').decode()
        self.assertEqual(b'key fixture', release.check_signing(env))

    def test_source_contains_licenses_and_excludes_untracked_files(self):
        (self.root / 'private.jks').write_text('Private fixture')
        (self.root / 'build').mkdir()
        (self.root / 'build/report.md').write_text('Local report')
        first = build_archive(self.root, self.output)
        digest = hashlib.sha256(first.read_bytes()).hexdigest()
        with zipfile.ZipFile(first) as archive:
            paths = archive.namelist()
            self.assertIn('LumeTV-0.2.1/LICENSE', paths)
            self.assertIn('LumeTV-0.2.1/THIRD_PARTY_NOTICES.md', paths)
            self.assertFalse(any('private.jks' in x or 'report.md' in x for x in paths))
            self.assertTrue(archive.getinfo('LumeTV-0.2.1/gradlew').external_attr >> 16 & 0o111)
        self.assertEqual(digest, hashlib.sha256(build_archive(self.root, self.output).read_bytes()).hexdigest())

    def test_accidentally_tracked_keystore_is_rejected(self):
        (self.root / 'private.jks').write_text('Private fixture')
        self.git('add', 'private.jks')
        with self.assertRaisesRegex(ValueError, 'Private or generated'):
            build_archive(self.root, self.output)

    def test_removed_workflow_is_not_restored_in_source_archive(self):
        workflow = self.root / '.github/workflows/release.yml'
        workflow.parent.mkdir(parents=True)
        workflow.write_text('name: Previously enabled release\n')
        self.git('add', '.github/workflows/release.yml')
        self.commit()
        workflow.unlink()
        output = build_archive(self.root, self.output)
        with zipfile.ZipFile(output) as archive:
            self.assertNotIn('LumeTV-0.2.1/.github/workflows/release.yml', archive.namelist())

    def test_new_source_cannot_be_silently_omitted(self):
        path = self.root / 'app/src/main/NewPolicy.kt'
        path.write_text('New source fixture')
        with self.assertRaisesRegex(ValueError, 'Untracked source'):
            build_archive(self.root, self.output)
        with self.assertRaisesRegex(ValueError, 'Commit source'):
            release.metadata(self.root, 'v0.2.1')
        output = build_archive(self.root, self.output, include_untracked=True)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(b'New source fixture', archive.read('LumeTV-0.2.1/app/src/main/NewPolicy.kt'))
            manifest = archive.read('LumeTV-0.2.1/SOURCE-MANIFEST.sha256').decode()
            for line in manifest.splitlines():
                checksum, name = line.split('  ', 1)
                self.assertEqual(checksum, hashlib.sha256(archive.read('LumeTV-0.2.1/' + name)).hexdigest())

    def prepared(self):
        self.output.mkdir()
        with zipfile.ZipFile(self.output / 'LumeTV-0.2.1.apk', 'w') as apk:
            legal = self.root / 'app/src/main/assets'
            for path in legal.rglob('*'):
                if path.is_file():
                    apk.write(path, 'assets/' + path.relative_to(legal).as_posix())
        (self.output / 'SIGNING-CERTIFICATE.txt').write_text('Fingerprint fixture')
        release.prepare(self.root, self.output)

    def fake_github(self, state=None, upload_failure=False):
        original = release.run
        calls = []
        def command(*args, **kwargs):
            if args[0] != 'gh':
                return original(*args, **kwargs)
            calls.append([str(x) for x in args])
            if args[2] == 'view':
                return subprocess.CompletedProcess(args, 1 if state is None else 0,
                                                   stdout='' if state is None else json.dumps({'isDraft': state}), stderr='')
            if upload_failure:
                raise subprocess.CalledProcessError(1, args)
            return subprocess.CompletedProcess(args, 0, stdout='', stderr='')
        return calls, patch.object(release, 'run', side_effect=command)

    def test_new_release_uploads_all_assets_before_publishing(self):
        self.prepared()
        calls, fake = self.fake_github()
        with fake:
            release.publish(self.root, self.output, 'v0.2.1', 'owner/repository')
        self.assertEqual(['view', 'create', 'edit'], [x[2] for x in calls])
        self.assertIn('--draft', calls[1])
        self.assertIn('--verify-tag', calls[1])
        self.assertTrue(any(x.endswith('-source.zip') for x in calls[1]))
        self.assertTrue(any(x.endswith('/update.json') for x in calls[1]))
        self.assertIn('--draft=false', calls[2])
        self.assertIn('--prerelease=false', calls[2])
        self.assertIn('--latest', calls[2])
        self.assertNotIn('--prerelease', calls[1])

    def test_draft_can_resume_but_published_release_cannot_be_replaced(self):
        self.prepared()
        calls, fake = self.fake_github(state=True)
        with fake:
            release.publish(self.root, self.output, 'v0.2.1', 'owner/repository')
        self.assertEqual(['view', 'upload', 'edit'], [x[2] for x in calls])
        calls, fake = self.fake_github(state=False)
        with fake, self.assertRaisesRegex(ValueError, 'already published'):
            release.publish(self.root, self.output, 'v0.2.1', 'owner/repository')
        self.assertEqual(['view'], [x[2] for x in calls])

    def test_upload_failure_never_publishes_the_draft(self):
        self.prepared()
        calls, fake = self.fake_github(upload_failure=True)
        with fake, self.assertRaises(subprocess.CalledProcessError):
            release.publish(self.root, self.output, 'v0.2.1', 'owner/repository')
        self.assertNotIn('edit', [x[2] for x in calls])

    def test_tampered_artifacts_are_not_uploaded(self):
        self.prepared()
        (self.output / 'LumeTV-0.2.1.apk').write_bytes(b'Changed fixture')
        calls, fake = self.fake_github()
        with fake, self.assertRaisesRegex(ValueError, 'checksum'):
            release.publish(self.root, self.output, 'v0.2.1', 'owner/repository')
        self.assertFalse(calls)

    def test_stale_apk_licenses_cannot_be_prepared(self):
        self.prepared()
        (self.root / 'app/src/main/assets/THIRD_PARTY_NOTICES.md').write_text('Updated attribution')
        with self.assertRaisesRegex(ValueError, 'APK legal notice differs'):
            release.prepare(self.root, self.output)

    def test_update_metadata_matches_the_apk_and_is_checksummed(self):
        self.prepared()
        info = json.loads((self.output / 'update.json').read_text())
        apk = self.output / 'LumeTV-0.2.1.apk'
        self.assertEqual({'schema': 1, 'applicationId': 'it.lumetv.app', 'versionName': '0.2.1', 'versionCode': 3,
                          'minSdk': 23, 'prerelease': False,
                          'apk': {'name': apk.name, 'size': apk.stat().st_size,
                                  'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}}, info)
        self.assertIn(hashlib.sha256((self.output / 'update.json').read_bytes()).hexdigest() + '  update.json\n',
                      (self.output / 'SHA256SUMS').read_text())

    def test_signing_checks_minimum_sdk_against_the_built_apk(self):
        env = dict.fromkeys(release.SIGNING_SECRETS, 'fixture')
        env['ANDROID_KEYSTORE_BASE64'] = base64.b64encode(b'key fixture').decode()
        def command(*args, **kwargs):
            if 'verify' in args:
                output = 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'
            elif 'badging' in args:
                output = f"package: name='it.lumetv.app' versionCode='3' versionName='0.2.1'\n{sdk_label}:'{minimum_sdk}'\n"
            else:
                Path(args[args.index('--out') + 1]).write_bytes(b'signed APK fixture')
                output = ''
            return subprocess.CompletedProcess(args, 0, stdout=output, stderr='')
        with patch.dict('os.environ', env), patch.object(release, 'run', side_effect=command):
            sdk_label = 'minSdkVersion'
            minimum_sdk = 24
            with self.assertRaisesRegex(ValueError, 'non-debuggable Lume TV release'):
                release.sign(self.root, self.output, self.root / 'sdk')
            minimum_sdk = 23
            release.sign(self.root, self.output, self.root / 'sdk')
            self.assertIn('a' * 64, (self.output / 'SIGNING-CERTIFICATE.txt').read_text())
            sdk_label = 'sdkVersion'
            release.sign(self.root, self.output, self.root / 'sdk')

    def test_inconsistent_update_metadata_is_never_published_even_with_new_checksums(self):
        self.prepared()
        info = json.loads((self.output / 'update.json').read_text())
        info['versionCode'] = 100
        (self.output / 'update.json').write_text(json.dumps(info))
        (self.output / 'SHA256SUMS').write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n'
                                                    for path in release.assets(self.output, '0.2.1')))
        calls, fake = self.fake_github()
        with fake, self.assertRaisesRegex(ValueError, 'Update metadata'):
            release.publish(self.root, self.output, 'v0.2.1', 'owner/repository')
        self.assertFalse(calls)


if __name__ == '__main__':
    unittest.main()
