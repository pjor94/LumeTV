#!/usr/bin/env python3
"""Reset disposable emulators and verify 500k/source, 1m total with local synthetic media only."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import functools
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
from threading import Thread
import time
from smoke_playback import Device, ROOT, PACKAGE, fixture


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True)
    parser.add_argument('--phone',required=True)
    parser.add_argument('--tv',required=True)
    parser.add_argument('--video',type=Path,required=True)
    parser.add_argument('--count',type=int,default=500_000)
    parser.add_argument('--output',type=Path,default=ROOT/'analysis/large-playlists-fix/native')
    parser.add_argument('--ui-only',action='store_true',help='Reuse the completed native reports and installed APKs')
    parser.add_argument('--refresh-only',action='store_true',help='Update the installed debug APKs and refresh the retained 500k source')
    args=parser.parse_args()
    if not 50_000<args.count<=500_000 or args.count%10:raise ValueError('Use a multiple of ten between 50010 and 500000')
    if args.phone==args.tv:raise ValueError('Two independent disposable emulators are required')
    (args.output/'validation.json').unlink(missing_ok=True)
    resources=args.output/'fixtures';resources.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(args.video,resources/'video.mp4')
    class Handler(SimpleHTTPRequestHandler):
        def log_message(self,*unused):pass
    server_record=args.output/'fixture-server.json'
    requested_port=json.loads(server_record.read_text())['port'] if args.ui_only or args.refresh_only else 0
    server=ThreadingHTTPServer(('127.0.0.1',requested_port),functools.partial(Handler,directory=str(resources)))
    port=server.server_port
    server_record.write_text(json.dumps({'port':port})+'\n')
    root=f'http://10.0.2.2:{port}'
    with (resources/'large.m3u').open('w') as out:
        out.write('#EXTM3U\n')
        for i in range(args.count):
            out.write(f'#EXTINF:{120 if i%10==0 else -1} tvg-id="B{i}" tvg-chno="{i+1}" group-title="Gruppo {i%50}",Carico B voce {i+1}\n{root}/video.mp4?source=B&id={i}\n')
    Thread(target=server.serve_forever,daemon=True).start()

    def check(serial,mode):
        d=Device(args.adb,serial,args.output)
        (d.output/'report.json').unlink(missing_ok=True)
        if d.run('shell','getprop','ro.hardware').strip() not in {b'ranchu',b'goldfish'}:raise ValueError('Emulator hardware required')
        assert d.run('shell','getprop','sys.boot_completed').strip()==b'1'
        if args.refresh_only:
            d.stop()
            d.run('install','--no-incremental','-r',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
            d.run('install','--no-incremental','-r','-t',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
            command=[args.adb,'-s',serial,'shell','am','instrument','-w','-e','operation','playlist-large','-e','phase','refresh',
                     '-e','fixtureRoot',root,'-e','count',str(args.count),PACKAGE+'.test/it.lumetv.app.home.HomeInspection']
            run=subprocess.run(command,capture_output=True,text=True,timeout=2400)
            (d.output/'refresh-instrumentation.log').write_text(run.stdout+run.stderr)
            match=re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$',run.stdout,re.M)
            if not match:raise RuntimeError((run.stdout+run.stderr)[-4000:])
            refresh=json.loads(match[1]);assert refresh['passed']
            refresh['apk_sha256']=hashlib.sha256((ROOT/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()
            (d.output/'refresh.json').write_text(json.dumps(refresh,indent=2)+'\n')
            native=json.loads((d.output/'native.json').read_text())
        elif not args.ui_only:
            d.run('uninstall',PACKAGE,check=False)
            d.run('uninstall',PACKAGE+'.test',check=False)
            d.run('install','--no-incremental',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
            d.run('install','--no-incremental','-t',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
            d.run('shell','run-as',PACKAGE,'mkdir','-p','files','shared_prefs','cache')
            fixture(d,port)
            d.stop()
            command=[args.adb,'-s',serial,'shell','am','instrument','-w','-e','operation','playlist-large','-e','fixtureRoot',root,
                     '-e','count',str(args.count),PACKAGE+'.test/it.lumetv.app.home.HomeInspection']
            run=subprocess.run(command,capture_output=True,text=True,timeout=2400)
            (d.output/'instrumentation.log').write_text(run.stdout+run.stderr)
            match=re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$',run.stdout,re.M)
            if not match:raise RuntimeError((run.stdout+run.stderr)[-4000:])
            native=json.loads(match[1]);assert native['passed']
            (d.output/'native.json').write_text(json.dumps(native,indent=2)+'\n')
        else:
            native=json.loads((d.output/'native.json').read_text())
            d.stop()
            d.run('install','--no-incremental','-r',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
        print(f'{serial}: native passed, {native["combinedEntriesTested"]} entries',flush=True)
        d.launch('catalog')
        d.wait('first catalog page is loaded',lambda:any('Carico B voce 2' in t for t in d.texts()),timeout=60)
        d.capture('00-first-page.png')
        d.wait('manual pagination controls are absent',lambda:all(n.get('content-desc') not in {'Pagina precedente','Pagina successiva'} for n in d.nodes()))
        first_labels=[t for t in d.texts() if 'Carico B voce' in t]
        if mode=='phone':
            bounds=next(n.get('bounds') for n in d.nodes() if n.get('class')=='androidx.recyclerview.widget.RecyclerView')
            left,top,right,bottom=map(int,re.findall(r'\d+',bounds))
            d.run('shell','input','swipe',str((left+right)//2),str(bottom-35),str((left+right)//2),str(top+35),'350')
        else:
            # Focus an actual channel, then use only the remote to move down.
            for unused in range(50):
                nodes=d.nodes()
                if any(n.get('focused')=='true' and 'Carico B voce' in n.get('content-desc','') for n in nodes):break
                d.key(61)
            else:raise AssertionError('TV catalog cannot receive focus')
            d.run('shell','input','keyevent',*(['20']*8))
        d.wait('catalog scrolls continuously',lambda:[t for t in d.texts() if 'Carico B voce' in t]!=first_labels)
        d.capture('01-continuous-scroll.png')
        if mode=='tv':d.click('Cerca')
        fields=[n for n in d.nodes() if n.get('class')=='android.widget.EditText']
        assert fields
        x1,y1,x2,y2=map(int,re.findall(r'\d+',fields[0].get('bounds')))
        d.run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
        d.run('shell','input','text',f'voce%s{args.count}')
        if mode=='tv':
            d.key(4)
            positive=next(n for n in d.nodes() if n.get('resource-id')=='android:id/button1')
            x1,y1,x2,y2=map(int,re.findall(r'\d+',positive.get('bounds')))
            d.run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
        else:d.key(4)
        d.wait('search reaches the last source entry',lambda:any(native['lastName'] in t for t in d.texts()))
        d.capture('02-last-entry-search.png')
        d.run('logcat','-c');d.click(next(t for t in d.texts() if t.endswith(native['lastName'])))
        d.wait('last source entry starts local video',lambda:d.playing(native['lastName']),timeout=45)
        d.wait('last source entry produces a frame',lambda:b'first_frame' in d.run('logcat','-d','-s','LumePlayer:I'),timeout=30)
        d.stop();d.launch('channel/'+native['lastChannel'])
        d.wait('cold deep link resolves outside the first page',lambda:d.playing(native['lastName']),timeout=45)
        d.launch('catalog');d.run('shell','settings','put','system','font_scale','1.3')
        d.wait('catalog remains usable at 130 percent font',lambda:any(n.get('class')=='androidx.recyclerview.widget.RecyclerView' for n in d.nodes()) and all(n.get('content-desc') not in {'Pagina precedente','Pagina successiva'} for n in d.nodes()),timeout=45)
        d.capture('03-font-130.png');d.stop()
        report={'passed':True,'mode':mode,'native':native,'ui_checks':d.checks,'physical_devices':False,
                'apk_sha256':hashlib.sha256((ROOT/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()}
        (d.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print(f'{serial}: native and UI passed',flush=True)
        return report
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(check,args.phone,'phone'),pool.submit(check,args.tv,'tv')]
            reports=[];errors=[]
            for future in futures:
                try:reports.append(future.result())
                except Exception as error:errors.append(repr(error));print('FAILED',repr(error),flush=True)
            if errors:raise RuntimeError('; '.join(errors))
        (args.output/'validation.json').write_text(json.dumps({'passed':True,'reports':reports},indent=2)+'\n')
    finally:server.shutdown()


if __name__=='__main__':main()
