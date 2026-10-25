import unittest
from update_catalog import LIMIT, validate

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

if __name__ == '__main__':
    unittest.main()
