#!/usr/bin/env python3
"""Exercise update prompts with cached metadata and a local video on disposable Android emulators.

No release is published or APK downloaded. App data and display settings are restored.
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
from urllib.parse import quote
import xml.etree.ElementTree as ET

PACKAGE = "it.lumetv.app"
ROOT = Path(__file__).resolve().parents[1]
TITLE = "Nuova versione disponibile"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["tv", "phone"], required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical device.")
    if not args.video.is_file():
        parser.error("Supply a local synthetic MP4.")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").unlink(missing_ok=True)
    checks = []

    def adb(*command, body=None, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=body,
                              capture_output=True, check=check, timeout=40).stdout

    def stop():
        adb("shell", "am", "force-stop", PACKAGE)

    def read(name):
        result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", name],
                                capture_output=True, timeout=20)
        return result.stdout if result.returncode == 0 and not result.stdout.startswith(b"cat:") else None

    def write(name, content):
        if content is None:
            adb("shell", "run-as", PACKAGE, "rm", "-f", name)
        else:
            adb("shell", "run-as " + PACKAGE + " sh -c 'cat > " + name + "'", body=content)

    def key(*codes):
        adb("shell", "input", "keyevent", *map(str, codes))

    def nodes():
        result = adb("shell", "uiautomator", "dump", "/sdcard/lume-update-smoke.xml")
        if b"dumped to:" not in result:
            raise AssertionError("Android did not return the current UI hierarchy: " + result.decode(errors="replace"))
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/lume-update-smoke.xml")).iter("node"))

    def texts(items=None):
        values = {node.get("text", "") for node in (nodes() if items is None else items)}
        return values | {value.capitalize() for value in values if value.isupper()}

    def wait_for(label, predicate, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            items = nodes()
            if predicate(items):
                return items
            time.sleep(.3)
        raise AssertionError(label)

    def passed(label, condition=True):
        if not condition:
            raise AssertionError(label)
        checks.append(label)
        print("PASS " + label, flush=True)

    def click(label):
        item = next(node for node in nodes() if node.get("text", "").casefold() == label.casefold() or node.get("content-desc") == label)
        left, top, right, bottom = map(int, re.findall(r"\d+", item.get("bounds", "")))
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def capture(name):
        (args.output / name).write_bytes(adb("exec-out", "screencap", "-p"))

    def launch(destination="catalog", launcher=False):
        if launcher:
            adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c",
                "android.intent.category.LAUNCHER", "-n", PACKAGE + "/.MainActivity")
        else:
            adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
                "lumetv://" + destination, "-n", PACKAGE + "/.MainActivity")

    def update_prefs(candidate, automatic=True):
        prefs = ET.Element("map")
        ET.SubElement(prefs, "boolean", name="automatic", value=str(automatic).lower())
        for name in ["last_success", "last_attempt"]:
            ET.SubElement(prefs, "long", name=name, value=str(int(adb("shell", "date", "+%s")) * 1000))
        if candidate is not None:
            ET.SubElement(prefs, "string", name="candidate").text = json.dumps(candidate)
        return ET.tostring(prefs)

    class QuietHandler(SimpleHTTPRequestHandler):
        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0),
        functools.partial(QuietHandler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    url = "http://10.0.2.2:" + str(server.server_port) + "/" + quote(args.video.name)
    paths = ["files/channels.m3u", "files/guide.json", "shared_prefs/guide.xml", "shared_prefs/lume.xml",
             "shared_prefs/app_updates.xml", "shared_prefs/discovery.xml", "shared_prefs/interface.xml"]
    stop()
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
    backup = {name: read(name) for name in paths}
    font_scale = adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    rotation_policy = adb("shell", "wm", "user-rotation").decode().strip().split()
    now = int(adb("shell", "date", "+%s")) * 1000
    installed_code = int(re.search(rb"versionCode=(\d+)", adb("shell", "dumpsys", "package", PACKAGE))[1])
    candidate = {"schema": 1, "applicationId": PACKAGE, "versionName": "99.0.0", "versionCode": installed_code + 1,
                 "minSdk": 23, "prerelease": False, "apk": {"name": "LumeTV-99.0.0.apk", "size": 1, "sha256": "0" * 64}}
    first_id = hashlib.sha256(b"update1|Canale test 1|Update test").hexdigest()[:24]

    def seed(info=candidate, automatic=True):
        stop()
        write("shared_prefs/app_updates.xml", update_prefs(info, automatic))
        write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/></map>'.encode())
        write("shared_prefs/interface.xml", ('<map><string name="mode">' +
              ("TV" if args.mode == "tv" else "SMARTPHONE") + '</string></map>').encode())
        if b'"versionCode"' not in (read("shared_prefs/app_updates.xml") or b'') and info is not None:
            raise AssertionError("Update fixture was not written")

    try:
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        if args.mode == "phone":
            adb("shell", "wm", "user-rotation", "lock", "0")
        playlist = ["#EXTM3U"]
        guide = {}
        for number in range(1, 21):
            playlist += [f'#EXTINF:-1 tvg-id="update{number}" tvg-chno="{number}" group-title="Update test",Canale test {number}', url]
            guide["update" + str(number)] = [{"title": "Programma di prova", "start": now - 60_000, "end": now + 3_600_000}]
        for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
            playlist += [f'#EXTINF:-1 tvg-id="unused{number}" tvg-chno="{number}",Validazione {number}',
                         f'plugin://plugin.video.wltvhelper/play/{broadcaster}/unused{number}']
        write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
        write("files/guide.json", json.dumps(guide).encode())
        write("shared_prefs/guide.xml", f'<map><long name="updated" value="{now}"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>'.encode())
        write("shared_prefs/discovery.xml", b'<map/>')
        seed()
        launch()
        items = wait_for("Popup nel catalogo", lambda items: TITLE in texts(items))
        passed("La versione salvata mostra il popup nel catalogo", "Aggiorna" in texts(items) and "Più tardi" in texts(items))
        if args.mode == "tv":
            passed("Focus iniziale su Più tardi", any(n.get("text", "").casefold() == "più tardi" and n.get("focused") == "true" for n in items))
        capture("popup.png")
        key(4)
        passed("Indietro rinvia il popup", TITLE not in texts())
        focus = next((n.get("content-desc") or n.get("text") for n in nodes() if n.get("focused") == "true"), None)
        if args.mode == "tv":
            passed("La chiusura ripristina il focus nel catalogo", bool(focus))
        click("Opzioni")
        click("Chiudi")
        passed("Il ritorno da Opzioni non ripropone la stessa versione", TITLE not in texts() and
               not any(value.startswith("Interfaccia:") for value in texts()))
        key(223)
        time.sleep(1)
        key(224)
        adb("shell", "wm", "dismiss-keyguard")
        passed("Il ritorno dallo standby conserva il rinvio", TITLE not in texts())
        launch(launcher=True)
        wait_for("Popup al nuovo avvio", lambda items: TITLE in texts(items))
        passed("Un nuovo avvio dal launcher ripropone la versione")
        click("Più tardi")
        passed("Più tardi chiude l’avviso", TITLE not in texts())
        launch(launcher=True)
        wait_for("Popup prima dell’azione Aggiorna", lambda items: TITLE in texts(items))
        click("Aggiorna")
        items = wait_for("Schermata Aggiornamenti", lambda items: "Aggiornamenti" in texts(items))
        passed("Aggiorna apre il flusso esistente senza scaricare automaticamente", "Scarica e aggiorna" in texts(items))
        launch(launcher=True)
        passed("Il popup non si sovrappone alla schermata Aggiornamenti", "Aggiornamenti" in texts() and TITLE not in texts())
        key(4)
        passed("Il ritorno dal controllo manuale non duplica il popup", TITLE not in texts())
        click("Opzioni")
        current_interface = next(n.get("text") for n in nodes() if n.get("text", "").startswith("Interfaccia:"))
        click(current_interface)
        click("Smartphone" if args.mode == "tv" else "TV")
        wait_for("Cambio interfaccia", lambda items: "Canali" in texts(items))
        passed("Il cambio di interfaccia conserva il rinvio", TITLE not in texts())

        seed()
        adb("logcat", "-c")
        launch()
        wait_for("Avviso prima dell’apertura esterna del canale", lambda items: TITLE in texts(items))
        launch("channel/" + first_id)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline and b"first_frame" not in adb("logcat", "-d", "-s", "LumePlayer:I"):
            time.sleep(.5)
        passed("Diretta sintetica avviata", b"first_frame" in adb("logcat", "-d", "-s", "LumePlayer:I"))
        passed("Nessun popup durante la diretta", TITLE not in texts())
        passed("Un collegamento al canale chiude il popup già aperto", TITLE not in texts())
        key(4)
        wait_for("Avviso dopo la diretta", lambda items: TITLE in texts(items))
        passed("Il popup in attesa compare tornando al catalogo")
        click("Più tardi")

        if args.mode == "tv":
            seed()
            write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/><string name="last_channel">{first_id}</string></map>'.encode())
            adb("logcat", "-c")
            launch(launcher=True)
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline and b"first_frame" not in adb("logcat", "-d", "-s", "LumePlayer:I"):
                time.sleep(.5)
            passed("L’avvio ripristina l’ultima diretta senza popup", b"first_frame" in adb("logcat", "-d", "-s", "LumePlayer:I") and TITLE not in texts())
            key(4)
            wait_for("Avviso dopo la diretta ripristinata", lambda items: TITLE in texts(items))
            passed("La diretta ripristinata rinvia l’avviso al catalogo")
            click("Più tardi")

        seed()
        launch("integration")
        items = wait_for("Dialog di integrazione", lambda items: "Collegamento con AURA" in texts(items))
        passed("Il popup non si sovrappone ad altre finestre", TITLE not in texts(items))
        key(4)
        wait_for("Avviso dopo la finestra", lambda items: TITLE in texts(items))
        passed("L’avviso in attesa compare alla chiusura dell’altra finestra")
        click("Più tardi")

        for label, info, automatic in [("Controllo disattivato", candidate, False),
                                       ("Nessuna versione disponibile", None, True),
                                       ("Versione già installata", dict(candidate, versionCode=installed_code), True),
                                       ("Versione incompatibile", dict(candidate, minSdk=999), True)]:
            seed(info, automatic)
            launch()
            wait_for("Catalogo pronto", lambda items: "Canali" in texts(items))
            passed(label + ": nessun popup", TITLE not in texts())

        stop()
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        seed()
        launch()
        items = wait_for("Popup con caratteri ingranditi", lambda items: TITLE in texts(items))
        passed("Caratteri al 130%: entrambe le azioni accessibili", "Aggiorna" in texts(items) and "Più tardi" in texts(items))
        capture("popup-font-130.png")
        key(4)
        passed("Nessun crash Java", b"FATAL EXCEPTION" not in adb("logcat", "-d", "-s", "AndroidRuntime:E"))
        (args.output / "report.json").write_text(json.dumps({"device": args.serial, "mode": args.mode,
            "checks": checks, "passed": len(checks), "scope": "Android emulator; cached synthetic update; local MP4; no release downloaded"},
            ensure_ascii=False, indent=2) + "\n")
    except Exception:
        capture("failure.png")
        (args.output / "failure-logcat.txt").write_bytes(adb("logcat", "-d", check=False))
        raise
    finally:
        stop()
        for name, content in backup.items():
            write(name, content)
        if font_scale == "null":
            adb("shell", "settings", "delete", "system", "font_scale")
        else:
            adb("shell", "settings", "put", "system", "font_scale", font_scale)
        if args.mode == "phone" and rotation_policy:
            adb("shell", "wm", "user-rotation", *rotation_policy)
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
