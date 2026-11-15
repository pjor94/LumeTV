#!/usr/bin/env python3
"""Playback options, language, captions and timer checks on two disposable emulators.

Creates owned HLS fixtures with ffmpeg. Never selects a physical device or changes CI.
Requires the current Debug and AndroidTest APKs to have been built first.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
from threading import Thread

from smoke_playback import Device, ROOT, PACKAGE, fixture


def media(root):
    root.mkdir(parents=True, exist_ok=True)
    ffmpeg = shutil.which('ffmpeg')
    if not ffmpeg:
        raise RuntimeError('ffmpeg is required to generate the synthetic streams')
    for height, bitrate in [(180, '120k'), (360, '300k')]:
        output = root / f'video{height}.m3u8'
        if not output.exists():
            subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                            f'testsrc2=size={height*16//9}x{height}:rate=15', '-t', '180', '-an', '-c:v',
                            'libx264', '-preset', 'ultrafast', '-g', '30', '-b:v', bitrate,
                            '-f', 'hls', '-hls_time', '6', '-hls_list_size', '0', str(output)], check=True)
    for language, frequency in [('it', 440), ('en', 880)]:
        output = root / f'audio-{language}.m3u8'
        if not output.exists():
            subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                            f'sine=frequency={frequency}:sample_rate=44100', '-t', '180', '-c:a', 'aac',
                            '-b:a', '32k', '-f', 'hls', '-hls_time', '6', '-hls_list_size', '0', str(output)], check=True)
    (root / 'master.m3u8').write_text('''#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Italiano",LANGUAGE="it",DEFAULT=YES,AUTOSELECT=YES,URI="audio-it.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="audio-en.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="text",NAME="Italiano",LANGUAGE="it",DEFAULT=NO,AUTOSELECT=YES,URI="subtitles.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=320x180,CODECS="avc1.42c00c,mp4a.40.2",AUDIO="audio",SUBTITLES="text"
video180.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=420000,RESOLUTION=640x360,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio",SUBTITLES="text"
video360.m3u8
''')
    (root / 'subtitles.m3u8').write_text('#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:180\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:180,\nsubtitles.vtt\n#EXT-X-ENDLIST\n')
    (root / 'subtitles.vtt').write_text('WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:0\n\n00:00:00.000 --> 00:03:00.000\nSottotitoli di prova\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--tv', required=True)
    parser.add_argument('--font', type=float, default=1.0, choices=[1.0, 1.3, 2.0])
    parser.add_argument('--output', type=Path, default=ROOT / 'analysis/playback-options')
    args = parser.parse_args()
    if args.phone == args.tv:
        raise ValueError('Two separate disposable emulators are required')
    devices = [Device(args.adb, serial, args.output) for serial in [args.phone, args.tv]]
    for device in devices:
        if device.run('shell', 'getprop', 'ro.hardware').strip() not in {b'ranchu', b'goldfish'}:
            raise ValueError('Disposable emulator required')
    media(args.output / 'media')
    class QuietHandler(SimpleHTTPRequestHandler):
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(args.output / 'media')))
    Thread(target=server.serve_forever, daemon=True).start()
    def check(device, mode):
        font = device.run('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        try:
            device.stop()
            for apk in [ROOT/'app/build/outputs/apk/debug/app-debug.apk', ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
                device.run('install', '--no-incremental', '-r', '-t', str(apk))
            fixture(device, server.server_port)
            device.run('shell', 'settings', 'put', 'system', 'font_scale', str(args.font))
            device.key('KEYCODE_WAKEUP'); device.run('logcat', '-c')
            result = subprocess.run([args.adb, '-s', device.serial, 'shell', 'am', 'instrument', '-w',
                                     '-e', 'operation', 'playback-options', '-e', 'mode', mode, '-e', 'fixtureRoot',
                                     f'http://10.0.2.2:{server.server_port}', PACKAGE+'.test/it.lumetv.app.home.HomeInspection'],
                                    capture_output=True, text=True, timeout=240)
            (device.output/'instrumentation.log').write_text(result.stdout+result.stderr)
            (device.output/'logcat.txt').write_bytes(device.run('logcat', '-d'))
            device.capture('last.png')
            for name in ['01-main', '02-preferences', '03-quality', '04-timer', '05-landscape', '06-radio']:
                data = device.read('cache/playback-options/'+name+'.png')
                if data:
                    (device.output/(name+'.png')).write_bytes(data)
            match = re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$', result.stdout, re.M)
            if not match:
                raise RuntimeError(device.serial+': '+(result.stdout+result.stderr)[-2000:])
            report = json.loads(match[1])
            assert report['passed']
            (device.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
            print(device.serial, mode, len(report['checks']), 'checks passed', flush=True)
            return report
        finally:
            device.stop()
            device.run('shell', 'settings', 'put', 'system', 'font_scale', font)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            reports = list(pool.map(lambda pair: check(*pair), zip(devices, ['phone', 'tv'])))
        (args.output/'validation.json').write_text(json.dumps({'passed': True, 'font': args.font, 'reports': reports}, indent=2)+'\n')
    finally:
        server.shutdown(); server.server_close()


if __name__ == '__main__':
    main()
