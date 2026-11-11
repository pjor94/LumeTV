#!/usr/bin/env python3
"""Exercise the TV options page with D-pad navigation and local media only."""
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
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/tv-options-smoke")
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
            self.send_response(200); self.send_header("Content-Type", "audio/mpeg"); self.end_headers()
            offset = 0
            try:
                while True:
                    self.wfile.write((audio + audio)[offset:offset + 8192]); self.wfile.flush()
                    offset = (offset + 8192) % len(audio)
                    time.sleep(.35)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    d.stop()
    backup = {p: d.read(p) for p in FILES}
    scale = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()
    d.run("logcat", "-c")

    def page(): return any(n.get("resource-id") == PACKAGE + ":id/tv_options" for n in d.nodes())
    def focus():
        n = next((n for n in d.nodes() if n.get("focused") == "true"), None)
        return (n.get("content-desc") or n.get("text", "")) if n is not None else ""
    def row(label): return focus().startswith(label + ":")
    def choose(label):
        node = next(n for n in d.nodes() if n.get("text", "").casefold() == label.casefold())
        l, t, r, b = map(int, re.findall(r"\d+", node.get("bounds")))
        d.run("shell", "input", "tap", str((l+r)//2), str((t+b)//2))
    def row_title_height():
        # Android 14 scales body text differently from large headings.
        node = next(n for n in d.nodes() if n.get("text") == "Licenze e avvisi")
        _, t, _, b = map(int, re.findall(r"\d+", node.get("bounds")))
        return b-t
    def created():
        lines = d.run("logcat", "-d", "-s", "LumePlayer:I").splitlines()
        return next((l for l in reversed(lines) if b"player_created" in l), b"")

    try:
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        radio, tv = fixture(d, server.server_port)
        d.launch("catalog")
        d.wait("TV catalog ready", lambda: "La TV, fatta semplice." in d.texts())
        d.key(19)
        for _ in range(6):
            if focus() in ["Canali", "Radio", "Cosa guardare", "Guida TV", "Opzioni", "Esci"]: break
            d.key(19)
        for _ in range(8):
            if focus() == "Opzioni": break
            d.key(22)
        d.wait("options is reachable from the remote", lambda: focus() == "Opzioni")
        d.key(23)
        d.wait("options opens as a focused TV page", lambda: page() and row("Interfaccia"))
        d.wait("all TV navigation destinations remain visible in options", lambda: set(
            ["Canali", "Radio", "Cosa guardare", "Guida TV", "Opzioni", "Esci"]).issubset(d.texts()))
        d.key(19); d.wait("Up reaches the top menu from the first setting", lambda: focus() == "Opzioni")
        d.key(20); d.wait("Down returns from the menu to the setting", lambda: row("Interfaccia"))
        d.capture("01-tv-options.png")
        d.key(19); d.key(21); d.key(21)
        d.wait("the shared menu reaches Guide TV with the remote", lambda: focus() == "Guida TV")
        d.key(23)
        d.wait("the menu switches directly from options to Guide TV", lambda: not page() and "Adesso" in d.texts())
        d.click("Opzioni"); d.wait("the shared menu reopens options", lambda: page() and row("Interfaccia"))
        for label in ["CH+/CH−", "Collegamento con AURA", "Aggiornamenti", "Informazioni", "Licenze e avvisi"]:
            d.key(20); d.wait("D-pad reaches " + label, lambda label=label: row(label))
        d.key(4)
        d.wait("Back restores catalog and the options button focus", lambda: not page() and focus() == "Opzioni")
        d.key(23); d.wait("reopening remembers the selected option", lambda: row("Licenze e avvisi"))
        d.key(19); d.key(19); d.key(19); d.key(19)
        d.wait("D-pad returns to zapping preference", lambda: row("CH+/CH−"))
        d.key(23); d.wait("zapping choices open", lambda: "Solo preferiti" in d.texts())
        choose("Solo preferiti")
        d.wait("choice updates the TV page immediately", lambda: page() and "Solo preferiti" in d.texts())
        d.key(20); d.key(23)
        d.wait("AURA consent opens", lambda: any(t.casefold() == "consenti collegamento" for t in d.texts()))
        choose("Consenti collegamento")
        d.wait("AURA status updates on the TV page", lambda: page() and "Attivo" in d.texts())
        d.key(23); d.wait("AURA revocation opens", lambda: any(t.casefold() == "revoca collegamento" for t in d.texts()))
        choose("Revoca collegamento")
        d.wait("AURA revocation is reflected", lambda: page() and "Non attivo" in d.texts())
        d.key(20); d.key(20); d.key(20)
        d.wait("last preference stays focused", lambda: row("Licenze e avvisi"))
        d.key(23)
        d.wait("license documents are accessible", lambda: "Lume TV · GNU GPL v3" in d.texts())
        d.key(4); d.wait("licenses return to their setting", lambda: row("Licenze e avvisi"))
        d.key(19); d.key(23)
        d.wait("app information is accessible", lambda: any(t.startswith("Lume TV · 0.") for t in d.texts()))
        d.key(4); d.wait("information returns to its setting", lambda: row("Informazioni"))
        d.key(19); d.key(23)
        d.wait("manual updates are accessible", lambda: any(t.casefold() == "controlla ora" for t in d.texts()))
        d.key(4); d.wait("updates return to their setting", lambda: row("Aggiornamenti"))
        d.key(20); d.key(20)
        normal = row_title_height()
        d.run("shell", "settings", "put", "system", "font_scale", "1.3")
        d.wait("large text recreates and restores the TV page", lambda: page() and row_title_height() > normal and row("Licenze e avvisi"))
        d.capture("02-tv-options-large-text.png")
        d.key(3); d.wait("TV options reaches the background", lambda: not page())
        pid = d.run("shell", "pidof", PACKAGE, check=False).decode().strip()
        if pid: d.run("shell", "run-as", PACKAGE, "kill", "-9", pid, check=False)
        d.launch()
        d.wait("process recreation preserves the TV options selection", lambda: page() and row("Licenze e avvisi"))
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        d.wait("normal text is restored", lambda: page() and row_title_height() <= normal)
        d.launch("guide"); d.key(10)
        d.wait("guide selects channel three", lambda: any("TV Test 3" in t and "IN ONDA" in t for t in d.texts()))
        d.click("Opzioni"); d.wait("options opens over the guide", page)
        d.key(166); d.key(167); d.key(8); d.key(4)
        d.wait("options does not change the hidden guide selection", lambda: not page() and any("TV Test 3" in t and "IN ONDA" in t for t in d.texts()))
        d.launch("channel/" + tv); d.wait("TV video plays", lambda: d.playing("TV Test 1"))
        marker = created()
        d.key(4); d.click("Opzioni")
        d.wait("video preview continues in TV options", lambda: page() and d.playing("TV Test 1") and "Apri la diretta" in d.texts())
        d.capture("03-tv-options-video.png")
        d.key(22); d.wait("Right reaches the preview controls", lambda: focus() == "Apri la diretta")
        d.key(20); d.wait("Down reaches playback stop", lambda: focus() == "Interrompi")
        d.key(21); d.wait("Left returns to the selected preference", lambda: row("Licenze e avvisi"))
        d.key(127); d.wait("media pause keeps the page open", lambda: page() and d.playing(paused=True))
        d.key(126); d.wait("media play resumes the same TV session", lambda: page() and d.playing("TV Test 1"))
        d.key(22); d.key(23)
        d.wait("preview opens the full player without creating another player", lambda: not page() and d.playing("TV Test 1") and created() == marker)
        d.key(4); d.click("Opzioni")
        d.wait("options restores focus after the full player", lambda: page() and row("Licenze e avvisi"))
        d.key(22); d.wait("preview can be reopened from the settings", lambda: focus() == "Apri la diretta")
        d.key(20); d.wait("playback stop is focused before activation", lambda: focus() == "Interrompi")
        d.key(23)
        d.wait("stopping playback preserves the TV options page", lambda: page() and not d.playing() and row("Licenze e avvisi"))
        d.launch("channel/" + radio); d.wait("TV radio plays", lambda: d.playing("Radio Test Uno"))
        d.key(4); d.click("Opzioni")
        d.wait("radio stays active inside TV options", lambda: page() and d.playing("Radio Test Uno"))
        d.capture("04-tv-options-radio.png")
        d.key(3); d.wait("TV radio stops outside the app", lambda: not d.playing())
        d.launch(); d.wait("returning to options does not restart the radio", lambda: page() and not d.playing())
        errors = d.run("logcat", "-d", "-s", "AndroidRuntime:E").decode(errors="replace")
        d.wait("no Android crash", lambda: "FATAL EXCEPTION" not in errors)
        (args.output / "report.json").write_text(json.dumps({"passed": d.checks, "count": len(d.checks)}, ensure_ascii=False, indent=2) + "\n")
    finally:
        d.stop()
        for path, data in backup.items(): d.write(path, data)
        if scale == "null": d.run("shell", "settings", "delete", "system", "font_scale")
        else: d.run("shell", "settings", "put", "system", "font_scale", scale)
        server.shutdown()


if __name__ == "__main__":
    main()
