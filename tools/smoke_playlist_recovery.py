#!/usr/bin/env python3
"""Verify recoverable M3U imports through real pages on disposable phone/TV emulators, without playback."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import re
import subprocess
from threading import Lock, Thread
from smoke_playback import Device, ROOT, PACKAGE, fixture


def recovery_playlist(root):
    return f'''#EXTM3U
---- SPORT ----
#KODIPROP:inputstream.adaptive.manifest_type=mpd
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
#EXTVLCOPT:http-user-agent=RecoveryPlayer
#EXTHTTP:{{"X-Diag":"private-value"}}
#EXTINF:-1 tvg-id="secure",Canale protetto
{root}/test.mpd
#EXTINF:-1 tvg-id="normal",Normale
{root}/normal.m3u8
Rigaestranea
{root}/orphan.mp4
#EXTINF:-1,Voce incompleta
#EXTINF:-1 tvg-id="after",Ultima
{root}/last.m3u8
'''.encode()


def compatibility_playlist(root):
    routes=[('Nove','Nove'),('Real Time','RealTime'),('Food Network','FoodNetwork'),('Discovery','Discovery'),('Giallo','Giallo'),('DMAX','DMAX'),('HGTV Italia','HGTV'),('Motor Trend','MotorTrend')]
    special={0:'K2',1:'Frisbee',2:'Rai 4K',3:'RSI LA1 HD',4:'RSI LA2 HD'}
    hints=[i for i in range(250) if i not in range(5,13)][:67];hls={2,*hints[-9:]}
    pairs=','.join(f'{i:032x}:{i+10:032x}' for i in range(6))
    lines=['#EXTM3U']
    for i in range(250):
        name=special.get(i,f'Canale {i}')
        if i in range(5,13):name=routes[i-5][0]
        if i in hints:lines.append('#KODIPROP:inputstream.adaptive.file_type='+('m3u8' if i in hls else 'mpd'))
        if i in [0,1]:lines.append('#KODIPROP:inputstream.adaptive.license_key='+pairs)
        if i==2:lines.append('#KODIPROP:inputstream.adaptive.stream_headers=HbbTV/1.6.1')
        artwork=f' group-logo="{root}/group.png"' if i<16 else ''
        epg='rsila'+str(i-2) if i in [3,4] else f'fixture{i}'
        lines.append(f'#EXTINF:-1 tvg-id="{epg}"{artwork},{name}')
        lines.append('localplay='+routes[i-5][1] if i in range(5,13) else f'{root}/channel/{i}.'+('mpd' if i in hints and i not in hls else 'm3u8'))
    for name,prop in [('Bad DRM','inputstream.adaptive.license_type=playready'),('Bad Headers','inputstream.adaptive.stream_headers=not-a-header'),('Bad Addon','inputstreamaddon=unsupported')]:
        lines.extend([f'#EXTINF:-1,{name}','#KODIPROP:'+prop,root+'/bad.mpd'])
    lines.extend(['EXTINF:-1,Modello']+[f'[template_{i}]' for i in range(25)])
    lines.extend(['#KODIPROP:inputstreamaddon=inputstream.adaptive','#KODIPROP:inputstream.adaptive.manifest_type=mpd','#KODIPROP:inputstream.adaptive.license_type=clearkey','[template_end]'])
    return ('\n'.join(lines)+'\n').encode()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True);parser.add_argument('--phone',required=True);parser.add_argument('--tv',required=True)
    parser.add_argument('--font',type=float,default=1.0)
    parser.add_argument('--compatibility',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'analysis/import-recovery/native')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'validation.json').unlink(missing_ok=True)
    if args.phone==args.tv:raise ValueError('Two independent disposable emulators required')
    devices=[Device(args.adb,args.phone,args.output),Device(args.adb,args.tv,args.output)]
    for device in devices:
        if device.run('shell','getprop','ro.hardware').strip() not in {b'ranchu',b'goldfish'}:raise ValueError('Disposable emulator required')
        if device.run('shell','getprop','sys.boot_completed').strip()!=b'1':raise ValueError('Boot the emulators first')
    requests=[];lock=Lock()
    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self):
            with lock:requests.append('HEAD '+self.path)
            self.send_error(405)
        def do_POST(self):
            with lock:requests.append('POST '+self.path)
            self.send_error(405)
        def do_GET(self):
            with lock:requests.append(self.path)
            body=(compatibility_playlist if args.compatibility else recovery_playlist)(f'http://10.0.2.2:{self.server.server_port}') if self.path==('/compatibility.m3u' if args.compatibility else '/recovery.m3u') else b''
            self.send_response(200 if body else 404);self.send_header('Content-Type','application/vnd.apple.mpegurl')
            self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def log_message(self,*unused):pass
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True;Thread(target=server.serve_forever,daemon=True).start()
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';sha=hashlib.sha256(apk.read_bytes()).hexdigest()
    def check(device,mode):
        device.stop();device.run('install','--no-incremental','-r',str(apk))
        device.run('install','--no-incremental','-r','-t',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
        device.run('shell','run-as',PACKAGE,'mkdir','-p','files','shared_prefs','cache')
        device.run('shell','run-as',PACKAGE,'rm','-rf','cache/playlist-recovery','cache/playlist-recovery-progress.json')
        fixture(device,server.server_port);device.run('shell','settings','put','system','font_scale',str(args.font))
        device.run('shell','input','keyevent','KEYCODE_WAKEUP');device.run('logcat','-c')
        try:
            run=subprocess.run([args.adb,'-s',device.serial,'shell','am','instrument','-w','-e','operation','playlist-import-compatibility' if args.compatibility else 'playlist-import-recovery',
                '-e','fixtureRoot',f'http://10.0.2.2:{server.server_port}','-e','mode',mode,PACKAGE+'.test/it.lumetv.app.home.HomeInspection'],capture_output=True,text=True,timeout=240)
            (device.output/'instrumentation.log').write_text(run.stdout+run.stderr)
            logs=device.run('logcat','-d').decode(errors='replace');(device.output/'logcat.txt').write_text(logs)
            for name in ['01-preview','02-status','03-restored-status','failure']:
                data=device.read(f'cache/playlist-recovery/{name}.png')
                if data:(device.output/f'{name}.png').write_bytes(data)
            match=re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$',run.stdout,re.M)
            if not match:
                progress=device.read('cache/playlist-recovery-progress.json')
                if progress:(device.output/'progress.json').write_bytes(progress)
                raise RuntimeError((run.stdout+run.stderr)[-2000:])
            report=json.loads(match[1]);assert report['passed'] and 'FATAL EXCEPTION' not in logs
            report.update(font=args.font,apk_sha256=sha,physical_devices=False)
            (device.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
            print(f'{device.serial}: PASS {len(report["checks"])} checks, font {args.font}',flush=True)
            return report
        finally:
            device.stop();device.run('shell','settings','put','system','font_scale','1.0')
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(check,device,mode) for device,mode in zip(devices,['phone','tv'])]
            reports=[];failures=[]
            for future in futures:
                try:reports.append(future.result())
                except Exception as error:failures.append(str(error));print('FAILED',str(error),flush=True)
            if failures:raise RuntimeError('; '.join(failures))
        assert requests and all(path==('/compatibility.m3u' if args.compatibility else '/recovery.m3u') for path in requests),'Playback or unexpected request detected'
        (args.output/'validation.json').write_text(json.dumps({'passed':True,'reports':reports,'requests':requests,'playback_requests':0},indent=2)+'\n')
    finally:server.shutdown()


if __name__=='__main__':main()
