#!/usr/bin/env python3
"""Native guide smoke test on a disposable emulator with an installed debug APK.

Serves only a supplied synthetic video. Backs up and restores the app's catalog,
guide, preferences and font scale; never contacts broadcaster playback endpoints.
"""
import argparse
import functools
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--video", type=Path, required=True, help="A local synthetic MP4")
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/guide-smoke")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical TV.")
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []

    def adb(*command, body=None, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=body,
                              capture_output=True, check=check, timeout=30).stdout

    def stop():
        adb("shell", "am", "force-stop", PACKAGE)

    def read(path):
        result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", path],
                                capture_output=True, timeout=20)
        return result.stdout if result.returncode == 0 else None

    def write(path, content):
        # All private paths are constants in this test, never user input.
        if content is None:
            adb("shell", "run-as", PACKAGE, "rm", "-f", path)
        else:
            adb("shell", "run-as " + PACKAGE + " sh -c 'cat > " + path + "'", body=content)

    def key(*codes):
        adb("shell", "input", "keyevent", *map(str, codes))

    def nodes():
        adb("shell", "uiautomator", "dump", "/sdcard/lume-guide-smoke.xml")
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/lume-guide-smoke.xml")).iter("node"))

    def texts(items):
        return [node.get("text", "") for node in items]

    def focused(items=None):
        return next(node for node in (items or nodes()) if node.get("focused") == "true")

    def click(label, description=False):
        attribute = "content-desc" if description else "text"
        candidates = [n for n in nodes() if " ".join(n.get(attribute, "").split()) == " ".join(label.split()) and n.get("clickable") == "true"]
        # Local guide filters are below the global navigation.
        node = candidates[-1]
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def passed(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)
        print("PASS " + name, flush=True)

    class QuietHandler(SimpleHTTPRequestHandler):
        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0),
        functools.partial(QuietHandler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    video_url = "http://10.0.2.2:" + str(server.server_port) + "/" + args.video.name
    paths = ["files/channels.m3u", "files/guide.json", "shared_prefs/guide.xml", "shared_prefs/lume.xml", "shared_prefs/app_updates.xml"]
    stop()
    backup = {path: read(path) for path in paths}
    font_scale = adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    now = int(adb("shell", "date", "+%s").strip()) * 1000
    base = now // 1_800_000 * 1_800_000
    try:
        playlist = ["#EXTM3U"]
        guide = {}
        for number in range(1, 31):
            identity = "rai" + str(number) if number <= 3 else "guide" + str(number)
            name = "Rai " + str(number) if number <= 3 else "Canale " + str(number)
            playlist += ['#EXTINF:-1 tvg-id="' + identity + '" tvg-chno="' + str(number) + '" group-title="Guida test",' + name, video_url]
            intervals = [(0, 30, "Notizie in diretta"), (30, 35, "Notiziario breve"),
                         (35, 180, "Il cinema della sera"), (180, 360, "La seconda serata"),
                         (360, 480, "Programmazione notturna")]
            if number == 2:
                intervals = [(45, 180, "Il programma dopo il vuoto"), (180, 480, "La seconda serata")]
            guide[identity] = [] if number == 3 else [
                {"title": title, "start": base + start * 60_000, "end": base + end * 60_000,
                 "description": "Descrizione di prova: una storia di incontri, scelte e nuovi inizi. " * 6}
                for start, end, title in intervals]
        # Catalog validation requires at least three Rai and Mediaset integrations.
        for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
            playlist += ['#EXTINF:-1 tvg-id="unused' + str(number) + '" tvg-chno="' + str(number) + '",Canale di validazione ' + str(number),
                         "plugin://plugin.video.wltvhelper/play/" + broadcaster + "/unused" + str(number)]
        write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
        write("files/guide.json", json.dumps(guide).encode())
        write("shared_prefs/guide.xml", ('<map><long name="updated" value="' + str(now) +
              '"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>').encode())
        write("shared_prefs/lume.xml", ('<map><long name="catalog_updated" value="' + str(now) +
              '"/><boolean name="auto_update_check" value="false"/></map>').encode())
        write("shared_prefs/app_updates.xml", b'<map><boolean name="automatic" value="false"/></map>')
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "lumetv://guide", "-n", PACKAGE + "/.MainActivity")
        initial = nodes()
        passed("Apertura sul programma attuale", "Notizie in diretta" in focused(initial).get("content-desc", ""))
        click("★ Preferiti")
        passed("Preferiti vuoti utilizzabili", "La guida dei tuoi preferiti." in texts(nodes()))
        click("Tutti")
        passed("Rientro dai preferiti vuoti sul programma", "Notizie in diretta" in focused().get("content-desc", ""))
        key(22)
        passed("Programma di cinque minuti selezionabile", "Notiziario breve" in focused().get("content-desc", ""))
        key(20)
        passed("Cambio canale conserva l'orario nel vuoto del palinsesto", "Programmazione non disponibile" in focused().get("content-desc", ""))
        key(20)
        passed("Canale senza palinsesto navigabile", "Programmazione non disponibile" in focused().get("content-desc", ""))
        key(19, 19, 22)
        before_dialog = focused().get("content-desc")
        key(23)
        detail = nodes()
        passed("Dettagli futuri con descrizione", any("Descrizione di prova:" in t for t in texts(detail)))
        key(4)
        passed("Indietro ripristina il focus", focused().get("content-desc") == before_dialog)
        click("Ora successiva", description=True)
        future = focused().get("content-desc")
        future_times = sorted(t for t in texts(nodes()) if re.fullmatch(r"\d\d:\d\d", t))
        frames_before = adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame")
        click("▷ Guarda il canale ora")
        deadline = time.monotonic() + 15
        first_frame = False
        while time.monotonic() < deadline:
            log = adb("logcat", "-d", "-s", "LumePlayer:I").decode(errors="replace")
            if log.count("first_frame") > frames_before:
                first_frame = True
                break
            time.sleep(0.5)
        passed("Diretta sintetica avviata", first_frame)
        key(4)
        returned = nodes()
        passed("Ritorno dalla diretta conserva programma e fascia", focused(returned).get("content-desc") == future and
               sorted(t for t in texts(returned) if re.fullmatch(r"\d\d:\d\d", t)) == future_times)
        (args.output / "guide.png").write_bytes(adb("exec-out", "screencap", "-p"))
        adb("shell", "input", "keyevent", "--longpress", "23")
        click("★ Preferiti")
        passed("OK prolungato aggiunge il preferito", "La guida dei tuoi preferiti." not in texts(nodes()))
        click("Tutti")
        key(*([20] * 45))
        passed("Pressioni rapide raggiungono l'ultima riga", "Canale di validazione 106" in focused().get("content-desc", ""))
        key(8)  # Numeric 1 selects the row, without playback.
        time.sleep(1.2)
        passed("Numero canale seleziona la riga senza aprire la diretta", "Rai 1," in focused().get("content-desc", ""))
        key(166)
        passed("CH+ sfoglia i canali per pagina", "Canale 6," in focused().get("content-desc", ""))
        click("Adesso")
        key(8)
        time.sleep(1.2)
        key(19)
        passed("Uscita dalla griglia verso i controlli", focused().get("text") == "Tutti")
        key(19)
        passed("Controlli raggiungono le sezioni principali", focused().get("text") == "Guida TV")
        key(23)
        before_scale = focused().get("content-desc")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        time.sleep(1)
        large = nodes()
        passed("Caratteri ingranditi e ricreazione conservano la selezione", focused(large).get("content-desc") == before_scale)
        (args.output / "guide-large-text.png").write_bytes(adb("exec-out", "screencap", "-p"))
        report = {"device": args.serial, "checks": checks, "passed": len(checks), "playback": "local synthetic MP4",
                  "scope": "Android TV emulator; physical TV not tested"}
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except Exception:
        (args.output / "failure.png").write_bytes(adb("exec-out", "screencap", "-p", check=False))
        raise
    finally:
        stop()
        for path, content in backup.items():
            write(path, content)
        if font_scale == "null":
            adb("shell", "settings", "delete", "system", "font_scale")
        else:
            adb("shell", "settings", "put", "system", "font_scale", font_scale)
        server.shutdown()


if __name__ == "__main__":
    main()
