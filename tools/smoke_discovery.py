#!/usr/bin/env python3
"""Exercise Focus with a synthetic EPG/video on a disposable Android TV emulator.

Restores app data and font scale. Does not request any broadcaster streams.
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
from datetime import datetime, time as day_time, timedelta
from zoneinfo import ZoneInfo

PACKAGE = "it.lumetv.app"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/discovery-smoke")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical TV.")
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []

    def adb(*command, body=None, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=body,
                              capture_output=True, check=check, timeout=30).stdout

    def key(*codes):
        adb("shell", "input", "keyevent", *map(str, codes))

    def stop():
        adb("shell", "am", "force-stop", PACKAGE)

    def launch():
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
            "lumetv://discover", "-n", PACKAGE + "/.MainActivity")

    def nodes():
        adb("shell", "uiautomator", "dump", "/sdcard/lume-discovery-smoke.xml")
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/lume-discovery-smoke.xml")).iter("node"))

    def texts(items=None):
        return [n.get("text", "") for n in (items if items is not None else nodes())]

    def focused(items=None):
        return next(n for n in (items if items is not None else nodes()) if n.get("focused") == "true")

    def click(label, description=False):
        attribute = "content-desc" if description else "text"
        candidates = [n for n in nodes() if n.get("clickable") == "true" and
                      " ".join(n.get(attribute, "").split()).casefold() == " ".join(label.split()).casefold()]
        if not candidates:
            raise AssertionError("Missing control: " + label)
        left, top, right, bottom = map(int, re.findall(r"\d+", candidates[-1].get("bounds")))
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def read(path):
        result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", path],
                                capture_output=True, timeout=20)
        return result.stdout if result.returncode == 0 else None

    def write(path, data):
        if data is None:
            adb("shell", "run-as", PACKAGE, "rm", "-f", path)
        else:
            adb("shell", "run-as " + PACKAGE + " sh -c 'cat > " + path + "'", body=data)

    def passed(label, ok):
        if not ok:
            raise AssertionError(label)
        checks.append(label)
        print("PASS " + label, flush=True)

    def capture(name):
        (args.output / name).write_bytes(adb("exec-out", "screencap", "-p"))

    class QuietHandler(SimpleHTTPRequestHandler):
        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(QuietHandler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    url = "http://10.0.2.2:" + str(server.server_port) + "/" + args.video.name
    paths = ["files/channels.m3u", "files/guide.json", "shared_prefs/guide.xml", "shared_prefs/lume.xml",
             "shared_prefs/app_updates.xml", "shared_prefs/discovery.xml"]
    stop()
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
    backup = {path: read(path) for path in paths}
    font_scale = adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    now = int(adb("shell", "date", "+%s")) * 1000
    zone = ZoneInfo(adb("shell", "getprop", "persist.sys.timezone").decode().strip() or "UTC")
    day = datetime.fromtimestamp(now / 1000, zone).date()
    evening = int(datetime.combine(day, day_time(20, 30), zone).timestamp()) * 1000
    midnight = int(datetime.combine(day + timedelta(days=1), day_time(), zone).timestamp()) * 1000
    try:
        playlist = ["#EXTM3U"]
        guide = {}
        names = {1: "Rai 1", 2: "La7", 3: "Rai 3", 4: "Rete 4", 5: "TV8", 6: "Rai Movie"}
        titles = {1: "A un passo dal cielo", 2: "Città aperta", 4: "Quarta Repubblica",
                  5: "Alessandro Borghese · 4 ristoranti", 6: "Il diritto di contare"}
        for number in range(1, 21):
            identity = "focus" + str(number)
            name = names.get(number, "Canale " + str(number))
            playlist += [f'#EXTINF:-1 tvg-id="{identity}" tvg-chno="{number}" group-title="Focus test",{name}', url]
            guide[identity] = [] if number == 3 else [
                {"title": titles.get(number, "Il programma di " + name),
                 "start": now - 30 * 60_000, "end": now + 10 * 60_000,
                 "description": "Una storia di incontri, scelte e nuovi inizi. " * 12},
                {"title": "Un professore · Una nuova lezione da imparare" if number == 1 else "Il viaggio di " + name,
                 "start": now + 10 * 60_000, "end": now + 90 * 60_000,
                 "description": "Un viaggio alla scoperta dei luoghi e delle persone. " * 10}]
            if number == 20:
                guide[identity] = [{"title": "Il cinema di stasera", "start": evening, "end": midnight,
                                    "description": "Un film per la tua serata."}]
        for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
            playlist += [f'#EXTINF:-1 tvg-id="unused{number}" tvg-chno="{number}",Validazione {number}',
                         f'plugin://plugin.video.wltvhelper/play/{broadcaster}/unused{number}']
        first_id = hashlib.sha256(b"focus1|Rai 1|Focus test").hexdigest()[:24]
        write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
        write("files/guide.json", json.dumps(guide).encode())
        write("shared_prefs/guide.xml", f'<map><long name="updated" value="{now}"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>'.encode())
        write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/><set name="favorites"><string>{first_id}</string></set></map>'.encode())
        write("shared_prefs/app_updates.xml", b'<map><boolean name="automatic" value="false"/></map>')
        write("shared_prefs/discovery.xml", b'<map/>')
        launch()
        first = focused()
        passed("Focus apre il programma attuale del preferito", "A un passo dal cielo" in first.get("content-desc", ""))
        capture("focus.png")
        key(20)
        passed("Freccia giu aggiorna i dettagli senza avviare il player", "Città aperta" in focused().get("content-desc", ""))
        key(21)
        passed("Freccia sinistra raggiunge Guarda ora", "Guarda ora" in focused().get("text", ""))
        key(22)
        passed("Le azioni raggiungono il comando preferiti", "preferiti" in focused().get("content-desc", ""))
        key(22)
        passed("Freccia destra torna al programma selezionato", "Città aperta" in focused().get("content-desc", ""))
        click("Cerca")
        adb("shell", "input", "text", "Citta")
        found = texts()
        passed("Ricerca ignora gli accenti", "1 programma" in found and "Città aperta" in found)
        adb("shell", "input", "text", "12")
        editing = nodes()
        passed("Numeri durante la ricerca restano nel testo", any(n.get("class") == "android.widget.EditText" and "Citta12" in n.get("text", "") for n in editing))
        passed("Nessun risultato mantiene accessibile il catalogo", "Apri i canali" in texts(editing))
        click("Chiudi la ricerca", description=True)
        click("★ Solo preferiti")
        passed("Filtro preferiti mostra soltanto i programmi preferiti", "1 programma" in texts())
        click("★ Solo preferiti")
        click("Tra poco")
        future = focused().get("content-desc", "")
        passed("Tra poco seleziona un programma futuro", "Un professore" in future)
        key(23)
        passed("OK salva un promemoria", "✓ Avviso attivo" in texts())
        key(165)
        passed("INFO apre la descrizione completa", any("Un viaggio alla scoperta" in t for t in texts()))
        key(4)
        passed("Chiudere i dettagli ripristina il programma", "Un professore" in focused().get("content-desc", ""))
        click("Ora")
        before = focused().get("content-desc", "")
        frames = adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame")
        key(23)
        deadline = time.monotonic() + 15
        played = False
        while time.monotonic() < deadline:
            if adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame") > frames:
                played = True
                break
            time.sleep(.5)
        passed("OK avvia la diretta sintetica nel player esistente", played)
        key(4)
        passed("Ritorno dalla diretta conserva la selezione", focused().get("content-desc", "") == before)
        key(*([20] * 35))
        passed("Pressioni rapide raggiungono la fine dei programmi", "Canale 19" in focused().get("content-desc", "") or "Canale 20" in focused().get("content-desc", ""))
        click("Stasera")
        passed("Stasera comprende la fascia serale", "0 programmi" not in texts())
        capture("focus-tonight.png")
        click("Tra poco")
        saved = focused().get("content-desc", "")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        large = nodes()
        passed("Caratteri ingranditi conservano ricerca fascia e selezione", focused(large).get("content-desc", "") == saved)
        capture("focus-large-text.png")
        stop(); launch()
        passed("Riapertura conserva la fascia e il promemoria", "✓ Avviso attivo" in texts())
        # Make the stored reminder due without altering the emulator's system clock.
        stop()
        preferences = ET.fromstring(read("shared_prefs/discovery.xml"))
        reminder_row = next(n for n in preferences if n.get("name") == "reminders")
        pending = json.loads(reminder_row.text)
        current_time = int(adb("shell", "date", "+%s")) * 1000
        pending[0]["start"] = current_time + 60_000
        reminder_row.text = json.dumps(pending)
        write("shared_prefs/discovery.xml", ET.tostring(preferences))
        launch()
        deadline = time.monotonic() + 40
        delivered = False
        while time.monotonic() < deadline:
            if "Sta per iniziare" in texts():
                delivered = True
                break
            time.sleep(.5)
        passed("Promemoria mostra le azioni mentre Lume e aperta", delivered)
        click("Più tardi")
        passed("Ignorare l’avviso non apre la diretta", "Cosa guardare." in texts())
        report = {"device": args.serial, "checks": checks, "passed": len(checks),
                  "playback": "local synthetic MP4", "scope": "Android TV emulator; physical TV not tested"}
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except Exception:
        capture("failure.png")
        raise
    finally:
        stop()
        for path, data in backup.items():
            write(path, data)
        if font_scale == "null":
            adb("shell", "settings", "delete", "system", "font_scale")
        else:
            adb("shell", "settings", "put", "system", "font_scale", font_scale)
        server.shutdown()


if __name__ == "__main__":
    main()
