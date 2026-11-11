#!/usr/bin/env python3
"""Check the phone options page, navigation and shared player on a disposable emulator."""
import argparse
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from threading import Thread
import time

from smoke_playback import Device, FILES, PACKAGE, ROOT, fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/options-smoke")
    args = parser.parse_args()
    d = Device(args.adb, args.serial, args.output)
    audio = args.audio.read_bytes()

    class Handler(SimpleHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/video.mp4":
                self.path = "/" + args.video.name
                return super().do_GET()
            if self.path not in ("/radio1.mp3", "/radio2.mp3"):
                return self.send_error(404)
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.end_headers()
            offset = 0
            try:
                while True:
                    chunk = (audio + audio)[offset:offset + 8192]
                    offset = (offset + 8192) % len(audio)
                    self.wfile.write(chunk); self.wfile.flush()
                    time.sleep(.35)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    d.stop()
    backup = {p: d.read(p) for p in FILES}
    scale = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()
    rotation = d.run("shell", "wm", "user-rotation").decode().strip().split()
    d.run("logcat", "-c")

    def page():
        return any(n.get("content-desc") == "Chiudi opzioni" for n in d.nodes())

    def scroll_down():
        _, _, right, bottom = map(int, re.findall(r"\d+", d.nodes()[0].get("bounds")))
        d.run("shell", "input", "swipe", str(right // 2), str(int(bottom * .8)), str(right // 2), str(int(bottom * .35)), "300")

    def click(label):
        for _ in range(5):
            nodes = d.nodes()
            candidate = next((n for n in nodes if n.get("text", "").casefold() == label.casefold() and
                              len(re.findall(r"\d+", n.get("bounds", ""))) == 4 and
                              int(re.findall(r"\d+", n.get("bounds"))[3]) > 0), None)
            if candidate is not None:
                left, top, right, bottom = map(int, re.findall(r"\d+", candidate.get("bounds")))
                d.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                return
            scroll_down()
        d.capture("missing-control.png")
        raise AssertionError("Missing options control: " + label)

    def header_height():
        node = next(n for n in d.nodes() if n.get("text") == "Opzioni")
        _, top, _, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        return bottom - top

    def recreate_process():
        d.key(3)
        # Let Android finish saving the stopped activity before simulating process death.
        d.wait("options task reaches the background", lambda: not page())
        process = d.run("shell", "pidof", PACKAGE, check=False).decode().strip()
        if process: d.run("shell", "run-as", PACKAGE, "kill", "-9", process, check=False)
        d.launch()

    def license_position():
        node = next(n for n in d.nodes() if n.get("text") == "Licenze e avvisi")
        return int(re.findall(r"\d+", node.get("bounds"))[1])

    try:
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        radio, tv = fixture(d, server.server_port)
        d.launch("catalog"); d.run("shell", "wm", "user-rotation", "lock", "0")
        d.wait("phone catalog ready", lambda: any(n.get("content-desc") == "Radio" for n in d.nodes()))
        d.click("Radio", description=True)
        d.click("Cerca una radio o una città", description=True); d.run("shell", "input", "text", "Roma")
        d.click("Opzioni", description=True)
        d.wait("options opens as a full page", lambda: page() and "Preferenze" in d.texts())
        d.wait("current settings are visible", lambda: "Tutte le emittenti" in d.texts() and "Non attivo" in d.texts())
        d.capture("01-options.png")
        d.key(4)
        d.wait("system Back preserves radio section and search", lambda: "1 radio" in d.texts() and "Roma" in d.texts())
        d.click("Opzioni", description=True); d.click("Chiudi opzioni", description=True)
        d.wait("toolbar Back returns to the same catalog", lambda: "1 radio" in d.texts())
        d.click("Opzioni", description=True)
        click("Qualità e consumo dati"); click("Risparmio dati")
        d.wait("quality selection updates the page immediately", lambda: page() and "Risparmio dati" in d.texts())
        d.wait("quality preference is saved", lambda: b'data_saver" value="true"' in d.read("shared_prefs/lume.xml"))
        click("Cambio canale"); click("Solo preferiti")
        d.wait("zapping preference updates the page", lambda: page() and "Solo preferiti" in d.texts())
        click("Interfaccia"); click("Automatica")
        d.wait("interface choice returns to options", page)
        click("Collegamento con AURA"); click("Consenti collegamento")
        d.wait("AURA consent updates the page", lambda: page() and "Attivo" in d.texts())
        click("Collegamento con AURA"); click("Revoca collegamento")
        d.wait("AURA revocation updates the page", lambda: page() and "Non attivo" in d.texts())
        click("Aggiornamenti")
        d.wait("existing update controls remain available", lambda: any(t.casefold() == "controlla ora" for t in d.texts()))
        d.key(4); d.wait("Back from updates preserves options", page)
        click("Informazioni")
        d.wait("app information remains available", lambda: any("Software libero" in t for t in d.texts()))
        d.key(4); click("Licenze e avvisi"); click("Crediti e sorgenti")
        d.wait("license documents remain readable", lambda: "Crediti e sorgenti" in d.texts())
        d.key(4); d.wait("Back from license documents preserves options", page)
        d.click("Chiudi opzioni", description=True); d.click("Opzioni", description=True)
        normal_height = header_height()
        d.run("shell", "settings", "put", "system", "font_scale", "1.3")
        d.wait("font recreation preserves options with enlarged text", lambda: page() and header_height() > normal_height)
        d.capture("02-options-large-text.png")
        click("Licenze e avvisi")
        d.wait("license list opens at 130 percent text", lambda: "Apache License 2.0" in d.texts())
        d.key(4)
        d.wait("bottom options remain reachable at 130 percent text", page)
        scroll_down()
        d.capture("03-options-large-text-bottom.png")
        position = license_position()
        recreate_process()
        d.wait("process recreation restores options at 130 percent text", page)
        d.wait("scroll position survives process recreation", lambda: abs(license_position() - position) <= 2)
        d.click("Chiudi opzioni", description=True); d.click("Opzioni", description=True)
        d.run("shell", "wm", "user-rotation", "lock", "1")
        d.wait("options adapts to landscape", lambda: int(re.findall(r"\d+", d.nodes()[0].get("bounds"))[2]) >
               int(re.findall(r"\d+", d.nodes()[0].get("bounds"))[3]))
        d.capture("04-options-landscape.png")
        d.run("shell", "wm", "user-rotation", "lock", "0")
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        d.launch("channel/" + radio); d.wait("radio starts", lambda: d.playing("Radio Test Uno"))
        click("Torna al catalogo"); d.click("Opzioni", description=True)
        d.wait("radio keeps playing with its mini player in options", lambda: page() and d.playing("Radio Test Uno") and
               any(n.get("content-desc") == "Interrompi la riproduzione" for n in d.nodes()))
        d.capture("05-options-radio.png")
        click("Radio Test Uno")
        d.wait("mini player reopens radio from options", lambda: "Torna al catalogo" in d.texts() and not page() and d.playing())
        click("Torna al catalogo"); d.click("Opzioni", description=True)
        d.click("Interrompi la riproduzione", description=True)
        d.wait("stopping from options keeps the page open", lambda: page() and not d.playing())
        recreate_process()
        d.wait("process recreation restores the options page without playback", lambda: page() and not d.playing())
        d.wait("selected settings survive process recreation", lambda: "Risparmio dati" in d.texts() and "Solo preferiti" in d.texts())
        d.launch("channel/" + tv); d.wait("TV starts from a link over options", lambda: d.playing("TV Test 1") and not page())
        d.key(4); d.click("Opzioni", description=True)
        d.wait("TV preview keeps playing inside options", lambda: page() and d.playing("TV Test 1") and
               any(n.get("content-desc") == "Apri la diretta" for n in d.nodes()))
        d.capture("06-options-tv.png")
        d.click("Apri la diretta", description=True)
        d.wait("TV preview returns to the full player", lambda: not page() and d.playing("TV Test 1"))
        d.key(4); d.click("Opzioni", description=True)
        d.run("shell", "cmd", "media_session", "dispatch", "stop")
        d.wait("system stop keeps options open", lambda: page() and not d.playing())
        d.launch("favorites")
        d.wait("catalog links dismiss options", lambda: not page() and any(n.get("content-desc") == "Preferiti" and n.get("selected") == "true" for n in d.nodes()))
        d.launch("integration")
        d.wait("integration consent opens from a link", lambda: any(t.casefold() == "consenti collegamento" for t in d.texts()))
        d.key(4)
        d.wait("integration link returns to its options page", page)
        errors = d.run("logcat", "-d", "-s", "AndroidRuntime:E").decode(errors="replace")
        d.wait("no Android crash", lambda: "FATAL EXCEPTION" not in errors)
        (args.output / "report.json").write_text(json.dumps({"passed": d.checks, "count": len(d.checks)}, ensure_ascii=False, indent=2) + "\n")
    finally:
        d.stop()
        for p, data in backup.items(): d.write(p, data)
        if scale == "null": d.run("shell", "settings", "delete", "system", "font_scale")
        else: d.run("shell", "settings", "put", "system", "font_scale", scale)
        if rotation and rotation[0] in ("free", "lock"): d.run("shell", "wm", "user-rotation", *rotation)
        server.shutdown()


if __name__ == "__main__":
    main()
