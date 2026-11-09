#!/usr/bin/env python3
"""Check catalog refresh, semantics and press feedback on a disposable TV emulator.

Uses a local, unplayed stream URL and restores app data, font scale and animation
settings. ImageMagick reads a background pixel to verify the pressed state.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "it.lumetv.app"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", default="emulator-5560")
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/ui-smoke")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Use a disposable emulator, never a physical TV.")
    if not shutil.which("magick"):
        parser.error("ImageMagick is required for the press-feedback check.")
    args.output.mkdir(parents=True, exist_ok=True)
    checks = []

    def adb(*command, body=None, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=body,
                              capture_output=True, check=check, timeout=30).stdout

    def read(path):
        result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "cat", path],
                                capture_output=True, timeout=20)
        return result.stdout if result.returncode == 0 else None

    def write(path, data):
        # Private paths are fixed below, never derived from external input.
        if data is None:
            adb("shell", "run-as", PACKAGE, "rm", "-f", path)
        else:
            adb("shell", "run-as " + PACKAGE + " sh -c 'cat > " + path + "'", body=data)

    def key(*codes):
        adb("shell", "input", "keyevent", *map(str, codes))

    def nodes():
        adb("shell", "uiautomator", "dump", "/sdcard/lume-ui-smoke.xml")
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/lume-ui-smoke.xml")).iter("node"))

    def focused(items):
        return next(node for node in items if node.get("focused") == "true")

    def passed(label, condition):
        if not condition:
            raise AssertionError(label)
        checks.append(label)
        print("PASS " + label, flush=True)

    def capture(name):
        path = args.output / name
        path.write_bytes(adb("exec-out", "screencap", "-p"))
        return path

    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))

    def background_pixel(path, node):
        left, top, right, bottom = bounds(node)
        point = f"%[pixel:p{{{left + 24},{(top + bottom) // 2}}}]"
        return subprocess.run(["magick", str(path), "-format", point, "info:"],
                              capture_output=True, check=True, timeout=20).stdout.decode().strip()

    paths = ["files/channels.m3u", "files/guide.json", "shared_prefs/guide.xml",
             "shared_prefs/lume.xml", "shared_prefs/app_updates.xml"]
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
    backup = {path: read(path) for path in paths}
    font_scale = adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    animation_scale = adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
    try:
        adb("shell", "settings", "put", "system", "font_scale", "1.0")
        now = int(adb("shell", "date", "+%s")) * 1000
        playlist = ["#EXTM3U"]
        guide = {}
        # Catalog policy accepts at least 20 entries, including validation integrations.
        for number in range(1, 25):
            name = "Rai 1" if number == 1 else "Canale " + str(number)
            playlist += [f'#EXTINF:-1 tvg-id="ui{number}" tvg-chno="{number}" group-title="UI test",{name}',
                         "http://127.0.0.1:9/unplayed.mp4"]
            guide["ui" + str(number)] = [
                {"title": "Prima del cambio", "start": now - 60_000, "end": now + 15_000},
                {"title": "Dopo il cambio", "start": now + 15_000, "end": now + 3_600_000}]
        for number, broadcaster in enumerate(["rai"] * 3 + ["mediaset"] * 3, 101):
            playlist += [f'#EXTINF:-1 tvg-id="unused{number}" tvg-chno="{number}",Validazione {number}',
                         f'plugin://plugin.video.wltvhelper/play/{broadcaster}/unused{number}']
        first_id = hashlib.sha256(b"ui1|Rai 1|UI test").hexdigest()[:24]
        write("files/channels.m3u", ("\n".join(playlist) + "\n").encode())
        write("files/guide.json", json.dumps(guide).encode())
        write("shared_prefs/guide.xml", f'<map><long name="updated" value="{now}"/><int name="policy_version" value="1"/><int name="logo_metadata_version" value="1"/></map>'.encode())
        write("shared_prefs/lume.xml", f'<map><long name="catalog_updated" value="{now}"/><set name="favorites"><string>{first_id}</string></set></map>'.encode())
        write("shared_prefs/app_updates.xml", b'<map><boolean name="automatic" value="false"/></map>')
        adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
            "lumetv://catalog", "-n", PACKAGE + "/.MainActivity")
        # Leave touch mode left by another smoke test, then restore the first card.
        key(20)
        items = nodes()
        first = next(node for node in items if node.get("content-desc", "").startswith("1, Rai 1,"))
        passed("Scheda catalogo espone il programma attuale", "Prima del cambio" in first.get("content-desc", ""))
        passed("Preferito annunciato nella scheda catalogo", "preferito" in first.get("content-desc", ""))
        passed("Scheda canale espone il ruolo pulsante", first.get("class") == "android.widget.Button")
        passed("Sezione Canali espone selezione e ruolo", any(node.get("text") == "Canali" and
            node.get("selected") == "true" and node.get("class") == "android.widget.Button" for node in items))
        passed("Filtro Tutti espone la selezione", any(node.get("text") == "Tutti" and node.get("selected") == "true" for node in items))
        before_focus = focused(items)
        capture("catalog.png")
        deadline = time.monotonic() + 45
        while True:
            items = nodes()
            first = next(node for node in items if node.get("content-desc", "").startswith("1, Rai 1,"))
            if "Dopo il cambio" in first.get("content-desc", "") or time.monotonic() >= deadline:
                break
            time.sleep(1)
        passed("Catalogo aggiorna il programma senza navigazione o refresh", "Dopo il cambio" in first.get("content-desc", ""))
        after_focus = focused(items)
        passed("Aggiornamento programma conserva focus e posizione", bounds(after_focus) == bounds(before_focus) and
               after_focus.get("content-desc", "").split(", ")[:2] == before_focus.get("content-desc", "").split(", ")[:2])
        capture("catalog-updated.png")
        for _ in range(6):
            if focused(nodes()).get("text") == "Canali":
                break
            key(19)
        button = focused(nodes())
        passed("Telecomando raggiunge la navigazione principale", button.get("text") == "Canali")
        resting = capture("button-focused.png")
        # Keep Android's confirm key down while capturing, before the long-press timeout.
        press = subprocess.Popen([args.adb, "-s", args.serial, "shell", "input", "dpad", "keyevent", "--longpress", "23"],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            time.sleep(.18)
            pressed = capture("button-pressed.png")
        finally:
            stdout, stderr = press.communicate(timeout=10)
            if press.returncode:
                raise RuntimeError(stderr.decode(errors="replace"))
        passed("OK premuto produce un feedback distinto dal focus",
               background_pixel(resting, button) != background_pixel(pressed, button))
        adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
        key(19, 19)
        passed("Focus utilizzabile con animazioni disabilitate", focused(nodes()).get("clickable") == "true")
        capture("reduced-motion.png")
        adb("shell", "settings", "put", "system", "font_scale", "1.3")
        time.sleep(1)
        items = nodes()
        width, height = map(int, re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").decode()).groups())
        labels = {"Canali", "Cosa guardare", "Guida TV", "↻", "Opzioni", "Esci"}
        navigation = [node for node in items if node.get("text") in labels and node.get("clickable") == "true"]
        passed("Testo ingrandito mantiene tutti i comandi principali", {node.get("text") for node in navigation} == labels)
        passed("Navigazione con testo ingrandito resta nello schermo", all(
            0 <= left < right <= width and 0 <= top < bottom <= height
            for left, top, right, bottom in map(bounds, navigation)))
        capture("catalog-large-text.png")
        (args.output / "report.json").write_text(json.dumps({"device": args.serial, "checks": checks,
            "passed": len(checks), "scope": "Android TV emulator; local fixtures; physical TV and TalkBack speech not tested"},
            ensure_ascii=False, indent=2) + "\n")
    except Exception:
        capture("failure.png")
        raise
    finally:
        adb("shell", "am", "force-stop", PACKAGE)
        for path, data in backup.items():
            write(path, data)
        for namespace, name, original in [("system", "font_scale", font_scale),
                                           ("global", "animator_duration_scale", animation_scale)]:
            if original == "null":
                adb("shell", "settings", "delete", namespace, name)
            else:
                adb("shell", "settings", "put", namespace, name, original)


if __name__ == "__main__":
    main()
