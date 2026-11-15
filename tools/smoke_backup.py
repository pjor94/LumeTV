#!/usr/bin/env python3
"""Backup and restore through real app pages and document-result contracts on disposable emulators."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import re
import subprocess
from threading import Thread
from smoke_playback import Device, ROOT, PACKAGE, fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--tv', required=True)
    parser.add_argument('--font', type=float, choices=[1.0, 1.3, 2.0], default=1.3)
    parser.add_argument('--output', type=Path, default=ROOT/'analysis/backup/native')
    args = parser.parse_args()
    if args.phone == args.tv:
        raise ValueError('Two separate disposable emulators are required')
    devices = [Device(args.adb, serial, args.output) for serial in [args.phone, args.tv]]
    for device in devices:
        if device.run('shell', 'getprop', 'ro.hardware').strip() not in {b'ranchu', b'goldfish'}:
            raise ValueError('Disposable emulator required')
    class QuietHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_error(404)
        def log_message(self, *_):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), QuietHandler)
    server.daemon_threads = True
    Thread(target=server.serve_forever, daemon=True).start()
    apk = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    def check(device, mode):
        original = device.run('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        try:
            device.stop()
            for path in [apk, ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
                device.run('install', '--no-incremental', '-r', '-t', str(path))
            device.run('shell', 'run-as', PACKAGE+'.test', '/system/bin/pm', 'enable',
                       PACKAGE+'.test/it.lumetv.app.playlists.BackupDocumentActivity')
            fixture(device, server.server_port)
            device.run('shell', 'settings', 'put', 'system', 'font_scale', str(args.font))
            device.run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            device.run('shell', 'run-as', PACKAGE, 'rm', '-rf', 'cache/backup-inspection')
            device.run('logcat', '-c')
            result = subprocess.run([args.adb, '-s', device.serial, 'shell', 'am', 'instrument', '-w',
                                     '-e', 'operation', 'backup', '-e', 'mode', mode, '-e', 'fixtureRoot',
                                     f'http://10.0.2.2:{server.server_port}', PACKAGE+'.test/it.lumetv.app.home.HomeInspection'],
                                    capture_output=True, text=True, timeout=360)
            (device.output/'instrumentation.log').write_text(result.stdout+result.stderr)
            logs = device.run('logcat', '-d').decode(errors='replace')
            (device.output/'logcat.txt').write_text(logs)
            for name in ['00-overview', '01-password', '02-preview', '03-restored', '04-landscape', 'failure']:
                data = device.read('cache/backup-inspection/'+name+'.png')
                if data:
                    (device.output/(name+'.png')).write_bytes(data)
            progress = device.read('cache/backup-inspection/progress.json')
            if progress:
                (device.output/'progress.json').write_bytes(progress)
            match = re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$', result.stdout, re.M)
            if not match:
                raise RuntimeError(device.serial+': '+(result.stdout+result.stderr)[-2000:])
            report = json.loads(match[1])
            assert report['passed'] and 'FATAL EXCEPTION' not in logs
            report.update(font=args.font, apk_sha256=digest, physical_devices=False)
            (device.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
            print(device.serial, mode, len(report['checks']), 'checks passed', flush=True)
            return report
        finally:
            device.stop()
            device.run('shell', 'run-as', PACKAGE+'.test', '/system/bin/pm', 'disable',
                       PACKAGE+'.test/it.lumetv.app.playlists.BackupDocumentActivity', check=False)
            if original == 'null':
                device.run('shell', 'settings', 'delete', 'system', 'font_scale')
            else:
                device.run('shell', 'settings', 'put', 'system', 'font_scale', original)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            reports = list(pool.map(lambda pair: check(*pair), zip(devices, ['phone', 'tv'])))
        (args.output/'validation.json').write_text(json.dumps({'passed':True, 'reports':reports}, indent=2)+'\n')
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
