#!/usr/bin/env python3
"""Native A/B/C persistence, authenticated includes, AES and 100k capacity. Emulators only."""
import argparse
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
from threading import Thread
import time
import struct
import zlib
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlsplit

from smoke_playback import FILES, PACKAGE, ROOT, fixture
from smoke_playlist_compatibility import KEY, KID, PlaylistDevice, media, playlist_actions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["phone", "tv"], required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/personal-playlists-abc")
    parser.add_argument("--capacity-only", action="store_true", help="Repeat native capacity and UI checks without the compatibility suite")
    args = parser.parse_args()
    d = PlaylistDevice(args.adb, args.serial, args.output)
    if d.run("shell", "getprop", "ro.hardware").strip() not in {b"ranchu", b"goldfish"}:
        raise ValueError("Disposable Android emulator hardware required")
    resources = args.output / "media"
    resources.mkdir(parents=True, exist_ok=True)
    if not args.capacity_only:
        media(resources, args.video.resolve())
    aes = resources / "aes"
    aes.mkdir(exist_ok=True)
    for i in range(0 if args.capacity_only else 4):
        target = aes / f"part{i}.ts"
        if not target.exists():
            subprocess.run(["openssl", "enc", "-aes-128-cbc", "-K", KEY, "-iv", "00" * 16,
                            "-in", str(resources / "hls" / f"part{i:03d}.ts"), "-out", str(target)], check=True)
    requests = []
    generation = {}

    class CDN(SimpleHTTPRequestHandler):
        def do_GET(self):
            good = self.headers.get("X-List") == "fixture-list"
            requests.append({"kind": "included-document", "valid_headers": good})
            body = (f'#EXTM3U catchup-days="3"\n#EXTINF:-1 tvg-id="child" tvg-name="Guida figlia",Figlia\n'
                    '#KODIPROP:inputstream.adaptive.manifest_type=mpd\n'
                    '#KODIPROP:inputstream.adaptive.license_type=clearkey\n'
                    f'#KODIPROP:inputstream.adaptive.license_key={KID}:{KEY}\n'
                    f'http://10.0.2.2:{server.server_port}/dash/manifest.mpd\n').encode()
            self.send_response(200 if good else 403); self.end_headers(); self.wfile.write(body)
        def log_message(self, *unused): pass

    cdn = ThreadingHTTPServer(("127.0.0.1", 0), CDN)

    class Handler(SimpleHTTPRequestHandler):
        def send(self, body, kind="audio/x-mpegurl", status=200):
            self.send_response(status); self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            split = urlsplit(self.path); path = split.path; query = parse_qs(split.query)
            root = f"http://10.0.2.2:{self.server.server_port}"
            if path == "/parent.m3u":
                good = self.headers.get("X-List") == "fixture-list"
                requests.append({"kind": "parent", "valid_headers": good})
                refresh_at=(datetime.now(timezone.utc)+timedelta(days=1)).isoformat().replace("+00:00","Z")
                self.send((f'#EXTM3U tvg-shift="2" catchup-days="7" refresh="PT6H" refresh_at="{refresh_at}"\n'
                           '#EXTINF:-1 tvg-id="root" tvg-name="Guida principale" ch-number="9999",Principale\n'
                           f'{root}/plain.mp4\n#EXTINF:-1 type="playlist",Include\n{root}/redirect-child\n'
                           '#EXTINF:-1 tvg-name="Non supportata",Certificato non supportato\n'
                           '#KODIPROP:inputstream.adaptive.server_certificate=fixture\n'
                           f'{root}/plain.mp4\n').encode(), status=200 if good else 403); return
            if path == "/redirect-child":
                self.send_response(302); self.send_header("Location", f"http://10.0.2.2:{cdn.server_port}/child.m3u"); self.end_headers(); return
            if path == "/large.m3u":
                count = int(query["count"][0]); dataset = query["set"][0]
                assert 1 <= count <= 50_000
                version = generation.get(dataset, 0); generation[dataset] = version + 1
                requests.append({"kind": "capacity", "entries": count, "revision": version})
                self.send_response(200); self.send_header("Content-Type", "audio/x-mpegurl"); self.end_headers()
                try:
                    self.wfile.write(b"#EXTM3U\n")
                    for i in range(count):
                        row = (f'#EXTINF:-1 tvg-id="load-{dataset}-{i}" tvg-chno="{i+1}" group-title="Gruppo {i%10}",Carico {dataset} voce {i+1}\n'
                               f'{root}/plain.mp4?entry={i}&token={"x"*650}&v={version}\n')
                        self.wfile.write(row.encode())
                except (BrokenPipeError, ConnectionResetError): pass
                return
            if path == "/aes.m3u":
                self.send((f'#EXTM3U\n#EXTINF:-1 tvg-id="aes",AES locale\n'
                           '#KODIPROP:inputstream.adaptive.license_key=?token=a%2Bb|X-Key=fixture-key||\n'
                           f'{root}/aes/master.m3u8\n').encode()); return
            if path == "/fixture-logo.png":
                color = self.headers.get("X-Logo")
                colors = {"red": (255, 0, 0), "blue": (0, 0, 255), "green": (0, 255, 0)}
                requests.append({"kind": "artwork", "valid_headers": color in colors})
                rgb = colors.get(color, (0, 0, 0))
                def chunk(kind, data):
                    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
                png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
                png += chunk(b"IDAT", zlib.compress(b"\0" + bytes(rgb))) + chunk(b"IEND", b"")
                self.send(png, "image/png"); return
            if path == "/aes/master.m3u8":
                requests.append({"kind": "aes-manifest", "valid_headers": self.headers.get("X-Key") is None})
                text = f'#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI="{root}/aes-key",IV=0x{"00"*16}\n'
                text += "".join(f"#EXTINF:2,\npart{i}.ts\n" for i in range(4)) + "#EXT-X-ENDLIST\n"
                self.send(text.encode(), "application/vnd.apple.mpegurl"); return
            if path == "/aes-key":
                good = self.headers.get("X-Key") == "fixture-key" and query.get("token") == ["a+b"]
                requests.append({"kind": "aes-key", "valid_headers": good})
                self.send(bytes.fromhex(KEY), "application/octet-stream", 200 if good else 403); return
            if path.startswith("/aes/"):
                requests.append({"kind": "aes-segment", "valid_headers": self.headers.get("X-Key") is None})
            if path in {"/plain.mp4", "/video.mp4"}:
                self.path = "/" + args.video.name
                original = self.directory; self.directory = str(args.video.resolve().parent)
                try: super().do_GET()
                finally: self.directory = original
                return
            super().do_GET()
        def log_message(self, *unused): pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(resources)))
    for service in [server, cdn]: Thread(target=service.serve_forever, daemon=True).start()
    d.stop()
    backup = {path: d.read(path) for path in FILES + ["files/personal-watch.sqlite"]}
    index_path = "files/personal-playlists/index.json"
    index = d.read(index_path)
    saved = {}
    if index:
        for source in json.loads(index)["ids"]:
            if not re.fullmatch(r"user_[a-f0-9]{32}", source): raise ValueError("Invalid source ID")
            for suffix in [".sqlite", ".json", ".json.bak"]:
                path = "files/personal-playlists/" + source + suffix
                saved[path] = d.read(path)
    font = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()
    rotation = d.run("shell", "settings", "get", "system", "user_rotation").decode().strip()
    auto = d.run("shell", "settings", "get", "system", "accelerometer_rotation").decode().strip()

    def native(operation):
        command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-e", "operation", operation,
                   "-e", "fixtureRoot", f"http://10.0.2.2:{server.server_port}", "-e", "cdnOrigin", f"http://10.0.2.2:{cdn.server_port}",
                   PACKAGE + ".test/it.lumetv.app.home.HomeInspection"]
        result = subprocess.run(command, capture_output=True, timeout=600, check=True).stdout.decode(errors="replace")
        (d.output / (operation + ".txt")).write_text(result)
        match = re.search(r"(?m)^INSTRUMENTATION_RESULT: snapshot=(.+)$", result)
        if not match: raise AssertionError("Native inspection failed: " + result[-2000:])
        report = json.loads(match[1]); assert report["passed"]
        d.checks += report["checks"]
        print("PASS", args.serial, operation, flush=True)
        return report

    def click(label, description=False, contains=False):
        key = "content-desc" if description else "text"
        for _ in range(8):
            nodes = playlist_actions(d.nodes(),label,description,contains)
            if nodes:
                left, top, right, bottom = map(int, re.findall(r"\d+", nodes[-1].get("bounds")))
                if right > left and bottom > top:
                    d.run("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2)); return
            if args.mode == "phone": d.run("shell", "input", "swipe", "540", "1500", "540", "600", "300")
            else: d.run("shell", "input", "swipe", "1100", "750", "1100", "330", "300")
        raise AssertionError("Missing UI control: " + label)

    def manager():
        d.launch("catalog")
        d.wait("catalog visible", lambda: "Canali" in d.texts())
        click("Opzioni", description=args.mode == "phone")
        d.wait("options visible", lambda: any(n.get("content-desc", "").startswith("Le mie liste:") for n in d.nodes()))
        click("Le mie liste:", description=True, contains=True)
        d.wait("personal manager visible", lambda: "Aggiungi URL" in d.texts())

    try:
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists")
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        if args.mode == "phone":
            d.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            d.run("shell", "settings", "put", "system", "user_rotation", "0")
        fixture(d, server.server_port)
        compatibility = None
        if not args.capacity_only:
            compatibility = native("playlist-compatibility")
            assert all(r["valid_headers"] for r in requests if r["kind"] in {"parent", "included-document"})
            d.run("logcat","-c"); d.launch("channel/" + compatibility["aesChannel"])
            d.wait("AES playback decodes with scoped key headers and encoded parameters", lambda: d.playing("AES locale") and b"first_frame" in d.run("logcat","-d","-s","LumePlayer:I"))
            d.capture("01-aes-playback.png")
            assert any(r["kind"] == "aes-key" for r in requests)
            assert all(r["valid_headers"] for r in requests if r["kind"].startswith("aes-"))
            manager(); click("Compatibilità avanzata", contains=True); click("Lettura e contenuti")
            d.wait("advanced format and encoding settings visible", lambda: "Codifica del testo" in d.texts())
            d.capture("02-advanced-settings.png")
            click("Annulla");click("Accesso ai server", contains=True)
            click("Download della lista", contains=True)
            d.wait("scoped server and HTTP header editor visible", lambda: any("Altri server autorizzati" in t for t in d.texts()))
            d.capture("03-scoped-access.png"); d.key(20)
            assert any(n.get("focused") == "true" for n in d.nodes())
            click("Annulla"); click("Annulla")
        d.stop()
        capacity = native("playlist-capacity")
        d.run("logcat","-c"); d.launch("channel/" + capacity["lastChannel"])
        d.wait("last entry of 100000 channel catalog plays", lambda: d.playing("Carico extra voce 1000") and b"first_frame" in d.run("logcat","-d","-s","LumePlayer:I"), timeout=45)
        d.capture("04-capacity-playback.png")
        d.stop(); d.run("logcat","-c"); d.launch("channel/" + capacity["firstChannel"])
        d.wait("100000 channel catalog restores after process restart", lambda: d.playing("Carico 0 voce 1") and b"first_frame" in d.run("logcat","-d","-s","LumePlayer:I"), timeout=45)
        d.run("shell", "settings", "put", "system", "font_scale", "1.3")
        d.stop(); manager()
        d.capture("05-capacity-font-130.png")
        d.checks.append("100000 channel native UI at 130 percent text")
        (d.output / "report.json").write_text(json.dumps({"passed": True, "mode": args.mode, "compatibility": compatibility,
            "capacity": capacity, "checks": d.checks, "requests": requests}, indent=2) + "\n")
    finally:
        d.stop()
        d.run("shell", "settings", "put", "system", "font_scale", font)
        if args.mode == "phone":
            d.run("shell", "settings", "put", "system", "user_rotation", rotation)
            d.run("shell", "settings", "put", "system", "accelerometer_rotation", auto)
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists")
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files/personal-playlists")
        for path, data in saved.items(): d.write(path, data)
        d.write(index_path, index)
        for path, data in backup.items(): d.write(path, data)
        for service in [server, cdn]: service.shutdown(); service.server_close()


if __name__ == "__main__":
    main()
