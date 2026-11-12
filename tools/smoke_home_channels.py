#!/usr/bin/env python3
"""Verify Home publication on disposable emulators, with local fixtures and the real TV provider.

Requires app-debug.apk and app-debug-androidTest.apk. Never accepts physical devices.
"""
import argparse
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from threading import Thread
import xml.etree.ElementTree as ET

from smoke_playback import Device, FILES, PACKAGE, ROOT, fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--phone", action="store_true")
    parser.add_argument("--projectivy-only", action="store_true", help="Keep the local fixture alive for manual emulator launcher QA; Enter verifies playback and restores data")
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/home-launchers")
    args = parser.parse_args()
    d = Device(args.adb, args.serial, args.output)
    home_file = "shared_prefs/home_channels.xml"
    d.stop()
    backup = {name: d.read(name) for name in dict.fromkeys(FILES + [home_file])}
    font = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *unused): pass
        def do_GET(self):
            if self.path == "/video.mp4": self.path = "/" + args.video.name
            super().do_GET()

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()

    def click(label, contains=False):
        nodes = [n for n in d.nodes() if (label.casefold() in n.get("text", "").casefold() if contains else n.get("text", "").casefold() == label.casefold())]
        if not nodes: raise AssertionError(("Missing control", label, d.texts()))
        left, top, right, bottom = map(int, re.findall(r"\d+", nodes[-1].get("bounds")))
        d.run("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2))

    def prefs():
        raw = d.read(home_file)
        if not raw: return {}
        result = {}
        for item in ET.fromstring(raw):
            value = [c.text for c in item] if item.tag == "set" else item.text if item.tag == "string" else item.get("value")
            result[item.get("name")] = value
        return result

    def inspect(operation="snapshot", key=None):
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "operation", operation]
        if key: command += ["-e", "id", key]
        output = d.run(*command, PACKAGE + ".test/it.lumetv.app.home.HomeInspection").decode(errors="replace")
        match = re.search(r"(?m)^INSTRUMENTATION_RESULT: snapshot=(.+)$", output)
        if not match: raise AssertionError(output)
        return json.loads(match.group(1))

    def open_home():
        d.launch("home")
        d.wait("Home settings reachable", lambda: "Canali nella Home" in d.texts())

    def approve():
        texts = d.texts()
        if "Add" in texts: click("Add")
        elif "Aggiungi" in texts: click("Aggiungi")
        elif "Canali pronti" in texts: click("Chiudi")

    def manual(labels, activating=False):
        click("Attiva e scegli canali" if activating else "Canali da mostrare")
        d.wait("selection mode", lambda: "Selezione manuale" in d.texts())
        d.capture("02-selection-mode.png")
        click("Selezione manuale"); click("Continua")
        d.wait("channel selection", lambda: any("Scegli canali" in text for text in d.texts()))
        click("Deseleziona tutti")
        for label in labels: click(label)
        d.wait("selection count matches the checked channels", lambda: f"Scegli canali · {len(labels)}/24" in d.texts())
        d.capture("02-manual-selection.png")
        click("Pubblica")
        d.wait("selection published", lambda: prefs().get("enabled") == "true" and int(prefs().get("count", 0)) == len(labels))
        approve()

    try:
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        if not args.phone:
            # Clean only Lume's own preview row before isolating test preferences.
            d.write(home_file, b'<map><boolean name="enabled" value="false"/></map>')
            inspect("publish")
        d.write(home_file, None)
        radio, first_tv = fixture(d, server.server_port)
        if args.phone:
            open_home()
            d.wait("phone explains TV-only publication", lambda: any("richiede un dispositivo Android TV" in text for text in d.texts()))
            d.capture("01-phone-unavailable.png"); click("Chiudi")
            d.wait("Back returns to phone options", lambda: "Interfaccia" in d.texts())
            assert "Canali nella Home" not in d.texts()
            d.checks.append("phone options omit the unsupported Home preference")
            return
        open_home()
        d.wait("Home consent is initially off", lambda: "Attiva e scegli canali" in d.texts())
        d.capture("01-home-off.png")
        manual(["1 · TV Test 1", "2 · TV Test 2", "3 · TV Test 3"], activating=True)
        first = inspect()
        (d.output / "published-provider.json").write_text(json.dumps(first, indent=2))
        assert len(first["rows"]) == 1 and len(first["programs"]) == 3, first
        assert all(row["live"] == 1 and row["type"] == 6 for row in first["programs"]), first
        assert {row["title"] for row in first["programs"]} == {"TV Test 1", "TV Test 2", "TV Test 3"}
        assert all("intent://channel/" in row["intent_uri"] and "scheme=lumetv;" in row["intent_uri"] and
                   "component=it.lumetv.app/" in row["intent_uri"] for row in first["programs"]), first
        assert not any(word in json.dumps(first) for word in ["10.0.2.2", "video.mp4", "Radio Test", "Authorization"])
        d.checks += ["real TV provider contains one row and exactly the chosen channels", "metadata contains explicit Lume links and no streams or radio"]
        poster = d.run("exec-out", "content", "read", "--uri", first["programs"][0]["poster_art_uri"])
        assert poster.startswith(b"\x89PNG"), poster[:200]
        (d.output / "poster.png").write_bytes(poster)
        d.checks.append("published poster is readable through the bounded artwork provider")
        if args.projectivy_only:
            d.run("logcat", "-c")
            d.run("shell", "am", "force-stop", "com.spocky.projengmenu")
            d.run("shell", "am", "start", "-W", "-n", "com.spocky.projengmenu/.ui.home.MainActivity")
            print("PROJECTIVY_FIXTURE_READY: open TV Test 2 in the launcher, then press Enter", flush=True)
            input()
            d.wait("a Projectivy channel tile opens the selected Lume stream", lambda: d.playing("TV Test 2"))
            d.capture("07-projectivy-to-player.png")
            return
        open_home(); d.capture("03-home-published.png")
        click("Chiudi")
        d.wait("dialog returns focus to the Home preference", lambda: any(n.get("focused") == "true" and n.get("content-desc", "").startswith("Canali nella Home:") for n in d.nodes()))
        d.capture("04-options-focus.png")

        open_home(); click("Canali da mostrare")
        d.wait("favorites mode is offered independently", lambda: any("I miei preferiti" in t for t in d.texts()))
        click("I miei preferiti", contains=True); click("Continua")
        d.wait("no favorites publishes no unrelated channels", lambda: prefs().get("selection") == "FAVORITES" and int(prefs().get("count", -1)) == 0)
        open_home(); d.capture("04-empty-favorites.png"); click("Chiudi")
        d.launch("catalog")
        d.wait("catalog available for a real favorite edit", lambda: "TV Test 2" in d.texts())
        node = next(n for n in d.nodes() if n.get("text") == "TV Test 2")
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        x, y = str((left+right)//2), str((top+bottom)//2)
        d.run("shell", "input", "swipe", x, y, x, y, "1200")
        d.wait("a favorite edit triggers the background publication job", lambda: int(prefs().get("count", 0)) == 1)
        favorites = inspect()
        assert [x["title"] for x in favorites["programs"]] == ["TV Test 2"], favorites
        d.checks.append("favorites mode follows a native catalog edit through JobScheduler")
        open_home(); manual(["1 · TV Test 1", "2 · TV Test 2", "3 · TV Test 3"])
        first = inspect()

        # An app restart must reconcile existing provider rows and retain stable database IDs.
        second = inspect("publish")
        assert [(x["_id"], x["internal_provider_id"]) for x in second["programs"]] == [(x["_id"], x["internal_provider_id"]) for x in first["programs"]]
        assert second["rows"] == first["rows"]
        d.checks.append("process recreation preserves row and card IDs without duplicates")

        removed = first["programs"][0]["internal_provider_id"]
        inspect("remove-card", removed)
        after_removal = inspect("publish")
        assert len(after_removal["programs"]) == 2 and removed not in {x["internal_provider_id"] for x in after_removal["programs"]}
        assert removed in prefs()["suppressed_ids"]
        d.checks.append("a hard-deleted Home card remains removed after synchronization")
        open_home(); click("Ripristina tessere rimosse")
        d.wait("explicit restore restores the removed card", lambda: int(prefs().get("count", 0)) == 3)

        # Real guide metadata changes, including expiry, are read from disk in a fresh process.
        d.stop()
        guide = json.loads(d.read("files/guide.json"))
        now = int(d.run("shell", "date", "+%s")) * 1000
        for shows in guide.values():
            for show in shows: show["title"] = "Programma aggiornato"
        d.write("files/guide.json", json.dumps(guide).encode())
        updated = inspect("publish")
        assert all(x["short_description"] == "In onda: Programma aggiornato" for x in updated["programs"])
        assert {x["_id"] for x in updated["programs"]} == {x["_id"] for x in inspect()["programs"]}
        d.checks.append("updated guide changes metadata on existing cards")
        d.stop()
        for shows in guide.values():
            for show in shows: show["start"] = now-20000; show["end"] = now-10000
        d.write("files/guide.json", json.dumps(guide).encode())
        expired = inspect("publish")
        assert all(x["short_description"] == "TV in diretta" for x in expired["programs"])
        d.checks.append("expired programmes fall back to the channel description")

        inspect("remove-row")
        missing = inspect("publish")
        assert not missing["rows"] and prefs()["status"] == "REMOVED"
        d.checks.append("automatic updates respect a removed Home row")
        open_home(); click("Mostra nella Home")
        d.wait("explicit Home request recreates the row", lambda: prefs().get("status") in ["HIDDEN", "PUBLISHED"] and int(prefs().get("count",0)) == 3)
        approve()
        open_home()
        d.run("shell", "settings", "put", "system", "font_scale", "1.3")
        open_home(); d.capture("05-home-large-text.png")
        d.key(4)
        d.wait("large text Home settings close with Back", lambda: "Canali da mostrare" not in d.texts())
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        open_home(); click("Disattiva e rimuovi")
        d.wait("revocation clears publication state", lambda: prefs().get("enabled") == "false" and int(prefs().get("count",-1)) == 0)
        revoked = inspect("publish")
        assert not revoked["rows"] and not revoked["programs"]
        d.checks.append("revocation removes real provider rows and queued publication cannot recreate them")
        denied = d.run("exec-out", "content", "read", "--uri", first["programs"][0]["poster_art_uri"], check=False)
        assert not denied.startswith(b"\x89PNG")
        d.checks.append("revocation denies previously published artwork URIs")
        open_home(); d.capture("06-home-revoked.png")
    finally:
        d.stop()
        for name, data in backup.items(): d.write(name, data)
        if not args.phone:
            original = backup.get(home_file)
            if original and b'name="enabled" value="true"' in original:
                root=ET.fromstring(original)
                for child in list(root):
                    if child.get("name") in ["row_id", "create_requested", "published_ids"]: root.remove(child)
                ET.SubElement(root,"boolean",name="create_requested",value="true")
                d.write(home_file,ET.tostring(root))
            inspect("publish")
            d.stop()
        if font in ("null", ""):
            d.run("shell", "settings", "delete", "system", "font_scale")
        else:
            d.run("shell", "settings", "put", "system", "font_scale", font)
        server.shutdown()
        (d.output / "report.json").write_text(json.dumps({"serial":args.serial,"checks":d.checks},indent=2)+"\n")


if __name__ == "__main__": main()
