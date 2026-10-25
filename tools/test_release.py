import base64
import hashlib
import io
import json
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
            'app/build.gradle.kts': 'versionName = "0.2.1"\nversionCode = 3\n',
            'CHANGELOG.md': '# Changelog\n\n## 0.2.1\n\n- Current change.\n\n## 0.2.0\n\n- Old change.\n',
            'LICENSE': 'License fixture', 'README.md': 'Readme fixture',
            'THIRD_PARTY_NOTICES.md': 'WorldLiveTV attribution fixture',
            'gradlew': '#!/bin/sh\n', 'gradle/wrapper/gradle-wrapper.jar': 'Wrapper fixture',
        }
        for name, text in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        (self.root / 'gradlew').chmod(0o755)
        self.git('init', '-q')
        self.git('add', '.')
        self.commit()
        self.git('tag', 'v0.2.1')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode()

    def commit(self):
        self.git('-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'Test fixture')

    def test_tag_must_match_version_and_checked_out_commit(self):
        self.assertTrue(release.metadata(self.root, 'v0.2.1')['prerelease'])
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

    def prepared(self):
        self.output.mkdir()
        (self.output / 'LumeTV-0.2.1.apk').write_bytes(b'APK fixture')
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
        self.assertIn('--draft=false', calls[2])
        self.assertIn('--prerelease=true', calls[2])

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


if __name__ == '__main__':
    unittest.main()
