import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import update_catalog
from update_catalog import LIMIT, SOURCE, filter_playlist, validate

def playlist():
    return ('#EXTM3U\n' + ''.join(
        f'#EXTINF:-1,Canale {i}\nplugin://plugin.video.wltvhelper/play/{"rai" if i < 10 else "mediaset"}/{i}\n'
        for i in range(20))).encode()

class UpdateCatalogTest(unittest.TestCase):
    def test_accepts_complete_playlist(self):
        self.assertEqual(validate(playlist()), {'mediaset': 10, 'rai': 10})

    def test_rejects_proxy_html_truncation_and_partial_playlist(self):
        for body in (b'<html>unavailable</html>', b'#EXTM3U\n#EXTINF:-1,broken', playlist().replace(b'mediaset', b'unknown'), b'x' * (LIMIT + 1)):
            with self.subTest(body=body[:30]), self.assertRaises(ValueError):
                validate(body)

    def test_upstream_sample_loses_exactly_four_rsi_entries(self):
        source = Path(__file__).resolve().parents[1] / 'test-fixtures/channels-2026-10-25.m3u'
        original = source.read_bytes()
        filtered = filter_playlist(original)
        counts = validate(filtered)
        self.assertEqual(sum(counts.values()), 155)
        self.assertEqual(counts['direct'], 47)
        self.assertNotIn('rsi', counts)
        self.assertNotIn(b'rsila', filtered.lower())
        self.assertEqual(filter_playlist(filtered), filtered)
        bundled = update_catalog.PLAYLIST.read_bytes()
        self.assertEqual(filter_playlist(bundled), bundled)

    def test_exclusion_survives_title_id_and_endpoint_changes(self):
        entries = [
            '#EXTINF:-1 tvg-id="renamed",News\nplugin://plugin.video.wltvhelper/?play/rsi/LA%201\n',
            '#EXTINF:-1 tvg-id="RSILa2.VPN",News\nhttps://example.org/changed.mpd\n',
            '#EXTINF:-1 tvg-id="renamed",rSi La 1 HD\nhttps://example.org/changed.mpd\n',
            '#EXTINF:-1 tvg-id="renamed",News\nhttps://new.example.org/live/RSILA2HD/manifest.mpd\n',
        ]
        for entry in entries:
            with self.subTest(entry=entry):
                self.assertEqual(filter_playlist(playlist() + entry.encode()), playlist())

    def test_unrelated_channels_and_headers_are_preserved(self):
        body = (playlist() + b'#EXTINF:-1 tvg-id="radio",RSI Radio Rete Uno\n'
                b'#EXTVLCOPT:http-referrer=https://example.org/\nhttps://example.org/radio.m3u8\n'
                b'#EXTINF:-1 tvg-id="other",RSI La10\nhttps://example.org/rsila10/manifest.mpd\n')
        self.assertEqual(filter_playlist(body), body)

    def test_refresh_filters_upstream_before_writing_catalogue_and_metadata(self):
        original = (Path(__file__).resolve().parents[1] / 'test-fixtures/channels-2026-10-25.m3u').read_bytes()
        response = io.BytesIO(original)
        response.url = SOURCE
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'channels.m3u'
            metadata = Path(directory) / 'source.json'
            with patch.object(update_catalog, 'PLAYLIST', target), patch.object(update_catalog, 'METADATA', metadata):
                with patch.object(update_catalog.urllib.request, 'urlopen', return_value=response):
                    update_catalog.refresh()
                self.assertEqual(target.read_bytes(), filter_playlist(original))
                info = json.loads(metadata.read_text())
                self.assertEqual(info['entries'], 155)
                self.assertEqual(info['sha256'], hashlib.sha256(target.read_bytes()).hexdigest())
                update_catalog.check()
                target.write_bytes(original)
                with self.assertRaisesRegex(ValueError, 'excluded RSI'):
                    update_catalog.check()

if __name__ == '__main__':
    unittest.main()
