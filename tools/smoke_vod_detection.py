#!/usr/bin/env python3
"""Exercise VOD detection, local upgrades and settings on disposable phone/TV emulators."""
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
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True);parser.add_argument('--phone',required=True);parser.add_argument('--tv',required=True)
    parser.add_argument('--video',type=Path,required=True);parser.add_argument('--font',type=float,default=1.0)
    parser.add_argument('--output',type=Path,default=ROOT/'analysis/vod-detection/native')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'validation.json').unlink(missing_ok=True)
    if args.phone==args.tv:raise ValueError('Two independent disposable emulators required')
    devices=[Device(args.adb,args.phone,args.output),Device(args.adb,args.tv,args.output)]
    for d in devices:
        if d.run('shell','getprop','ro.hardware').strip() not in {b'ranchu',b'goldfish'}:raise ValueError('Disposable emulator required')
        if d.run('shell','getprop','sys.boot_completed').strip()!=b'1':raise ValueError('Boot the emulators first')
    video=args.video.read_bytes()
    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.send_response(200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(len(video)));self.end_headers()
        def do_GET(self):
            start=0
            if self.headers.get('Range','').startswith('bytes='):
                match=re.match(r'bytes=(\d+)-',self.headers['Range'])
                if match:start=min(int(match[1]),len(video)-1)
            self.send_response(206 if start else 200);self.send_header('Content-Type','video/mp4');self.send_header('Accept-Ranges','bytes')
            if start:self.send_header('Content-Range',f'bytes {start}-{len(video)-1}/{len(video)}')
            self.send_header('Content-Length',str(len(video)-start));self.end_headers();self.wfile.write(video[start:])
        def log_message(self,*unused):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True;Thread(target=server.serve_forever,daemon=True).start()
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';sha=hashlib.sha256(apk.read_bytes()).hexdigest()
    def check(d,mode):
        for name in ['report.json','failure.png','vod-progress.json']:(d.output/name).unlink(missing_ok=True)
        d.stop();d.run('install','--no-incremental','-r',str(apk))
        d.run('install','--no-incremental','-r','-t',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
        d.run('shell','run-as',PACKAGE,'mkdir','-p','files','shared_prefs','cache')
        d.run('shell','run-as',PACKAGE,'rm','-rf','cache/vod-detection','cache/vod-progress.json')
        fixture(d,server.server_port);d.run('shell','settings','put','system','font_scale',str(args.font));d.run('shell','input','keyevent','KEYCODE_WAKEUP');d.run('logcat','-c')
        run=subprocess.run([args.adb,'-s',d.serial,'shell','am','instrument','-w','-e','operation','playlist-vod-detection','-e','fixtureRoot',f'http://10.0.2.2:{server.server_port}',
            '-e','mode',mode,PACKAGE+'.test/it.lumetv.app.home.HomeInspection'],capture_output=True,text=True,timeout=600)
        (d.output/'instrumentation.log').write_text(run.stdout+run.stderr)
        logs=d.run('logcat','-d').decode(errors='replace');(d.output/'logcat.txt').write_text(logs)
        for name in ['00-catalog','01-detection','02-group-rules','03-videos','failure']:
            data=d.read(f'cache/vod-detection/{name}.png')
            if data:(d.output/f'{name}.png').write_bytes(data)
        match=re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$',run.stdout,re.M)
        if not match:
            data=d.read('cache/vod-progress.json')
            if data:(d.output/'vod-progress.json').write_bytes(data)
            raise RuntimeError((run.stdout+run.stderr)[-2500:])
        report=json.loads(match[1]);assert report['passed'] and 'FATAL EXCEPTION' not in logs
        report.update(font=args.font,apk_sha256=sha,physical_devices=False)
        (d.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(f'{d.serial}: PASS {len(report["checks"])} checks, font {args.font}',flush=True)
        d.stop();return report
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(check,d,mode) for d,mode in zip(devices,['phone','tv'])]
            reports=[];failures=[]
            for future in futures:
                try:reports.append(future.result())
                except Exception as error:failures.append(str(error));print('FAILED',str(error),flush=True)
            if failures:raise RuntimeError('; '.join(failures))
        (args.output/'validation.json').write_text(json.dumps({'passed':True,'reports':reports},indent=2)+'\n')
    finally:server.shutdown()


if __name__=='__main__':main()
