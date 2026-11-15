#!/usr/bin/env python3
"""Check channel management and phone navigation on two disposable emulators, outside CI."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
from threading import Thread
from smoke_playback import Device, PACKAGE, ROOT, fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--tv', required=True)
    parser.add_argument('--font', type=float, choices=[1.0, 1.3, 2.0], default=1.0)
    parser.add_argument('--count', type=int, choices=[450, 100000], default=450)
    parser.add_argument('--output', type=Path, default=ROOT/'analysis/channel-personalization')
    args = parser.parse_args()
    if args.phone == args.tv:
        raise ValueError('Two separate disposable emulators are required')
    devices = [Device(args.adb, serial, args.output) for serial in [args.phone, args.tv]]
    for device in devices:
        if device.run('shell', 'getprop', 'ro.hardware').strip() not in {b'ranchu', b'goldfish'}:
            raise ValueError('Disposable emulator required')
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(SimpleHTTPRequestHandler, directory=str(args.output)))
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
            result = subprocess.run([args.adb, '-s', device.serial, 'shell', 'am', 'instrument', '-w', '-e', 'operation',
                                     'channel-personalization', '-e', 'mode', mode, '-e', 'count', str(args.count),
                                     PACKAGE+'.test/it.lumetv.app.home.HomeInspection'], capture_output=True, text=True, timeout=300)
            (device.output/'instrumentation.log').write_text(result.stdout+result.stderr)
            (device.output/'logcat.txt').write_bytes(device.run('logcat', '-d'))
            device.capture('last.png')
            for name in ['01-phone-navigation', '02-favorites', '03-hidden', '04-final-editor', '05-landscape', 'failure']:
                data = device.read('cache/channel-editor/'+name+'.png')
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
