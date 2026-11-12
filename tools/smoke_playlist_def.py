#!/usr/bin/env python3
"""Native personal XMLTV, VOD and replay checks. Owned fixtures and disposable emulators only."""
import argparse
from datetime import datetime, timedelta, timezone
import functools
import gzip
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import lzma
from pathlib import Path
import re
import subprocess
from threading import Thread
import time
from urllib.parse import urlsplit, parse_qs

from smoke_playback import FILES, PACKAGE, ROOT, fixture
from smoke_playlist_compatibility import PlaylistDevice, playlist_actions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["phone", "tv"], required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/personal-playlists-def")
    args = parser.parse_args()
    d = PlaylistDevice(args.adb, args.serial, args.output)
    if d.run("shell", "getprop", "ro.hardware").strip() not in {b"ranchu", b"goldfish"}:
        raise ValueError("Disposable emulator hardware required")
    media = args.output / "media"
    media.mkdir(parents=True, exist_ok=True)
    if not (media / "live.m3u8").exists():
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(args.video.resolve()), "-c", "copy", "-f", "hls", "-hls_time", "4", "-hls_list_size", "0", str(media / "live.m3u8")], check=True)
    requests = []
    now = datetime.now(timezone.utc)
    def stamp(value): return value.strftime("%Y%m%d%H%M%S %z")
    def guide(letter):
        channels = ''.join(f'<channel id="unused-{i}"><display-name>Non richiesto {i}</display-name></channel>' for i in range(3000))
        programmes = ''.join(f'<programme channel="unused-{i}" start="{stamp(now-timedelta(hours=1))}" stop="{stamp(now+timedelta(hours=1))}"><title>Non richiesto</title><desc>{"x"*400}</desc></programme>' for i in range(3000))
        events = [(now-timedelta(days=8), now-timedelta(days=8)+timedelta(hours=1), 'Fuori conservazione'),
                  ((now-timedelta(days=2)).replace(hour=0,minute=0,second=0,microsecond=0), (now-timedelta(days=2)).replace(hour=1,minute=0,second=0,microsecond=0), f'Storia personale {letter}'),
                  (now-timedelta(hours=1), now-timedelta(minutes=30), f'Replay personale {letter}'),
                  (now-timedelta(minutes=1), now+timedelta(minutes=20), f'In onda personale {letter}'),
                  (now+timedelta(minutes=20), now+timedelta(hours=2), f'Prossimo personale {letter}'),
                  (now+timedelta(hours=49), now+timedelta(hours=50), 'Oltre futuro')]
        return ('<?xml version="1.0"?><!DOCTYPE tv SYSTEM "https://fixture.invalid/xmltv.dtd"><tv><channel id="shared"><display-name>Canale personale</display-name></channel>'+channels+
                ''.join(f'<programme channel="shared" start="{stamp(start)}" stop="{stamp(end)}" catchup-id="fixture-event"><title lang="en">English</title><title lang="it">{title}</title><desc>Guida sintetica di proprietà del progetto.</desc></programme>' for start,end,title in events)+programmes+'</tv>').encode()
    guides = {"/guide.xml": guide("A"), "/guide.xml.gz": gzip.compress(guide("B")), "/guide.xml.xz": lzma.compress(guide("C"))}

    class Handler(SimpleHTTPRequestHandler):
        def send(self, body, mime):
            self.send_response(200); self.send_header("Content-Type", mime); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            parsed = urlsplit(self.path); path = parsed.path
            root = f"http://10.0.2.2:{self.server.server_port}"
            if path in {"/catalog.m3u", "/second.m3u", "/third.m3u"}:
                feed = {"/catalog.m3u": "/guide.xml", "/second.m3u": "/guide.xml.gz", "/third.m3u": "/guide.xml.xz"}[path]
                name = {"/catalog.m3u": "A", "/second.m3u": "B", "/third.m3u": "C"}[path]
                text = (f'#EXTM3U url-tvg="{root}{feed}" catchup="default" catchup-source="{root}/archive.mp4?utc={{utc}}&end={{utcend}}&sig=a%2Bb+c" catchup-days="7"\n'
                        f'#EXTINF:-1 tvg-id="shared" tvg-name="Canale personale",Canale personale {name}\n#EXTHTTP:{{"X-Media":"media-fixture"}}\n{root}/live.m3u8\n')
                if path == "/catalog.m3u":
                    text += (f'#EXTINF:180 group-title="Cinema",Video personale\n#EXTHTTP:{{"X-Media":"media-fixture"}}\n{root}/vod.mp4\n'
                             f'#EXTINF:180 group-title="Prove",Video senza seek\n#EXTHTTP:{{"X-Media":"media-fixture"}}\n{root}/vod.mp4|seekable=0\n')
                self.send(text.encode(), "audio/x-mpegurl"); return
            if path in guides:
                good = self.headers.get("X-Guide") == "guide-fixture" and self.headers.get("X-Media") is None
                requests.append({"kind": "guide", "format": path.rsplit('.',1)[-1], "valid_headers": good})
                if not good: self.send_error(403); return
                self.send(guides[path], "application/octet-stream"); return
            if path in {"/vod.mp4", "/archive.mp4"} or path.endswith((".m3u8", ".ts")):
                good = self.headers.get("X-Media") == "media-fixture" and self.headers.get("X-Guide") is None
                requests.append({"kind": "archive" if path=="/archive.mp4" else "media", "valid_headers": good,
                                 "signed_query_intact": "sig=a%2Bb+c" in self.path if path=="/archive.mp4" else True})
                if not good: self.send_error(403); return
                if path in {"/vod.mp4", "/archive.mp4"}: self.send(args.video.read_bytes(), "video/mp4"); return
            super().do_GET()
        def log_message(self, *unused): pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(media.resolve())))
    server.daemon_threads = True
    Thread(target=server.serve_forever, daemon=True).start()
    d.stop()
    backup = {path: d.read(path) for path in FILES}
    private = {}
    for directory in ["files/personal-playlists", "files/personal-guides"]:
        for path in d.run("shell", "run-as", PACKAGE, "find", directory, "-type", "f", check=False).decode().splitlines():
            if not re.fullmatch(r"files/personal-(?:playlists|guides)/[A-Za-z0-9_.-]+", path): continue
            private[path] = d.read(path)
    private["files/personal-watch.sqlite"] = d.read("files/personal-watch.sqlite")
    font = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()
    rotation = d.run("shell", "settings", "get", "system", "user_rotation").decode().strip()
    auto = d.run("shell", "settings", "get", "system", "accelerometer_rotation").decode().strip()

    def click(label, description=False, contains=False):
        key = "content-desc" if description else "text"
        for _ in range(6):
            nodes = playlist_actions(d.nodes(),label,description,contains)
            if nodes:
                left,top,right,bottom=map(int,re.findall(r"\d+",nodes[-1].get("bounds")))
                if right>left and bottom>top:
                    d.run("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2)); return
            d.run("shell", "input", "swipe", "540" if args.mode=="phone" else "1100", "1500" if args.mode=="phone" else "750", "540" if args.mode=="phone" else "1100", "600" if args.mode=="phone" else "330", "250")
        raise AssertionError("Missing UI control: "+label)

    try:
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        if args.mode=="phone":
            d.run("shell", "settings", "put", "system", "accelerometer_rotation", "0");d.run("shell", "settings", "put", "system", "user_rotation", "0")
        fixture(d, server.server_port)
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists", "files/personal-guides", "files/personal-watch.sqlite")
        command = [args.adb,"-s",args.serial,"shell","am","instrument","-w","-e","operation","playlist-def","-e","mode",args.mode,
                   "-e","fixtureRoot",f"http://10.0.2.2:{server.server_port}",PACKAGE+".test/it.lumetv.app.home.HomeInspection"]
        output = subprocess.run(command,capture_output=True,timeout=300,check=True).stdout.decode(errors="replace")
        (d.output/"native-inspection.txt").write_text(output)
        match = re.search(r"(?m)^INSTRUMENTATION_RESULT: snapshot=(.+)$",output)
        if not match: raise AssertionError("Native D/E/F inspection failed: "+output[-2500:])
        native = json.loads(match[1]);assert native["passed"];d.checks+=native["checks"]
        print("PASS",args.serial,"native guide and playback",flush=True)
        d.stop();d.run("logcat","-c");d.launch("channel/"+native["vod"])
        d.wait("VOD restores after process death",lambda:d.playing("Video personale") and b"first_frame" in d.run("logcat","-d","-s","LumePlayer:I"),timeout=35)
        d.key(85);d.wait("VOD transport is inspectable while paused",lambda:d.playing("Video personale",paused=True))
        d.capture("01-vod-transport.png")
        d.launch("catalog");d.wait("catalog opens with personal library entry",lambda:"Video" in d.texts())
        click("Preferiti",description=args.mode=="phone")
        d.wait("saved VOD is accessible in favourites",lambda:"Video personale" in d.texts())
        click("Canali",description=args.mode=="phone")
        if args.mode=="tv":click("Tutti")
        click("Video");d.wait("paged video library is visible",lambda:"Video delle mie liste" in d.texts() and "Video personale" in d.texts())
        d.capture("02-video-library.png");click("Chiudi")
        click("Lista:",contains=True);click("Guida personale A")
        if args.mode=="phone":
            click("Guida",description=True)
            d.wait("personal guide current programme is visible",lambda:"In onda personale A" in d.texts())
            d.capture("03-personal-guide.png")
            click("In onda personale A")
            click("Guarda dall’inizio")
        else:
            click("Guida TV")
            d.wait("personal guide current programme is visible",lambda:any("In onda personale A" in text for text in d.texts()))
            d.capture("03-personal-guide.png")
            click("In onda personale A",contains=True);click("Guarda dall’inizio")
        d.wait("start-over opens through the real programme UI",lambda:d.playing("In onda personale A"),timeout=30)
        d.key(85);d.wait("archive controls remain available while paused",lambda:d.playing("In onda personale A",paused=True))
        d.key(126);d.wait("media play resumes the archive without switching to live",lambda:d.playing("In onda personale A"))
        d.key(127);d.wait("media pause keeps the archive selected",lambda:d.playing("In onda personale A",paused=True))
        d.capture("04-start-over.png")
        click("Torna alla diretta")
        d.wait("return-live UI restores channel metadata",lambda:d.playing("Canale personale A"),timeout=30)
        d.launch("catalog")
        if args.mode=="phone":
            click("Guida",description=True);click("Scegli giorno")
        else:
            click("Guida TV");click("Giorno")
        day=(now-timedelta(days=2)).astimezone().strftime("%a %-d %b")
        # Dates are rendered in Italian; select by the unambiguous numerical day from native UI.
        candidates=[n for n in d.nodes() if re.search(r"\b"+str((now-timedelta(days=2)).day)+r"\b",n.get("text",""))]
        if not candidates: raise AssertionError("Historical guide date missing")
        left,top,right,bottom=map(int,re.findall(r"\d+",candidates[-1].get("bounds")));d.run("shell","input","tap",str((left+right)//2),str((top+bottom)//2))
        d.wait("historical guide window is loaded from native SQLite",lambda:any("Storia personale A" in text for text in d.texts()),timeout=25)
        d.capture("05-historical-guide.png")
        d.launch("catalog");click("Opzioni",description=args.mode=="phone");click("Le mie liste")
        click("Guida personale A",contains=True);click("Guida e archivio");click("Impostazioni guida")
        d.wait("per-source guide configuration is accessible",lambda:"Attiva la guida personale" in d.texts() and any("Indirizzi XMLTV" in text for text in d.texts()))
        d.capture("06-guide-settings.png");click("Annulla")
        d.run("shell","settings","put","system","font_scale","1.3")
        d.stop();d.launch("channel/"+native["vod"])
        d.wait("VOD opens with large accessibility text",lambda:d.playing("Video personale"),timeout=30)
        d.key(85);d.wait("large-text video controls remain available",lambda:d.playing("Video personale",paused=True))
        d.capture("07-vod-font-130.png")
        assert all(r["valid_headers"] for r in requests)
        assert any(r["kind"]=="archive" for r in requests)
        assert all(r["signed_query_intact"] for r in requests if r["kind"]=="archive")
        d.checks += ["guide and media request roles stay separate","archive query encoding preserved","simulator UI inspected at 130 percent text"]
        (d.output/"report.json").write_text(json.dumps({"passed":True,"mode":args.mode,"native":native,"checks":d.checks,"requests":requests},indent=2)+"\n")
    finally:
        d.stop();d.run("shell","settings","put","system","font_scale",font)
        if args.mode=="phone":
            d.run("shell","settings","put","system","user_rotation",rotation);d.run("shell","settings","put","system","accelerometer_rotation",auto)
        d.run("shell","run-as",PACKAGE,"rm","-rf","files/personal-playlists","files/personal-guides","files/personal-watch.sqlite")
        d.run("shell","run-as",PACKAGE,"mkdir","-p","files/personal-playlists","files/personal-guides")
        for path,data in private.items(): d.write(path,data)
        for path,data in backup.items(): d.write(path,data)
        server.shutdown();server.server_close()


if __name__=="__main__": main()
