#!/usr/bin/env python3
"""Radio/background/PiP/TV playback checks using disposable emulators and local media.

Restores the app's catalogs and preferences. Never run on a physical device.
"""
import argparse
import functools
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
from threading import Thread
import time
import xml.etree.ElementTree as ET

PACKAGE = "it.lumetv.app"
ROOT = Path(__file__).resolve().parents[1]
FILES = ["files/channels.m3u", "files/radios.json", "files/guide.json",
         "shared_prefs/lume.xml", "shared_prefs/guide.xml", "shared_prefs/interface.xml",
         "shared_prefs/app_updates.xml", "shared_prefs/discovery.xml"]


class Device:
    def __init__(self, adb, serial, output):
        if not serial.startswith("emulator-"):
            raise ValueError("Only disposable emulators are supported")
        self.adb, self.serial, self.output = adb, serial, output / serial
        self.output.mkdir(parents=True, exist_ok=True)
        self.checks = []

    def run(self, *args, body=None, check=True):
        return subprocess.run([self.adb, "-s", self.serial, *args], input=body, capture_output=True,
                              check=check, timeout=45).stdout

    def read(self, path):
        exists = subprocess.run([self.adb, "-s", self.serial, "shell", "run-as", PACKAGE, "test", "-f", path], capture_output=True)
        if exists.returncode != 0:
            return None
        result = subprocess.run([self.adb, "-s", self.serial, "exec-out", "run-as", PACKAGE, "cat", path], capture_output=True)
        return result.stdout if result.returncode == 0 else None

    def write(self, path, data):
        if data is None:
            self.run("shell", "run-as", PACKAGE, "rm", "-f", path)
        else:
            self.run("shell", "run-as " + PACKAGE + " sh -c 'cat > " + path + "'", body=data)

    def stop(self):
        self.run("shell", "am", "force-stop", PACKAGE)

    def launch(self, route=None):
        if route is None:
            self.run("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-n", PACKAGE + "/.MainActivity")
        else:
            self.run("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "lumetv://" + route,
                     "-n", PACKAGE + "/.MainActivity")

    def nodes(self):
        self.run("shell", "uiautomator", "dump", "/sdcard/lume-playback.xml")
        return list(ET.fromstring(self.run("exec-out", "cat", "/sdcard/lume-playback.xml")).iter("node"))

    def texts(self):
        return [n.get("text", "") for n in self.nodes()]

    def click(self, label, description=False):
        attr = "content-desc" if description else "text"
        items = [n for n in self.nodes() if n.get(attr) == label]
        if not items:
            self.capture("missing-control.png")
            raise AssertionError("Missing control: " + label)
        node = items[-1]
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        self.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def key(self, code):
        self.run("shell", "input", "keyevent", str(code))

    def session(self):
        dump = self.run("shell", "dumpsys", "media_session").decode(errors="replace")
        sections = re.split(r"(?m)^    [^ \n].*?\n", dump)
        return next((s for s in sections if "package=" + PACKAGE in s), dump if PACKAGE in dump else "")

    def playing(self, name=None, paused=False):
        dump = self.session()
        return bool(re.search(r"state=(?:[A-Z_]+\()?" + ("2" if paused else "3") + r"(?:\)|[, }])", dump)) and (name is None or name in dump)

    def wait(self, name, condition, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if condition():
                self.checks.append(name)
                print("PASS", self.serial, name, flush=True)
                return
            time.sleep(.4)
        self.capture("failure.png")
        (self.output / "failure-media-session.txt").write_text(self.session())
        raise AssertionError(name)

    def capture(self, name):
        (self.output / name).write_bytes(self.run("exec-out", "screencap", "-p"))

    def show_controls(self):
        if not any(n.get("content-desc") == "Picture in Picture" for n in self.nodes()):
            self.click("Diretta · Tocca per mostrare o nascondere i comandi", description=True)

    def pinned(self):
        dump = self.run("shell", "dumpsys", "activity", "activities").decode(errors="replace")
        return any("pinned" in line.lower() and (PACKAGE in line or "Task{" in line) for line in dump.splitlines())


def fixture(device, port):
    now = int(device.run("shell", "date", "+%s")) * 1000
    playlist = ["#EXTM3U"]
    guide = {}
    for number in range(1, 25):
        name = "TV Test " + str(number)
        playlist += [f'#EXTINF:-1 tvg-id="test{number}" tvg-chno="{number}" group-title="Playback test",{name}',
                     f"http://10.0.2.2:{port}/video.mp4"]
        guide["test" + str(number)] = [{"title": "Programma di prova", "start": now - 600_000, "end": now + 3_600_000,
                                       "description": "Palinsesto sintetico locale per verificare la navigazione."}]
    for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
        playlist += [f'#EXTINF:-1 tvg-id="validation{number}" tvg-chno="{number}",Validazione {number}',
                     f'plugin://plugin.video.wltvhelper/play/{broadcaster}/unused{number}']
    radio = {"countries": [{"places": [{"name": "Roma", "channels": [
        {"code": "one", "title": "Radio Test Uno", "enabled": True, "url": f"http://10.0.2.2:{port}/radio1.mp3"}]},
        {"name": "Milano", "channels": [{"code": "two", "title": "Radio Test Due", "enabled": True,
                                         "url": f"http://10.0.2.2:{port}/radio2.mp3"}]}]}]}
    device.write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
    device.write("files/radios.json", json.dumps(radio).encode())
    device.write("files/guide.json", json.dumps(guide).encode())
    device.write("shared_prefs/guide.xml", f'<map><long name="updated" value="{now}"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>'.encode())
    device.write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/><long name="radios_updated" value="{now}"/></map>'.encode())
    device.write("shared_prefs/app_updates.xml", b'<map><boolean name="automatic" value="false"/></map>')
    device.write("shared_prefs/interface.xml", b'<map/>')
    device.write("shared_prefs/discovery.xml", b'<map/>')
    return hashlib.sha256(b"radio|one").hexdigest()[:24], hashlib.sha256(b"test1|TV Test 1|Playback test").hexdigest()[:24]


def phone_checks(d, radio_id, tv_id, requests):
    d.launch("catalog")
    d.wait("five touch destinations", lambda: all(any(n.get("content-desc") == name for n in d.nodes())
           for name in ["Canali", "Radio", "Preferiti", "Guida", "Scopri"]))
    d.click("Radio", description=True)
    d.wait("radio catalog is separate", lambda: "2 radio" in d.texts())
    d.click("Cerca una radio o una città", description=True)
    d.run("shell", "input", "text", "Roma")
    d.wait("radio search includes city", lambda: "1 radio" in d.texts())
    for _ in range(4): d.key(67)
    d.click("Radio", description=True)
    d.click("Aggiungi Radio Test Uno ai preferiti", description=True)
    d.click("Preferite")
    d.wait("radio favorites", lambda: "1 radio" in d.texts())
    d.capture("01-radio-catalog.png")
    d.launch("channel/" + radio_id)
    d.wait("radio playback", lambda: d.playing("Radio Test Uno"))
    d.wait("ICY song metadata", lambda: "Artista Test - Brano Test" in d.texts())
    # Audio playback state may not change after a favorite edit: feedback must still update.
    d.click("Rimuovi Radio Test Uno dai preferiti", description=True)
    d.wait("radio player immediately reflects favorite removal", lambda: any(n.get("content-desc") == "Aggiungi Radio Test Uno ai preferiti" for n in d.nodes()))
    d.click("Aggiungi Radio Test Uno ai preferiti", description=True)
    d.wait("radio player immediately reflects favorite addition", lambda: any(n.get("content-desc") == "Rimuovi Radio Test Uno dai preferiti" for n in d.nodes()))
    d.capture("02-radio-player.png")
    d.click("Torna al catalogo")
    d.wait("radio continues in the mini player", lambda: d.playing("Radio Test Uno") and any(n.get("content-desc") == "Interrompi la riproduzione" for n in d.nodes()))
    d.capture("03-radio-mini-player.png")
    d.key(3)
    d.key(223)
    print("WAIT", d.serial, "40 seconds of screen-off streaming", flush=True)
    time.sleep(40)
    d.wait("screen-off playback exceeds the former 30 second timeout", lambda: d.playing("Radio Test Uno") and requests.get("/radio1.mp3") == 1)
    service = d.run("shell", "dumpsys", "activity", "services", PACKAGE).decode(errors="replace")
    d.wait("foreground media playback service", lambda: "isForeground=true" in service)
    d.wait("notification advertises explicit stop", lambda: "Interrompi" in d.session())
    d.run("shell", "cmd", "media_session", "dispatch", "pause")
    d.wait("pause from system media controls", lambda: d.playing(paused=True))
    d.run("shell", "cmd", "media_session", "dispatch", "play")
    d.wait("resume from system media controls", lambda: d.playing())
    d.run("shell", "cmd", "media_session", "dispatch", "next")
    d.wait("background next selects another radio", lambda: d.playing("Radio Test Due"))
    d.key(224); d.run("shell", "wm", "dismiss-keyguard")
    d.launch("channel/" + tv_id)
    d.wait("TV playback after radio", lambda: d.playing("TV Test 1"))
    d.key(4)
    d.wait("TV preview inside the catalog", lambda: d.playing("TV Test 1") and any(n.get("content-desc") == "Apri la diretta" for n in d.nodes()))
    d.capture("04-tv-mini-player.png")
    d.click("Apri la diretta", description=True)
    d.show_controls()
    d.click("Picture in Picture", description=True)
    d.wait("TV enters system Picture in Picture", d.pinned)
    time.sleep(3)  # Task mode changes before the native transition and icon overlay finish.
    d.capture("05-pip.png")
    d.run("shell", "cmd", "media_session", "dispatch", "pause")
    d.wait("PiP pause", lambda: d.playing(paused=True))
    d.run("shell", "cmd", "media_session", "dispatch", "play")
    d.wait("PiP resume", lambda: d.playing())
    second_tv = hashlib.sha256(b"test2|TV Test 2|Playback test").hexdigest()[:24]
    d.launch("channel/" + second_tv)
    d.wait("channel link exits PiP and switches playback", lambda: not d.pinned() and d.playing("TV Test 2"))
    d.key(3)
    d.wait("Home automatically enters PiP during TV playback", d.pinned)
    time.sleep(1.2)
    d.launch("channel/" + tv_id)
    d.wait("channel link exits automatic PiP", lambda: not d.pinned() and d.playing("TV Test 1"))
    # Keep controls visible while locating the audio action through accessibility.
    d.run("shell", "cmd", "media_session", "dispatch", "pause")
    d.wait("paused TV survives the PiP return", lambda: d.playing(paused=True))
    d.show_controls(); d.click("Solo audio")
    d.wait("explicit TV audio-only mode", lambda: "Solo audio · TV in diretta" in d.texts())
    d.capture("07-tv-audio-only.png")
    d.run("shell", "cmd", "media_session", "dispatch", "play")
    d.key(3)
    d.wait("TV audio continues outside the app without a PiP window", lambda: d.playing("TV Test 1") and not d.pinned())
    d.launch()
    d.click("Torna al video")
    d.wait("return from audio-only to video", lambda: d.playing("TV Test 1"))
    d.key(4)
    d.click("Opzioni", description=True); d.click("Qualità e consumo dati"); d.click("Risparmio dati")
    d.wait("data saving preference persists", lambda: b'data_saver" value="true"' in d.read("shared_prefs/lume.xml"))
    d.key(4)  # Return from the options page to the catalog.
    d.click("Interrompi la riproduzione", description=True)
    d.wait("explicit stop ends playback", lambda: not d.playing())
    d.click("Canali", description=True); d.click("Recenti")
    d.wait("TV recents", lambda: "2 canali" in d.texts())
    d.click("Radio", description=True); d.click("Recenti")
    d.wait("radio recents are separate", lambda: "2 radio" in d.texts())
    d.run("shell", "settings", "put", "system", "font_scale", "1.3")
    # Recreate native TextViews with the new resources before judging the rendered text.
    d.stop(); d.launch("catalog")
    d.click("Radio", description=True); d.click("Recenti")
    d.wait("radio navigation at 130 percent text", lambda: any(n.get("content-desc") == "Radio" for n in d.nodes()))
    d.capture("06-radio-font-scale.png")


def tv_checks(d, radio_id, tv_id):
    d.launch("catalog")
    for _ in range(6):
        focused = next((n for n in d.nodes() if n.get("focused") == "true"), None)
        if focused is not None and focused.get("text") == "Canali": break
        d.key(19)
    d.key(22); d.key(23)
    d.wait("radio tab reached with D-pad", lambda: "Radio, in diretta." in d.texts())
    d.capture("01-radio-catalog.png")
    d.launch("channel/" + radio_id)
    d.wait("TV radio playback", lambda: d.playing("Radio Test Uno"))
    d.key(23)
    d.wait("radio pause with focused remote button", lambda: d.playing(paused=True))
    d.key(23)
    d.wait("radio resume with focused remote button", lambda: d.playing())
    d.capture("02-radio-player.png")
    d.key(4)
    d.wait("radio continues while browsing inside TV app", lambda: d.playing("Radio Test Uno") and "Apri la diretta" in d.texts())
    d.launch("channel/" + tv_id)
    d.wait("TV video playback", lambda: d.playing("TV Test 1"))
    d.key(4)
    d.click("Guida TV")
    d.wait("TV guide keeps video playing in the internal preview", lambda: d.playing("TV Test 1") and "Apri la diretta" in d.texts())
    d.capture("03-guide-preview.png")
    d.key(3)
    d.wait("TV video stops outside the app", lambda: not d.playing())
    d.launch("channel/" + radio_id)
    d.wait("TV radio reopened", lambda: d.playing("Radio Test Uno"))
    d.key(3)
    d.wait("TV radio stops outside the app", lambda: not d.playing())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--tv", required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/playback-smoke")
    args = parser.parse_args()
    audio = args.audio.read_bytes()
    requests = {}

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_GET(self):
            path = self.path.split("?")[0]
            requests[path] = requests.get(path, 0) + 1
            if path == "/video.mp4":
                self.path = "/" + args.video.name
                return super().do_GET()
            if path not in ("/radio1.mp3", "/radio2.mp3"):
                return self.send_error(404)
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            icy = self.headers.get("Icy-MetaData") == "1"
            if icy: self.send_header("icy-metaint", "8192")
            self.send_header("icy-br", "128")
            self.end_headers()
            metadata = b"StreamTitle='Artista Test - Brano Test';"
            padding = ((len(metadata) + 15) // 16) * 16
            metadata = bytes([padding // 16]) + metadata.ljust(padding, b"\0")
            offset = 0
            try:
                while True:
                    chunk = (audio + audio)[offset:offset + 8192]
                    offset = (offset + 8192) % len(audio)
                    self.wfile.write(chunk)
                    if icy: self.wfile.write(metadata)
                    self.wfile.flush()
                    time.sleep(.35)
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    devices = [Device(args.adb, serial, args.output) for serial in [args.phone, args.tv]]
    backup = {}
    scales = {}
    try:
        for device in devices:
            device.stop()
            device.run("logcat", "-c")
            backup[device.serial] = {path: device.read(path) for path in FILES}
            scales[device.serial] = device.run("shell", "settings", "get", "system", "font_scale").decode().strip()
            device.run("shell", "settings", "put", "system", "font_scale", "1.0")
            device.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
            radio_id, tv_id = fixture(device, server.server_port)
        phone_checks(devices[0], radio_id, tv_id, requests)
        tv_checks(devices[1], radio_id, tv_id)
        for device in devices:
            errors = device.run("logcat", "-d", "-s", "AndroidRuntime:E").decode(errors="replace")
            device.wait("no Android crash", lambda: "FATAL EXCEPTION" not in errors)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "report.json").write_text(json.dumps({d.serial: d.checks for d in devices}, ensure_ascii=False, indent=2) + "\n")
    finally:
        for device in devices:
            device.stop()
            for path, value in backup.get(device.serial, {}).items(): device.write(path, value)
            scale = scales.get(device.serial, "null")
            if scale == "null": device.run("shell", "settings", "delete", "system", "font_scale")
            else: device.run("shell", "settings", "put", "system", "font_scale", scale)
            device.key(224)
        server.shutdown()


if __name__ == "__main__":
    main()
