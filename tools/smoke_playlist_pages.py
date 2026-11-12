#!/usr/bin/env python3
"""Exercise retained playlist pages on disposable emulators with owned local fixtures."""
import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
from threading import Thread
import time

from smoke_playback import FILES, PACKAGE, ROOT, fixture
from smoke_playlist_compatibility import PlaylistDevice


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["phone", "tv"], required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/playlist-pages")
    parser.add_argument("--font", type=float, default=1.0)
    args = parser.parse_args()
    d = PlaylistDevice(args.adb, args.serial, args.output / ("font-130" if args.font > 1 else "normal"))
    (d.output / "report.json").unlink(missing_ok=True)
    if d.run("shell", "getprop", "ro.hardware").strip() not in {b"ranchu", b"goldfish"}:
        raise ValueError("Disposable emulators required")
    requests = []
    now = datetime.now(timezone.utc)
    def stamp(value): return value.strftime("%Y%m%d%H%M%S %z")

    class Handler(BaseHTTPRequestHandler):
        def send(self, body, mime="audio/x-mpegurl"):
            self.send_response(200); self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            root = f"http://10.0.2.2:{self.server.server_port}"
            if self.path == "/list.m3u":
                requests.append({"kind": "playlist", "header": self.headers.get("X-List")})
                self.send((f'#EXTM3U url-tvg="{root}/guide.xml"\n#EXTINF:-1 tvg-id="one",Canale uno\n{root}/one.mp4\n'
                           f'#EXTINF:-1 tvg-id="two",Canale due\n{root}/two.mp4\n').encode()); return
            if self.path == "/guide.xml":
                requests.append({"kind": "guide", "list_header_absent": self.headers.get("X-List") is None})
                self.send((f'<tv><channel id="one"><display-name>Canale uno</display-name></channel>'
                           f'<channel id="manual"><display-name>Nome guida</display-name></channel>'
                           f'<programme channel="one" start="{stamp(now-timedelta(hours=1))}" stop="{stamp(now+timedelta(hours=1))}"><title>Programma di prova</title></programme>'
                           f'<programme channel="manual" start="{stamp(now-timedelta(hours=1))}" stop="{stamp(now+timedelta(hours=1))}"><title>Programma manuale</title></programme></tv>').encode(), "application/xml"); return
            if self.path == "/slow.m3u":
                self.send_response(200); self.end_headers()
                try:
                    self.wfile.write(b"#EXTM3U\n"); self.wfile.flush()
                    for _ in range(100):
                        self.wfile.write(b"# slow fixture\n"); self.wfile.flush(); time.sleep(.2)
                except (BrokenPipeError, ConnectionResetError): pass
                return
            if self.path == "/broken.m3u": self.send(b"not a playlist"); return
            self.send(b"owned local fixture", "text/plain")
        def log_message(self, *unused): pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    Thread(target=server.serve_forever, daemon=True).start()
    d.stop()
    backup = {path: d.read(path) for path in FILES}
    private = {}
    for directory in ["files/personal-playlists", "files/personal-guides"]:
        for path in d.run("shell", "run-as", PACKAGE, "find", directory, "-type", "f", check=False).decode().splitlines():
            if re.fullmatch(r"files/personal-(?:playlists|guides)/[A-Za-z0-9_.-]+", path): private[path] = d.read(path)
    private["files/personal-watch.sqlite"] = d.read("files/personal-watch.sqlite")
    settings = {key:d.run("shell","settings","get","system",key).decode().strip() for key in ["font_scale","user_rotation","accelerometer_rotation"]}
    try:
        d.run("shell", "settings", "put", "system", "font_scale", str(args.font))
        if args.mode == "phone":
            d.run("shell","settings","put","system","accelerometer_rotation","0");d.run("shell","settings","put","system","user_rotation","0")
        fixture(d, server.server_port)
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists", "files/personal-guides", "files/personal-watch.sqlite", "cache/playlist-pages")
        output = subprocess.run([args.adb,"-s",args.serial,"shell", "am", "instrument", "-w", "-e", "operation", "playlist-pages", "-e", "mode", args.mode,
                       "-e", "fixtureRoot", f"http://10.0.2.2:{server.server_port}", PACKAGE+".test/it.lumetv.app.home.HomeInspection"], capture_output=True, check=True, timeout=180).stdout.decode(errors="replace")
        (d.output/"native-inspection.txt").write_text(output)
        for path in d.run("shell", "run-as", PACKAGE, "find", "cache/playlist-pages", "-name", "*.png", check=False).decode().splitlines():
            if re.fullmatch(r"cache/playlist-pages/[A-Za-z0-9_.-]+.png", path): (d.output/Path(path).name).write_bytes(d.read(path))
        match = re.search(r"(?m)^INSTRUMENTATION_RESULT: snapshot=(.+)$", output)
        if not match:
            d.capture("failure.png")
            raise AssertionError("Native pages inspection failed: "+output[-2500:])
        result = json.loads(match[1]); assert result["passed"]
        assert all(r["list_header_absent"] for r in requests if r["kind"] == "guide")
        assert any(r["kind"]=="playlist" and r["header"]=="ui-fixture" for r in requests)
        result.update(font=args.font, requests=requests)
        (d.output/"report.json").write_text(json.dumps(result, indent=2)+"\n")
        (d.output/"failure.png").unlink(missing_ok=True)
        print("PASS", args.serial, len(result["checks"]), "native page checks; font", args.font, flush=True)
    finally:
        d.stop()
        for key,value in settings.items(): d.run("shell","settings","put","system",key,value)
        d.run("shell","run-as",PACKAGE,"rm","-rf","files/personal-playlists","files/personal-guides","files/personal-watch.sqlite")
        d.run("shell","run-as",PACKAGE,"mkdir","-p","files/personal-playlists","files/personal-guides")
        for path,data in private.items(): d.write(path,data)
        for path,data in backup.items(): d.write(path,data)
        server.shutdown();server.server_close()


if __name__ == "__main__": main()
