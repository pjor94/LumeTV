#!/usr/bin/env python3
"""Exercise the phone UI, routing and playback with local fixtures on an emulator.

Requires an installed debug APK and a synthetic MP4. Restores catalog, guide,
preferences, rotation and font scale. Never use this on a physical device.
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/mobile-smoke")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical phone.")
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []

    def adb(*command, body=None, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=body,
                              capture_output=True, check=check, timeout=40).stdout

    def stop():
        adb("shell", "am", "force-stop", PACKAGE)

    def read(path):
        result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", path],
                                capture_output=True, timeout=20)
        return result.stdout if result.returncode == 0 else None

    def write(path, value):
        if value is None:
            adb("shell", "run-as", PACKAGE, "rm", "-f", path)
        else:
            adb("shell", "run-as " + PACKAGE + " sh -c 'cat > " + path + "'", body=value)

    def launch(route="catalog"):
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
            "lumetv://" + route, "-n", PACKAGE + "/.MainActivity")

    def nodes():
        adb("shell", "uiautomator", "dump", "/sdcard/lume-mobile-smoke.xml")
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/lume-mobile-smoke.xml")).iter("node"))

    def texts(items=None):
        return [n.get("text", "") for n in (items if items is not None else nodes())]

    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))

    def click(label, description=False, prefix=False, scroll=False):
        attr = "content-desc" if description else "text"
        for attempt in range(5 if scroll else 1):
            candidates = [n for n in nodes() if (n.get("clickable") == "true" or n.get("class") in ("android.widget.CheckedTextView", "android.widget.TextView")) and
                          (n.get(attr, "").startswith(label) if prefix else n.get(attr, "") == label)]
            if candidates:
                left, top, right, bottom = bounds(candidates[-1])
                if right > left and bottom > top:
                    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                    return
            if scroll:
                adb("shell", "input", "swipe", "520", "1650", "520", "650", "350")
        capture("failure.png")
        (args.output / "failure.xml").write_bytes(adb("exec-out", "cat", "/sdcard/lume-mobile-smoke.xml"))
        raise AssertionError("Missing control: " + label)

    def capture(name):
        (args.output / name).write_bytes(adb("exec-out", "screencap", "-p"))

    def passed(name, condition):
        if not condition:
            capture("failure.png")
            (args.output / "failure.xml").write_bytes(adb("exec-out", "cat", "/sdcard/lume-mobile-smoke.xml"))
            raise AssertionError(name)
        checks.append(name)
        print("PASS " + name, flush=True)

    def foreground(activity):
        for _ in range(16):
            dump = adb("shell", "dumpsys", "activity", "activities").decode(errors="replace")
            if any(activity in line and ("Resumed" in line or "resumedActivity" in line) for line in dump.splitlines()):
                return True
            time.sleep(.25)
        return False

    def frame_marker():
        # Logcat is a ring buffer: an old event can disappear while a new one arrives.
        # Compare the latest timestamped event rather than the number retained.
        frames = [line for line in adb("logcat", "-d", "-s", "LumePlayer:I").splitlines() if b"first_frame" in line]
        return frames[-1] if frames else b""

    def player_count():
        return adb("logcat", "-d", "-s", "LumePlayer:I").count(b"player_created")

    def wait_frame(before):
        for _ in range(25):
            marker = frame_marker()
            if marker and marker != before:
                return True
            time.sleep(1)
        return False

    class QuietHandler(SimpleHTTPRequestHandler):
        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(QuietHandler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    url = "http://10.0.2.2:" + str(server.server_port) + "/" + args.video.name
    paths = ["files/channels.m3u", "files/radios.json", "files/guide.json", "shared_prefs/guide.xml", "shared_prefs/lume.xml",
             "shared_prefs/app_updates.xml", "shared_prefs/discovery.xml", "shared_prefs/interface.xml"]
    stop()
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
    backup = {path: read(path) for path in paths}
    settings = {key: adb("shell", "settings", "get", "system", key).decode().strip()
                for key in ["font_scale", "accelerometer_rotation", "user_rotation"]}
    rotation_policy = adb("shell", "wm", "user-rotation").decode().strip().split()
    now = int(adb("shell", "date", "+%s")) * 1000
    first_id = hashlib.sha256(b"mobile1|Rai 1|Mobile test").hexdigest()[:24]
    second_id = hashlib.sha256(b"mobile2|Canale 2|Mobile test").hexdigest()[:24]
    try:
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        adb("shell", "wm", "user-rotation", "lock", "0")
        playlist = ["#EXTM3U"]
        guide = {}
        for number in range(1, 25):
            name = "Rai 1" if number == 1 else "Canale " + str(number)
            if number == 4:
                name = "Un nome molto lungo · Televisione internazionale 日本語"
            playlist += [f'#EXTINF:-1 tvg-id="mobile{number}" tvg-chno="{number}" group-title="Mobile test",{name}', url]
            guide["mobile" + str(number)] = [] if number == 3 else [
                {"title": "Notizie in diretta", "start": now - 900_000, "end": now + 2_700_000,
                 "description": "Informazione, cronaca e approfondimenti dal territorio."},
                {"title": "Il prossimo programma", "start": now + 2_700_000, "end": now + 5_400_000,
                 "description": "Una storia di incontri, scelte e nuovi inizi. Il programma è disponibile nella guida locale."}]
        for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
            playlist += [f'#EXTINF:-1 tvg-id="unused{number}" tvg-chno="{number}",Validazione {number}',
                         f'plugin://plugin.video.wltvhelper/play/{broadcaster}/unused{number}']
        write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
        write("files/guide.json", json.dumps(guide).encode())
        write("shared_prefs/guide.xml", f'<map><long name="updated" value="{now}"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>'.encode())
        write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/><long name="radios_updated" value="{now}"/></map>'.encode())
        write("shared_prefs/app_updates.xml", b'<map><boolean name="automatic" value="false"/></map>')
        write("shared_prefs/discovery.xml", b'<map/>')
        write("shared_prefs/interface.xml", b'<map/>')
        launch()
        # Apply the lock with the app foregrounded: Android can restore a
        # previous app rotation when leaving a NOSENSOR launcher activity.
        adb("shell", "wm", "user-rotation", "lock", "0")
        initial = nodes()
        passed("Smartphone riconosciuto automaticamente", foreground(".MobileActivity"))
        passed("Cinque destinazioni touch disponibili", all(any(n.get("content-desc") == label and n.get("clickable") == "true" for n in initial)
               for label in ["Canali", "Radio", "Preferiti", "Guida", "Scopri"]))
        passed("Catalogo con programma in onda", any("Notizie in diretta" in n.get("content-desc", "") for n in initial))
        capture("01-catalog.png")
        click("Preferiti", description=True)
        passed("Stato preferiti vuoti", "I tuoi canali, qui" in texts())
        click("Canali", description=True)
        click("Aggiungi Rai 1 ai preferiti", description=True)
        click("Preferiti", description=True)
        preferred = nodes()
        passed("Preferito aggiunto con pulsante esplicito", any(n.get("content-desc") == "Rimuovi Rai 1 dai preferiti" for n in preferred))
        passed("Filtro preferiti contiene un solo canale", "1 canale" in texts(preferred))
        capture("02-favorites.png")
        click("Canali", description=True)
        click("Cerca un canale", description=True)
        adb("shell", "input", "text", "Rai")
        passed("Ricerca canali touch", "1 canale" in texts())
        adb("shell", "input", "keyevent", "4")
        click("Guida", description=True)
        guide_nodes = nodes()
        passed("Guida per canale con programmi futuri", any("Il prossimo programma" in n.get("content-desc", "") for n in guide_nodes))
        capture("03-guide.png")
        click("Il prossimo programma", description=True, prefix=True)
        passed("Dettagli del programma futuro", any("Una storia di incontri" in t for t in texts()))
        click("Avvisami prima dell’inizio", scroll=True)
        passed("Avviso futuro impostabile dal telefono", "Rimuovi avviso" in texts())
        capture("04-programme.png")
        adb("shell", "input", "keyevent", "4")
        click("Rai 1 · Cambia")
        click("3 · Canale 3")
        passed("Canale senza guida resta guardabile", "Guarda Canale 3" in texts())
        click("Scopri", description=True)
        click("Tra poco")
        passed("Scopri usa la selezione programmi condivisa", any("Il prossimo programma" in n.get("content-desc", "") for n in nodes()))
        capture("05-discovery.png")
        before_live = frame_marker()
        launch("channel/" + first_id)
        passed("Deep link apre la diretta sintetica", wait_frame(before_live))
        # The controllers auto-hide during viewing. A tap on the letterbox reveals them.
        adb("shell", "input", "tap", "25", "600")
        if not any(n.get("content-desc") == "Pausa" for n in nodes()):
            adb("shell", "input", "tap", "25", "600")
        click("Pausa", description=True)
        passed("Pausa touch disponibile", any(n.get("content-desc") == "Riprendi la diretta" for n in nodes()))
        capture("06-player-portrait.png")
        before_rotation = player_count()
        click("Schermo intero", description=True)
        landscape = nodes()
        if "Viewing full screen" in texts(landscape):
            click("Got it")
            landscape = nodes()
        passed("Rotazione mantiene la UI smartphone", foreground(".MobileActivity") and any(n.get("content-desc") == "Esci da schermo intero" for n in landscape))
        passed("Rotazione non ricrea il player", player_count() == before_rotation)
        passed("Pausa conservata dopo rotazione", any(n.get("content-desc") == "Riprendi la diretta" for n in landscape))
        capture("07-player-landscape.png")
        adb("shell", "input", "keyevent", "3")
        adb("shell", "am", "kill", PACKAGE)
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-n", PACKAGE + "/.MainActivity")
        restored = []
        for _ in range(8):
            restored = nodes()
            if any(n.get("content-desc") == "Riprendi la diretta" for n in restored):
                break
            time.sleep(.25)
        passed("Pausa e canale conservati al rientro dall’app", foreground(".MobileActivity") and
               "Rai 1" in texts(restored) and any(n.get("content-desc") == "Riprendi la diretta" for n in restored))
        click("Riprendi la diretta", description=True)
        # Open through the normal player controls, without leaving playback.
        click("Canali")
        before_picker = frame_marker()
        picker = nodes()
        passed("Elenco canali touch durante la diretta", any(n.get("content-desc") == "Chiudi elenco canali" for n in picker))
        capture("08-live-channels.png")
        click("Chiudi elenco canali", description=True)
        passed("Elenco canali non riavvia la diretta", frame_marker() == before_picker)
        launch("channel/" + second_id)
        passed("Deep link a caldo cambia canale", wait_frame(before_picker))
        launch("favorites")
        favorite_nodes = nodes()
        passed("Deep link torna ai preferiti", any(n.get("content-desc") == "Preferiti" and n.get("selected") == "true" for n in favorite_nodes))
        passed("Indietro non richiede un telecomando", foreground(".MobileActivity"))
        click("Opzioni", description=True)
        click("Interfaccia")
        click("TV")
        passed("Scelta manuale attiva la UI TV", foreground(".TvActivity"))
        capture("09-tv-override.png")
        stop(); launch()
        passed("Scelta manuale conservata al riavvio", foreground(".TvActivity"))
        click("Opzioni")
        click("Interfaccia")
        click("Automatica")
        passed("Ritorno automatico alla UI smartphone", foreground(".MobileActivity"))
        click("Preferiti", description=True)
        passed("Preferiti condivisi tra le due interfacce", any(n.get("content-desc") == "Rimuovi Rai 1 dai preferiti" for n in nodes()))
        adb("shell", "wm", "user-rotation", "lock", "0")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        enlarged = nodes()
        passed("Navigazione utilizzabile con testo al 130%", all(any(n.get("content-desc") == label and n.get("clickable") == "true" for n in enlarged)
               for label in ["Canali", "Preferiti", "Guida", "Scopri"]))
        capture("10-font-scale.png")
        click("Guida", description=True)
        capture("11-guide-font-scale.png")
        passed("Guida utilizzabile con testo al 130%", any("Notizie in diretta" in n.get("content-desc", "") for n in nodes()))
        errors = adb("logcat", "-d", "-s", "AndroidRuntime:E").decode(errors="replace")
        passed("Nessun crash Android", "FATAL EXCEPTION" not in errors)
        (args.output / "report.json").write_text(json.dumps({"passed": checks, "count": len(checks), "device": args.serial,
            "scope": "Android phone emulator; local EPG and synthetic MP4; physical phone and broadcaster DRM not tested"}, ensure_ascii=False, indent=2) + "\n")
    finally:
        stop()
        for path, value in backup.items():
            write(path, value)
        for key, value in settings.items():
            if value == "null":
                adb("shell", "settings", "delete", "system", key)
            else:
                adb("shell", "settings", "put", "system", key, value)
        if rotation_policy and rotation_policy[0] in ("free", "lock"):
            adb("shell", "wm", "user-rotation", *rotation_policy)
        server.shutdown()


if __name__ == "__main__":
    main()
