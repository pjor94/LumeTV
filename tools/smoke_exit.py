#!/usr/bin/env python3
"""Exercise exit confirmation on a disposable emulator with a debug APK.

Uses a local synthetic MP4 and restores the catalog, guide and preferences.
Never selects the catalog's broadcaster validation entries.
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
from urllib.parse import quote
import xml.etree.ElementTree as ET

PACKAGE = "it.lumetv.app"
ROOT = Path(__file__).resolve().parents[1]
TITLE = "Uscire da Lume TV?"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--video", type=Path, required=True, help="A local synthetic MP4")
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/exit-smoke")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical TV.")
    if not args.video.is_file():
        parser.error("--video must name an existing synthetic MP4.")
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
            "lumetv://catalog", "-n", PACKAGE + "/.MainActivity")
        # Touch mode survives force-stop on the emulator, including a failed run.
        nodes()
        key(19)

    def nodes():
        adb("shell", "uiautomator", "dump", "/sdcard/lume-exit-smoke.xml")
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/lume-exit-smoke.xml")).iter("node"))

    def texts(items=None):
        return [node.get("text", "") for node in (items if items is not None else nodes())]

    def focused(items=None):
        return next(node for node in (items if items is not None else nodes()) if node.get("focused") == "true")

    def identity(node):
        return tuple(node.get(name, "") for name in ("text", "content-desc", "bounds"))

    def label(node):
        return node.get("text", "").strip().casefold()

    def click(value):
        candidates = [node for node in nodes() if node.get("clickable") == "true" and
                      label(node) == value.casefold()]
        if not candidates:
            raise AssertionError("Missing control: " + value)
        left, top, right, bottom = map(int, re.findall(r"\d+", candidates[-1].get("bounds")))
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        # Taps are a shortcut for setup only. Leave Android touch mode before
        # testing the remote's Back/default-focus behavior.
        nodes()
        key(19)
        return nodes()

    def read(path):
        result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", path],
                                capture_output=True, timeout=20)
        return result.stdout if result.returncode == 0 else None

    def write(path, content):
        # The private paths below are fixed constants, never user input.
        if content is None:
            adb("shell", "run-as", PACKAGE, "rm", "-f", path)
        else:
            adb("shell", "run-as " + PACKAGE + " sh -c 'cat > " + path + "'", body=content)

    def passed(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)
        print("PASS " + name, flush=True)

    def capture(name):
        (args.output / name).write_bytes(adb("exec-out", "screencap", "-p", check=False))

    def confirmation():
        items = nodes()
        return TITLE in texts(items) and label(focused(items)) == "annulla"

    def home():
        return "La TV, fatta semplice." in texts()

    def focus_exit():
        # Reach the global navigation using only the remote, then its last action.
        navigation = {"canali", "cosa guardare", "guida tv", "opzioni", "esci"}
        for _ in range(5):
            if label(focused()) in navigation:
                break
            key(19)
        for _ in range(8):
            if label(focused()) == "esci":
                return
            key(22)
        raise AssertionError("Esci is not reachable with the remote")

    def task_ids():
        output = adb("shell", "dumpsys", "activity", "recents").decode(errors="replace")
        return set(re.findall(r"Task\{[^\n]*#(\d+)[^\n]*\bit\.lumetv\.app\b", output))

    class QuietHandler(SimpleHTTPRequestHandler):
        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0),
        functools.partial(QuietHandler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    video_url = "http://10.0.2.2:" + str(server.server_port) + "/" + quote(args.video.name)
    paths = ["files/channels.m3u", "files/guide.json", "shared_prefs/guide.xml", "shared_prefs/lume.xml",
             "shared_prefs/app_updates.xml", "shared_prefs/discovery.xml"]
    stop()
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
    backup = {path: read(path) for path in paths}
    now = int(adb("shell", "date", "+%s")) * 1000
    try:
        playlist = ["#EXTM3U"]
        guide = {}
        for number in range(1, 21):
            channel = "exit" + str(number)
            playlist += [f'#EXTINF:-1 tvg-id="{channel}" tvg-chno="{number}" group-title="Exit test",Canale test {number}', video_url]
            guide[channel] = [{"title": "Programma di prova " + str(number), "start": now - 30 * 60_000,
                               "end": now + 60 * 60_000, "description": "Palinsesto sintetico per il collaudo."}]
        # Satisfy catalog validation without ever opening these entries.
        for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
            playlist += [f'#EXTINF:-1 tvg-id="unused{number}" tvg-chno="{number}",Validazione {number}',
                         f'plugin://plugin.video.wltvhelper/play/{broadcaster}/unused{number}']
        write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
        write("files/guide.json", json.dumps(guide).encode())
        write("shared_prefs/guide.xml", f'<map><long name="updated" value="{now}"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>'.encode())
        write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/></map>'.encode())
        write("shared_prefs/app_updates.xml", b'<map><boolean name="automatic" value="false"/></map>')
        write("shared_prefs/discovery.xml", b'<map/>')
        launch()
        passed("Home mostra il pulsante Esci", home() and "Esci" in texts())
        original_focus = identity(focused())
        key(4)
        passed("Back dalla Home apre la conferma con Annulla selezionato", confirmation())
        capture("exit-confirmation.png")
        key(23)
        passed("OK su Annulla conserva Home e focus", home() and identity(focused()) == original_focus)
        key(4)
        passed("Riaprire la conferma mantiene Annulla selezionato", confirmation())
        key(4)
        passed("Back sulla conferma annulla senza chiudere l'app", home() and TITLE not in texts())
        numeric_frames = adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame")
        key(8, 4)  # Queue synthetic channel 1, then open confirmation before its timeout.
        time.sleep(1.2)
        passed("Back annulla il numero pendente senza avviare la diretta", confirmation() and
               adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame") == numeric_frames)
        key(4)
        passed("Annullare dopo il numero pendente conserva la Home", home() and TITLE not in texts())
        focus_exit()
        passed("Esci e raggiungibile con le frecce del telecomando", label(focused()) == "esci")
        capture("exit-button.png")
        key(23)
        passed("Pulsante Esci apre la stessa conferma", confirmation())
        key(4)
        passed("Annullamento ripristina il focus su Esci", label(focused()) == "esci")
        click("Opzioni")
        key(4)
        passed("Back nelle Opzioni chiude soltanto le Opzioni", home() and TITLE not in texts())
        click("Preferiti")
        key(4)
        passed("Back dal filtro Preferiti chiede conferma", confirmation())
        key(4)
        click("Guida TV")
        key(4)
        passed("Back dalla Guida torna prima alla Home", home() and TITLE not in texts())
        click("Cosa guardare")
        click("Cerca")
        # Some emulator IMEs consume the first Back to dismiss their keyboard.
        key(4)
        if any(node.get("class") == "android.widget.EditText" for node in nodes()):
            key(4)
        passed("Back nella ricerca resta in Cosa guardare", "Cosa guardare." in texts() and TITLE not in texts())
        key(4)
        passed("Back da Cosa guardare torna prima alla Home", home() and TITLE not in texts())
        frames = adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame")
        key(8)  # Channel 1 is exclusively the supplied synthetic MP4.
        deadline = time.monotonic() + 20
        played = False
        while time.monotonic() < deadline:
            if adb("logcat", "-d", "-s", "LumePlayer:I").count(b"first_frame") > frames:
                played = True
                break
            time.sleep(.5)
        passed("Diretta sintetica avviata", played)
        key(4)
        passed("Back dalla diretta torna alla Home senza conferma", home() and TITLE not in texts())
        playback_focus = identity(focused())
        key(4)
        passed("Back dalla Home dopo la diretta chiede conferma", confirmation())
        key(23)
        passed("Annulla dopo la diretta conserva Home e selezione", home() and identity(focused()) == playback_focus)
        old_tasks = task_ids()
        passed("Task dell'app presente prima della chiusura", bool(old_tasks))
        key(4)
        passed("La conferma finale parte ancora da Annulla", confirmation())
        key(22)
        passed("Freccia destra seleziona Esci nella conferma", label(focused()) == "esci")
        key(23)
        deadline = time.monotonic() + 5
        while old_tasks & task_ids() and time.monotonic() < deadline:
            time.sleep(.2)
        passed("Conferma Esci rimuove il task dell'app", not old_tasks & task_ids())
        launch()
        passed("Riapertura dell'app dopo la chiusura funziona", home() and "Esci" in texts())
        report = {"device": args.serial, "checks": checks, "passed": len(checks),
                  "playback": "local synthetic MP4", "scope": "Android emulator; physical TV not tested"}
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except Exception:
        capture("failure.png")
        (args.output / "failure-logcat.txt").write_bytes(
            adb("logcat", "-d", "-s", "ActivityTaskManager:I", "AndroidRuntime:E", check=False))
        raise
    finally:
        stop()
        for path, content in backup.items():
            write(path, content)
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
