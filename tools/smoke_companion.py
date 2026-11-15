#!/usr/bin/env python3
"""Exercise pairing and playback between two disposable emulators over real pinned TLS.

The emulator networks are isolated: an adb TCP forward carries the unchanged encrypted
connection through localhost. No production pairing, trust or command checks are bypassed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
from threading import Event, Lock, Thread
import time

from smoke_playback import Device, ROOT, PACKAGE, fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--phone', required=True)
    parser.add_argument('--tv', required=True)
    parser.add_argument('--video', type=Path, required=True)
    parser.add_argument('--font', type=float, default=1.3)
    parser.add_argument('--output', type=Path, default=ROOT/'analysis/companion/native')
    args = parser.parse_args()
    if args.phone == args.tv:
        raise ValueError('Separate disposable emulators required')
    devices = {role: Device(args.adb, serial, args.output) for role, serial in [('tv', args.tv), ('phone', args.phone)]}
    for device in devices.values():
        if device.run('shell', 'getprop', 'ro.hardware').strip() not in {b'ranchu', b'goldfish'}:
            raise ValueError('Disposable emulators only')
    ready = Event()
    actions = queue.Queue()
    pending = {}
    invite = {}
    lock = Lock()
    forwarded = 0

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def reply(self, data, status=200):
            body = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_GET(self):
            if self.path == '/actions':
                try:
                    return self.reply(actions.get_nowait())
                except queue.Empty:
                    return self.reply({})
            if self.path == '/invite':
                with lock:
                    return self.reply(dict(invite))
            if self.path.split('?')[0] == '/video.mp4':
                self.path = '/'+args.video.name
                try:
                    return super().do_GET()
                except (BrokenPipeError, ConnectionResetError):
                    return
            self.send_error(404)
        def do_POST(self):
            data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path == '/ready':
                with lock:
                    invite.update(data)
                ready.set()
                return self.reply({'ok': True})
            if self.path == '/done':
                with lock:
                    entry = pending.get(data['request'])
                    if 'invite' in data:
                        data['invite']['port'] = forwarded
                        invite.update(data['invite'])
                    if entry:
                        entry['result'] = data
                        entry['event'].set()
                return self.reply({'ok': True})
            if self.path == '/control':
                with lock:
                    request = len(pending)+1
                    entry = pending[request] = {'event': Event()}
                actions.put({'request': request, 'action': data['action']})
                if not entry['event'].wait(28):
                    return self.reply({'ok': False, 'error': 'TV test action timed out'}, 504)
                return self.reply(entry['result'])
            self.send_error(404)

    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=str(args.video.resolve().parent)))
    server.daemon_threads = True
    Thread(target=server.serve_forever, daemon=True).start()
    apk = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    originals = {}
    def prepare(role):
        device = devices[role]
        originals[role] = device.run('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        device.stop()
        for path in [apk, ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            device.run('install', '--no-incremental', '-r', '-t', str(path))
        fixture(device, server.server_port)
        device.run('shell', 'settings', 'put', 'system', 'font_scale', str(args.font))
        device.run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        device.run('shell', 'logcat', '-c')
        device.run('shell', 'run-as', PACKAGE, 'rm', '-rf', 'cache/companion-inspection')
    def inspect(role):
        device = devices[role]
        result = subprocess.run([args.adb, '-s', device.serial, 'shell', 'am', 'instrument', '-w', '-e', 'operation', 'companion',
                                 '-e', 'mode', role, '-e', 'fixtureRoot', f'http://10.0.2.2:{server.server_port}',
                                 PACKAGE+'.test/it.lumetv.app.home.HomeInspection'], capture_output=True, text=True, timeout=330)
        (device.output/'instrumentation.log').write_text(result.stdout+result.stderr)
        match = re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$', result.stdout, re.M)
        if not match:
            raise RuntimeError(role+': '+(result.stdout+result.stderr)[-1600:])
        report = json.loads(match[1])
        report.update(apk_sha256=digest, font=args.font, physical_devices=False, transport='TLS through emulator TCP forward')
        assert report['passed']
        (device.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')
        return report
    reports = []
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(prepare, devices))
            tv = pool.submit(inspect, 'tv')
            deadline = time.monotonic()+65
            while not ready.wait(.5):
                if tv.done():
                    tv.result()
                if time.monotonic()>deadline:
                    raise TimeoutError('TV listener not ready')
            forwarded = int(devices['tv'].run('forward', 'tcp:0', 'tcp:'+str(invite['port'])).strip())
            with lock:
                invite['port'] = forwarded
            phone = pool.submit(inspect, 'phone')
            try:
                reports.append(phone.result())
            finally:
                if not tv.done():
                    actions.put({'request': -1, 'action': 'exit'})
            reports.append(tv.result())
        (args.output/'validation.json').write_text(json.dumps({'passed': True, 'reports': reports}, indent=2)+'\n')
        for report in reports:
            print(report['mode'], len(report['checks']), 'checks passed', flush=True)
    finally:
        for role, device in devices.items():
            logs = device.run('logcat', '-d').decode(errors='replace')
            (device.output/'logcat.txt').write_text(logs)
            paths = device.run('shell', 'run-as', PACKAGE, 'ls', 'cache/companion-inspection', check=False).decode().splitlines()
            for name in paths:
                if re.fullmatch(r'[a-z-]+\.png|progress\.json', name):
                    data = device.read('cache/companion-inspection/'+name)
                    if data:
                        (device.output/name).write_bytes(data)
            device.stop()
            if role in originals:
                if originals[role] == 'null':
                    device.run('shell', 'settings', 'delete', 'system', 'font_scale')
                else:
                    device.run('shell', 'settings', 'put', 'system', 'font_scale', originals[role])
        if forwarded:
            devices['tv'].run('forward', '--remove', 'tcp:'+str(forwarded), check=False)
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
