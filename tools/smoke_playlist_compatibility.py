#!/usr/bin/env python3
"""HTTP, large M3U, scoped headers and synthetic ClearKey playback on disposable emulators only."""
import argparse
import base64
import functools
import json
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import sqlite3
import subprocess
from threading import Thread
import time
import uuid
import xml.etree.ElementTree as ET

from smoke_playback import Device, FILES, PACKAGE, ROOT, fixture


def playlist_actions(nodes, label, description=False, contains=False):
    """Resolve compound settings rows by accessible label before their visual copy."""
    needle=label.casefold()
    def matches(value):
        value=value.casefold()
        return needle in value if contains else value==needle or any(value.startswith(needle+separator) for separator in ["\n", ":", ","])
    matches_nodes=[node for node in nodes if node.get("enabled")!="false" and
                   any(matches(node.get(key,"")) for key in (["content-desc"] if description else ["text","content-desc"]))]
    return [node for node in matches_nodes if node.get("clickable")=="true"] or matches_nodes


class PlaylistDevice(Device):
    def nodes(self):
        path = "/sdcard/lume-playlist-compatibility.xml"
        result = self.run("shell", "uiautomator", "dump", path)
        # A timeout does not remove the previous XML. Never treat that stale file as current UI.
        if b"dumped to:" not in result:
            return []
        return list(ET.fromstring(self.run("exec-out", "cat", path)).iter("node"))

# Public synthetic fixture values, used only to encrypt the supplied local test video.
KID = "00112233445566778899aabbccddeeff"
KEY = "ffeeddccbbaa99887766554433221100"


