import hashlib
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from dependency_sources import fetch, normalize_gitiles, source_entries, verified_sources, verify_report


class DependencySourcesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'third_party').mkdir()
        self.cache = self.root / 'cache'
        self.cache.mkdir()
        self.source = b'Exact upstream source fixture'
        self.entry = {'coordinate': 'example:library:1.0', 'artifact': 'library.jar', 'sha256': 'a' * 64,
                      'source': {'file': 'library-sources.jar', 'url': 'https://example.invalid/library-sources.jar',
                                 'sha256': hashlib.sha256(self.source).hexdigest()}}
        self.lock([self.entry])

    def lock(self, artifacts):
        (self.root / 'third_party/sources.lock.json').write_text(json.dumps({'schema': 1, 'artifacts': artifacts}))

    def test_missing_or_modified_sources_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing dependency source'):
            verified_sources(self.root, self.cache)
        path = self.cache / 'library-sources.jar'
        path.write_bytes(b'Wrong source')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            verified_sources(self.root, self.cache)
        path.write_bytes(self.source)
        self.assertEqual([path], verified_sources(self.root, self.cache))
        with patch('dependency_sources.urllib.request.urlopen') as network:
            fetch(self.root, self.cache)
            network.assert_not_called()

    def test_extracted_source_distribution_works_without_cache(self):
        path = self.root / 'third_party/sources/library-sources.jar'
        path.parent.mkdir(); path.write_bytes(self.source)
        self.assertEqual([path], verified_sources(self.root, self.root / 'absent-cache'))

    def test_changed_binary_dependency_is_rejected(self):
        path = self.root / 'report.json'
        artifact = {k: self.entry[k] for k in ('coordinate', 'artifact', 'sha256')}
        path.write_text(json.dumps([artifact]))
        verify_report(self.root, path)
        artifact['sha256'] = 'b' * 64
        path.write_text(json.dumps([artifact]))
        with self.assertRaisesRegex(ValueError, 'APK dependencies changed'):
            verify_report(self.root, path)

    def test_conflicting_source_versions_or_unsafe_paths_are_rejected(self):
        other = json.loads(json.dumps(self.entry))
        other['source']['sha256'] = 'b' * 64
        self.lock([self.entry, other])
        with self.assertRaisesRegex(ValueError, 'Conflicting'):
            source_entries(self.root)
        self.entry['source']['file'] = '../outside.jar'
        self.lock([self.entry])
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            source_entries(self.root)

    def test_gitiles_timestamps_do_not_change_source_checksum(self):
        def archive(timestamp, content=b'Original source'):
            output = io.BytesIO()
            with tarfile.open(fileobj=output, mode='w:gz') as tar:
                item = tarfile.TarInfo('Example.java')
                item.mtime = timestamp
                item.pax_headers = {'mtime': str(timestamp)}
                item.size = len(content)
                tar.addfile(item, io.BytesIO(content))
            return output.getvalue()
        first = normalize_gitiles(archive(12345.678))
        self.assertEqual(first, normalize_gitiles(archive(87654.321)))
        self.assertNotEqual(first, normalize_gitiles(archive(12345.678, b'Changed source')))


if __name__ == '__main__':
    unittest.main()
