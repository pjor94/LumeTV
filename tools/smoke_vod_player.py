#!/usr/bin/env python3
"""Exercise VOD controls, thumbnails and buffering on disposable phone/TV emulators."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
from threading import Thread, Lock
import time
from urllib.parse import urlparse, parse_qs

from smoke_playback import Device, ROOT, PACKAGE, fixture


def media(root):
    root.mkdir(parents=True, exist_ok=True)
    video = root / 'movie.mp4'
    if not video.exists():
        captions=root/'captions.srt'
        captions.write_text('1\n00:00:00,000 --> 00:04:00,000\nSottotitoli di prova\nIl video continua durante la ricerca delle scene.\n')
        subprocess.run([shutil.which('ffmpeg') or 'ffmpeg', '-hide_banner', '-loglevel', 'error',
                        '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=15', '-f', 'lavfi', '-i',
                        'sine=frequency=440:sample_rate=44100', '-i', str(captions),
                        '-map','0:v:0','-map','1:a:0','-map','2:s:0','-t', '240', '-c:v', 'libx264',
                        '-preset', 'ultrafast', '-b:v', '1M', '-g', '30', '-c:a', 'aac', '-b:a', '64k',
                        '-c:s','mov_text','-metadata:s:s:0','language=ita',
                        '-movflags', '+faststart', str(video)], check=True)
    return video


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--tv', required=True)
    parser.add_argument('--font', type=float, default=1.0)
    parser.add_argument('--output', type=Path, default=ROOT / 'analysis/vod-player')
    args = parser.parse_args()
    if args.phone == args.tv:
        raise ValueError('Two disposable emulators are required')
    devices = [Device(args.adb, serial, args.output) for serial in (args.phone, args.tv)]
    for device in devices:
        if device.run('shell', 'getprop', 'ro.hardware').strip() not in (b'ranchu', b'goldfish'):
            raise ValueError('Only disposable emulators are supported')
    video = media(args.output / 'media').read_bytes()
    blocked = {}
    requests = []
    lock = Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *_):
            pass
        def handle(self):
            try:
                super().handle()
            except (BrokenPipeError, ConnectionResetError):
                pass
        def do_GET(self):
            parsed = urlparse(self.path)
            mode = parsed.path.strip('/').split('/')[0]
            if parsed.path.endswith('/__pause'):
                blocked[mode] = time.monotonic() + int(parse_qs(parsed.query).get('seconds', ['7'])[0])
                self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'OK')
                return
            if not parsed.path.endswith('/movie.mp4'):
                self.send_response(404); self.send_header('Content-Length', '0'); self.end_headers(); return
            start, end = 0, len(video) - 1
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
            if match:
                start = int(match[1]); end = min(int(match[2]) if match[2] else end, end)
            if start >= len(video):
                self.send_response(416); self.send_header('Content-Range', f'bytes */{len(video)}'); self.send_header('Content-Length', '0'); self.end_headers(); return
            with lock:
                requests.append({'mode': mode, 'start': start, 'end': end, 'time': round(time.monotonic(), 3)})
            self.send_response(206 if match else 200)
            if match:
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(video)}')
            self.send_header('Content-Type', 'video/mp4'); self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Length', str(end - start + 1)); self.end_headers()
            try:
                for offset in range(start, end + 1, 32 * 1024):
                    while time.monotonic() < blocked.get(mode, 0):
                        time.sleep(.05)
                    self.wfile.write(video[offset:min(offset + 32 * 1024, end + 1)])
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    Thread(target=server.serve_forever, daemon=True).start()
    def check(pair):
        device, mode = pair
        font = device.run('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        try:
            device.stop()
            for apk in (ROOT/'app/build/outputs/apk/debug/app-debug.apk', ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
                device.run('install', '--no-incremental', '-r', '-t', str(apk))
            fixture(device, server.server_port)
            device.run('shell', 'settings', 'put', 'system', 'font_scale', str(args.font))
            device.key('KEYCODE_WAKEUP'); device.run('logcat', '-c')
            result = subprocess.run([args.adb, '-s', device.serial, 'shell', 'am', 'instrument', '-w',
                                     '-e', 'operation', 'vod-player', '-e', 'mode', mode, '-e', 'fixtureRoot',
                                     f'http://10.0.2.2:{server.server_port}/{mode}', PACKAGE+'.test/it.lumetv.app.home.HomeInspection'],
                                    capture_output=True, text=True, timeout=240)
            (device.output/'instrumentation.log').write_text(result.stdout + result.stderr)
            (device.output/'logcat.txt').write_bytes(device.run('logcat', '-d'))
            device.capture('last.png')
            for name in ('00-clean', '01-controls', '02-scene', '03-paused', '04-portrait', '05-landscape'):
                data = device.read('cache/vod-player/' + name + '.png')
                if data:
                    (device.output/(name+'.png')).write_bytes(data)
            match = re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$', result.stdout, re.M)
            if not match:
                raise RuntimeError(device.serial + ': ' + (result.stdout+result.stderr)[-2400:])
            report = json.loads(match[1]); assert report['passed']
            (device.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
            print(device.serial, mode, len(report['checks']), 'checks passed', flush=True)
            return report
        finally:
            device.stop(); device.run('shell', 'settings', 'put', 'system', 'font_scale', font)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            reports = list(pool.map(check, zip(devices, ('phone', 'tv'))))
        (args.output/'validation.json').write_text(json.dumps({'passed': True, 'font': args.font, 'reports': reports}, indent=2)+'\n')
    finally:
        (args.output/'requests.json').write_text(json.dumps(requests, indent=2)+'\n')
        server.shutdown(); server.server_close()


if __name__ == '__main__':
    main()