def media(directory, video):
    directory.mkdir(parents=True, exist_ok=True)
    for child in ["dash", "hls"]:
        (directory / child).mkdir(exist_ok=True)
    common = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-stream_loop", "-1", "-i", str(video),
              "-an", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-g", "50", "-keyint_min", "50", "-sc_threshold", "0"]
    if not (directory / "dash/manifest.mpd").exists():
        subprocess.run(common + ["-t", "120", "-f", "dash", "-seg_duration", "2", "-format_options",
                                 f"encryption_scheme=cenc-aes-ctr:encryption_key={KEY}:encryption_kid={KID}", str(directory / "dash/manifest.mpd")], check=True)
        ET.register_namespace("", "urn:mpeg:dash:schema:mpd:2011")
        ET.register_namespace("cenc", "urn:mpeg:cenc:2013")
        tree = ET.parse(directory / "dash/manifest.mpd")
        for adaptation in tree.findall(".//{urn:mpeg:dash:schema:mpd:2011}AdaptationSet"):
            ET.SubElement(adaptation, "{urn:mpeg:dash:schema:mpd:2011}ContentProtection", {
                "schemeIdUri": "urn:mpeg:dash:mp4protection:2011", "value": "cenc",
                "{urn:mpeg:cenc:2013}default_KID": str(uuid.UUID(KID))})
        tree.write(directory / "dash/manifest.mpd", encoding="utf-8", xml_declaration=True)
    if not (directory / "hls/duration-600").exists():
        subprocess.run(common + ["-t", "600", "-f", "hls", "-hls_time", "2", "-hls_list_size", "0", "-hls_segment_filename",
                                 str(directory / "hls/part%03d.ts"), str(directory / "hls/media.m3u8")], check=True)
        (directory / "hls/master.m3u8").write_text("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360\nmedia.m3u8\n")
        (directory / "hls/duration-600").touch()
    def b64(value): return base64.urlsafe_b64encode(bytes.fromhex(value)).decode().rstrip("=")
    return json.dumps({"keys": [{"kty": "oct", "kid": b64(KID), "k": b64(KEY)}], "type": "temporary"}).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["phone", "tv"], required=True)
    parser.add_argument("--video", type=Path, required=True)
    phase = parser.add_mutually_exclusive_group()
    phase.add_argument("--features-only", action="store_true")
    phase.add_argument("--large-only", action="store_true", help="Import the feature fixture and run the large-source checks without repeating consent/playback checks")
    parser.add_argument("--large-entries", type=int, default=50_000)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/playlist-compatibility")
    args = parser.parse_args()
    d = PlaylistDevice(args.adb, args.serial, args.output)
    if d.run("shell", "getprop", "ro.hardware").strip() not in {b"ranchu", b"goldfish"}:
        raise ValueError("Disposable Android emulator hardware required")
    resources = args.output / "media"
    license_body = media(resources, args.video.resolve())
    requests = []
    state = {"revision": 0, "license_ok": True}

    class LicenseHandler(SimpleHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get("Content-Length", "0")))
            good = self.headers.get("X-License") == "license-fixture" and self.headers.get("Authorization") is None and len(self.headers.get_all("User-Agent", [])) == 1
            requests.append({"kind": "license", "valid_headers": good})
            self.send_response(200 if good and state["license_ok"] else 403)
            self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(license_body)))
            self.end_headers(); self.wfile.write(license_body)
        def log_message(self, *unused): pass

    license_server = ThreadingHTTPServer(("127.0.0.1", 0), LicenseHandler)

    class Handler(SimpleHTTPRequestHandler):
        def send(self, body, kind="audio/x-mpegurl"):
            self.send_response(200); self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/features.m3u":
                root = f"http://10.0.2.2:{self.server.server_port}"
                text = ["#EXTM3U", '#EXTINF:-1 tvg-id="headers" group-title="Prove",Header HLS',
                        '#EXTHTTP:{"User-Agent":"LumeFixture/1","Authorization":"stream-fixture"}',
                        '#KODIPROP:inputstream.adaptive.manifest_headers=X-Manifest=manifest',
                        '#KODIPROP:inputstream.adaptive.stream_headers=X-Stream=segment', root + "/hls/master.m3u8",
                        '#EXTINF:-1 tvg-id="local-key" group-title="Prove",ClearKey Locale',
                        '#KODIPROP:inputstream.adaptive.manifest_type=mpd',
                        '#KODIPROP:inputstream.adaptive.license_type=clearkey',
                        f'#KODIPROP:inputstream.adaptive.license_key={KID}:{KEY}', root + "/dash/manifest.mpd",
                        '#EXTINF:-1 tvg-id="remote-key" group-title="Prove",ClearKey Server',
                        '#EXTHTTP:{"User-Agent":"LumeFixture/1","Authorization":"stream-fixture"}',
                        '#KODIPROP:inputstream.adaptive.manifest_type=mpd',
                        '#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey',
                        f'#KODIPROP:inputstream.adaptive.license_key=http://10.0.2.2:{license_server.server_port}/keys|X-License=license-fixture|R{{SSM}}|R', root + "/dash/manifest.mpd",
                        '#EXTINF:-1 tvg-id="plain" group-title="Prove",HTTP Diretto', root + '/plain.mp4|User-Agent=Inline+Fixture&X-Token=local-fixture',
                        '#EXTINF:-1,DRM non supportato', '#KODIPROP:inputstream.adaptive.license_type=playready',
                        '#KODIPROP:inputstream.adaptive.license_key=https://example.invalid/license', root + '/dash/manifest.mpd']
                requests.append({"kind": "playlist"}); self.send(("\n".join(text) + "\n").encode()); return
            if path == "/large.m3u":
                requests.append({"kind": "large-playlist", "revision": state["revision"]})
                self.send_response(200); self.send_header("Content-Type", "audio/x-mpegurl"); self.end_headers()
                try:
                    self.wfile.write(b"#EXTM3U\n")
                    for i in range(args.large_entries):
                        name = f"Grande {i+1}" + (" aggiornato" if state["revision"] else "")
                        row = f'#EXTINF:-1 tvg-id="large-{i}" group-title="Gruppo {i%10}",{name}\nhttp://10.0.2.2:{self.server.server_port}/plain.mp4?i={i}&token={"x"*650}&v={state["revision"]}\n'
                        self.wfile.write(row.encode())
                except (BrokenPipeError, ConnectionResetError): pass
                return
            if path == "/slow.m3u":
                self.send_response(200); self.end_headers()
                try:
                    self.wfile.write(b"#EXTM3U\n")
                    for _ in range(200): self.wfile.write(("#" + "x"*8190 + "\n").encode()); self.wfile.flush(); time.sleep(.1)
                except (BrokenPipeError, ConnectionResetError): pass
                return
            if path.startswith("/hls/"):
                manifest = path.endswith(".m3u8")
                good = self.headers.get("User-Agent") == "LumeFixture/1" and len(self.headers.get_all("User-Agent", [])) == 1
                good = good and self.headers.get("Authorization") == "stream-fixture"
                good = good and (self.headers.get("X-Manifest") == "manifest" and self.headers.get("X-Stream") is None if manifest
                                 else self.headers.get("X-Stream") == "segment" and self.headers.get("X-Manifest") is None)
                requests.append({"kind": "hls-manifest" if manifest else "hls-segment", "valid_headers": good})
                if not good: self.send_error(403); return
            if path == "/plain.mp4":
                self.path = "/video.mp4"
            if path == "/video.mp4" or self.path == "/video.mp4":
                self.send(args.video.read_bytes(), "video/mp4"); return
            super().do_GET()
        def log_message(self, *unused): pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(resources)))
    server.daemon_threads = True; license_server.daemon_threads = True
    for service in [server, license_server]: Thread(target=service.serve_forever, daemon=True).start()
    d.stop()
    backup = {p: d.read(p) for p in FILES}
    index_path = "files/personal-playlists/index.json"
    original_index = d.read(index_path)
    original_font = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()
    original_rotation = d.run("shell", "settings", "get", "system", "user_rotation").decode().strip()
    original_auto_rotation = d.run("shell", "settings", "get", "system", "accelerometer_rotation").decode().strip()
    personal_backup = {}
    if original_index:
        for source in json.loads(original_index)["ids"]:
            if not re.fullmatch(r"user_[a-f0-9]{32}", source): raise ValueError("Invalid source ID")
            for suffix in [".json", ".sqlite"]:
                path = "files/personal-playlists/" + source + suffix
                personal_backup[path] = d.read(path)

    def click(label, description=False, contains=False):
        key = "content-desc" if description else "text"
        items = []
        deadline = time.monotonic() + 25
        while not items and time.monotonic() < deadline:
            items = playlist_actions(d.nodes(),label,description,contains)
            if not items: time.sleep(.2)
        if not items: raise AssertionError("Missing UI control: " + label)
        left, top, right, bottom = map(int, re.findall(r"\d+", items[-1].get("bounds")))
        d.run("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2))

    def manager():
        d.wait("catalog or manager visible", lambda: "Aggiungi URL" in d.texts() or any(n.get("text")=="Opzioni" or n.get("content-desc")=="Opzioni" for n in d.nodes()))
        if "Aggiungi URL" in d.texts(): return
        click("Opzioni", description=args.mode == "phone")
        d.wait("options ready", lambda: any(n.get("content-desc", "").startswith("Le mie liste:") for n in d.nodes()))
        click("Le mie liste:", description=True, contains=True)
        d.wait("personal manager ready", lambda: "Aggiungi URL" in d.texts())

    def set_http(allowed):
        click("Sorgente")
        node = next(n for n in d.nodes() if n.get("text")=="Consenti HTTP per questa lista")
        if (node.get("checked")=="true") != allowed: click("Consenti HTTP per questa lista")
        click("Salva modifiche")
        d.wait("source HTTP policy saved", lambda:any(t.startswith("Stato e ultima importazione") for t in d.texts()))
        click("Indietro")

    def editor(name, path, consent=True):
        click("Aggiungi URL")
        click("Nome della lista", description=True); d.run("shell", "input", "text", name.replace(" ", "%s"))
        if args.mode == "phone":
            # Native focus navigation remains stable while the landscape IME resizes the page.
            d.key(61)
            d.wait("address field receives input focus", lambda: any(n.get("content-desc") == "Indirizzo HTTP o HTTPS della lista" and n.get("focused") == "true" for n in d.nodes()))
        else:
            click("Indirizzo HTTP o HTTPS della lista", description=True)
        d.run("shell", "input", "text", f"http://10.0.2.2:{server.server_port}/{path}")
        d.key(4)
        if consent: click("Consenti HTTP per questa lista")

    def source(name):
        ids = json.loads(d.read(index_path) or b'{"ids":[]}')["ids"]
        for identity in ids:
            raw = d.read("files/personal-playlists/" + identity + ".sqlite")
            if not raw: continue
            db = args.output / args.serial / "inspection.sqlite"; db.write_bytes(raw)
            with sqlite3.connect(db) as connection:
                value = json.loads(connection.execute("SELECT value FROM metadata WHERE key='source'").fetchone()[0])
                if value["name"] != name: continue
                value["entries"] = {tvg: key for key, payload in connection.execute("SELECT id,payload FROM records WHERE active=1 ORDER BY position LIMIT 5")
                                    for tvg in [json.loads(payload)["tvgId"]]}
                value["first_id"] = connection.execute("SELECT id FROM records WHERE active=1 ORDER BY position LIMIT 1").fetchone()[0]
                value["last_id"] = connection.execute("SELECT id FROM records WHERE active=1 ORDER BY position DESC LIMIT 1").fetchone()[0]
                return value
        return None

    def play(key, name, capture):
        d.run("logcat", "-c"); d.launch("channel/" + key)
        d.wait(name + " has a decoded first frame", lambda: d.playing(name) and b"first_frame" in d.run("logcat", "-d", "-s", "LumePlayer:I"), timeout=35)
        assert "Le mie liste" not in d.texts(), "Personal settings covered the channel opened by a deep link"
        d.capture(capture)
        assert b"error=" not in d.run("logcat", "-d", "-s", "LumePlayer:I")

    try:
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        if args.mode == "phone":
            d.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            d.run("shell", "settings", "put", "system", "user_rotation", "0")
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists")
        fixture(d, server.server_port); d.launch("catalog"); d.wait("catalog ready", lambda: "Canali" in d.texts())
        manager(); editor("Compatibilita", "features.m3u", False)
        d.capture("01-http-opt-out.png"); click("Verifica lista")
        d.wait("HTTP source is blocked before any network request", lambda: any("abilita Consenti HTTP" in t for t in d.texts()))
        assert not any(r["kind"] == "playlist" for r in requests)
        d.capture("02-http-consent-required.png"); click("Consenti HTTP per questa lista"); click("Verifica lista")
        d.wait("preview recognizes supported DRM and excluded types", lambda: any("4 voci importabili" in t and ("1 esclusa" in t or "1 escluse" in t) for t in d.texts()))
        d.capture("03-preview.png"); click("Importa lista");d.wait("source details ready",lambda:any(t.startswith("Stato e ultima importazione") for t in d.texts()));click("Indietro")
        d.wait("features source saved", lambda: any("Compatibilita" in t for t in d.texts()) and "Aggiungi URL" in d.texts())
        saved = source("Compatibilita"); assert saved["allowHttp"] and saved["count"] == 4
        if not args.large_only:
            for tvg, title in [("headers", "Header HLS"), ("local-key", "ClearKey Locale"), ("remote-key", "ClearKey Server"), ("plain", "HTTP Diretto")]:
                play(saved["entries"][tvg], title, "04-" + tvg + ".png")
            assert any(r["kind"] == "license" for r in requests)
            assert all(r.get("valid_headers", True) for r in requests)
            d.checks.append("Manifest/segment headers and a separate license origin validated on the server")
            play(saved["entries"]["headers"], "Header HLS", "04-before-revoke.png")
            d.key(4); manager(); click("Compatibilita", contains=True); set_http(False)
            d.wait("revoking HTTP stops its current playback", lambda: not d.playing())
            assert source("Compatibilita")["allowHttp"] is False
            click("Compatibilita", contains=True); set_http(True)
            play(saved["entries"]["headers"], "Header HLS", "05-hls.png")
            d.key(4); manager()
            editor("Annullata", "slow.m3u"); click("Verifica lista")
            d.wait("slow import shows progress", lambda: any("Verifico…" in t for t in d.texts()))
            click("Interrompi verifica");click("Annulla");click("Scarta modifiche")
            d.wait("cancellation removes its draft", lambda: b".draft-" not in d.run("shell", "run-as", PACKAGE, "ls", "-a", "files/personal-playlists"))
            assert len(json.loads(d.read(index_path))["ids"]) == 1
        else:
            play(saved["entries"]["headers"], "Header HLS", "05-hls.png")
            d.key(4); manager()
        if not args.features_only:
            editor("Grande", "large.m3u"); click("Verifica lista")
            # UIAutomator waits for accessibility idle. The live byte/entry counter intentionally
            # changes while importing, so observe the active request/draft before waiting for preview.
            d.wait("large import starts in a private draft", lambda: any(r["kind"] == "large-playlist" for r in requests) and
                   b".draft-" in d.run("shell", "run-as", PACKAGE, "ls", "-a", "files/personal-playlists"))
            d.capture("06-large-progress.png")
            d.wait("large preview completed", lambda: any(f"{args.large_entries} voci importabili" in t for t in d.texts()), timeout=150)
            assert d.playing("Header HLS")
            d.capture("07-large-preview.png"); click("Importa lista");d.wait("source details ready",lambda:any(t.startswith("Stato e ultima importazione") for t in d.texts()));click("Indietro")
            d.wait("large catalog committed", lambda: "Aggiungi URL" in d.texts() and any("Grande" in t for t in d.texts()), timeout=90)
            large = source("Grande"); assert large["count"] == args.large_entries
            state["revision"] = 1
            click("Grande", contains=True); click("Aggiorna lista");click("Indietro")
            d.wait("large refresh starts", lambda: any("Aggiornamento in corso" in t for t in d.texts()))
            d.wait("large refresh completes", lambda: "Aggiungi URL" in d.texts() and not any("Aggiornamento in corso" in t for t in d.texts()), timeout=150)
            refreshed = source("Grande"); assert large["first_id"] == refreshed["first_id"] and large["last_id"] == refreshed["last_id"]
            assert refreshed["updated"] > large["updated"]
            d.checks.append("Large source refresh preserves channel identities")
            d.capture("08-large-saved.png")
            (args.output / args.serial / "memory.txt").write_bytes(d.run("shell", "dumpsys", "meminfo", PACKAGE))
            d.run("shell", "settings", "put", "system", "font_scale", "1.3")
            d.wait("large text keeps source controls available", lambda: "Aggiungi URL" in d.texts() and
                   d.run("shell", "settings", "get", "system", "font_scale").strip() == b"1.3")
            d.capture("09-large-text.png")
            d.launch("catalog")
            d.wait("catalog is visible after text recreation", lambda: "Canali" in d.texts())
        d.stop(); d.launch("catalog")
        d.wait("catalog restores after process death", lambda: "Canali" in d.texts(), timeout=45)
        restored = source("Compatibilita"); assert restored["entries"] == saved["entries"]
        play(saved["entries"]["local-key"], "ClearKey Locale", "10-restored-clearkey.png")
        runtime = d.run("logcat", "-d", "-s", "AndroidRuntime:E")
        assert b"FATAL EXCEPTION" not in runtime
        (args.output / args.serial / "requests.json").write_text(json.dumps(requests, indent=2) + "\n")
        (args.output / args.serial / "report.json").write_text(json.dumps({"passed": True, "checks": d.checks, "count": len(d.checks), "large_entries": 0 if args.features_only else args.large_entries, "large_only": args.large_only}, indent=2) + "\n")
    except Exception:
        d.capture("failure.png")
        (d.output / "failure-ui.xml").write_bytes(d.run("exec-out", "cat", "/sdcard/lume-playlist-compatibility.xml", check=False))
        raise
    finally:
        d.stop()
        if original_font and original_font != "null": d.run("shell", "settings", "put", "system", "font_scale", original_font)
        if args.mode == "phone":
            for key, value in [("user_rotation", original_rotation), ("accelerometer_rotation", original_auto_rotation)]:
                if value != "null": d.run("shell", "settings", "put", "system", key, value)
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists")
        if original_index:
            d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files/personal-playlists")
            d.write(index_path, original_index)
            for path, value in personal_backup.items(): d.write(path, value)
        for path, value in backup.items(): d.write(path, value)
        server.shutdown(); license_server.shutdown()


if __name__ == "__main__": main()
