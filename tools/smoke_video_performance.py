#!/usr/bin/env python3
"""Measure the real Video library on disposable phone/TV emulators, without opening any stream."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import subprocess
from smoke_playback import Device, ROOT, PACKAGE, fixture


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True);parser.add_argument('--phone',required=True);parser.add_argument('--tv',required=True)
    parser.add_argument('--entries',type=int,nargs='+',default=[10000,50000,100000,500000])
    parser.add_argument('--reuse',action='store_true',help='Reuse the existing matching synthetic dataset; omit import/index timings')
    parser.add_argument('--output',type=Path,default=ROOT/'analysis/video-optimization/performance')
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    if args.phone==args.tv:raise ValueError('Two independent disposable emulators required')
    if any(n not in {10000,50000,100000,500000} for n in args.entries):raise ValueError('Unsupported dataset')
    devices=[Device(args.adb,args.phone,args.output),Device(args.adb,args.tv,args.output)]
    for device in devices:
        if device.run('shell','getprop','ro.hardware').strip() not in {b'ranchu',b'goldfish'}:raise ValueError('Disposable emulator required')
        if device.run('shell','getprop','sys.boot_completed').strip()!=b'1':raise ValueError('Boot the emulators first')
    apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk';sha=hashlib.sha256(apk.read_bytes()).hexdigest()
    def check(device,mode):
        device.stop();device.run('install','--no-incremental','-r',str(apk))
        device.run('install','--no-incremental','-r','-t',str(ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
        device.run('shell','run-as',PACKAGE,'mkdir','-p','files','shared_prefs','cache')
        fixture(device,9);device.run('shell','settings','put','system','font_scale','1.0');device.run('shell','input','keyevent','KEYCODE_WAKEUP')
        reports=[]
        for entries in args.entries:
            device.stop();device.run('logcat','-c')
            device.run('shell','run-as',PACKAGE,'rm','-f','cache/video-performance-progress.json')
            print(f'{device.serial}: START {entries:,}',flush=True)
            command=[args.adb,'-s',device.serial,'shell','am','instrument','-w','-e','operation','playlist-video-performance','-e','entries',str(entries),'-e','mode',mode]
            if args.reuse:command+=['-e','reuse','true']
            run=subprocess.run(command+[PACKAGE+'.test/it.lumetv.app.home.HomeInspection'],capture_output=True,text=True,timeout=1500)
            (device.output/f'{entries}-instrumentation.log').write_text(run.stdout+run.stderr)
            logs=device.run('logcat','-d').decode(errors='replace');(device.output/f'{entries}-logcat.txt').write_text(logs)
            for name in ['home','films','detail']:
                data=device.read(f'cache/video-performance-{name}.png')
                if data:(device.output/f'{entries}-{name}.png').write_bytes(data)
            progress=device.read('cache/video-performance-progress.json')
            if progress:(device.output/f'{entries}-progress.json').write_bytes(progress)
            match=re.search(r'^INSTRUMENTATION_RESULT: snapshot=(.+)$',run.stdout,re.M)
            if not match:raise RuntimeError(f'{device.serial}/{entries}: '+(run.stdout+run.stderr)[-2500:])
            report=json.loads(match[1]);assert report['passed'] and 'FATAL EXCEPTION' not in logs
            report.update(apk_sha256=sha,physical_devices=False,android_api=device.run('shell','getprop','ro.build.version.sdk').decode().strip())
            (device.output/f'{entries}-report.json').write_text(json.dumps(report,indent=2)+'\n');reports.append(report)
            print(f'{device.serial}: PASS {entries:,}; films {report["legacy_films_count_page_ms"]:.1f} → {report["indexed_films_count_page_ms"]:.1f} ms; warm back median '+str(sorted(r['return_ms'] for r in report['warm_navigation'])[5]),flush=True)
        device.stop();return reports
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(check,d,mode) for d,mode in zip(devices,['phone','tv'])]
        reports=[];failures=[]
        for future in futures:
            try:reports.extend(future.result())
            except Exception as error:failures.append(str(error));print('FAILED',str(error),flush=True)
        if failures:raise RuntimeError('; '.join(failures))
    (args.output/'validation.json').write_text(json.dumps({'passed':True,'reports':reports},indent=2)+'\n')


if __name__=='__main__':main()
