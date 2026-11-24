#!/usr/bin/env python3
"""Check TV channel switching and remote navigation on a disposable emulator."""
import argparse
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
import time

from smoke_playback import Device, FILES, PACKAGE, ROOT, fixture, is_tv_channel_catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--media-button-dex", type=Path, help="Compiled tools/android/MediaButtonProbe.java; runs the synthetic STOP regression")
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/tv-channel-switch")
    args = parser.parse_args()
    d = Device(args.adb, args.serial, args.output)

    class Handler(SimpleHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/slow.mp4":
                time.sleep(8)
                self.path = "/video.mp4"
            if self.path == "/video.mp4": self.path = "/" + args.video.name
            super().do_GET()

        def log_message(self, *unused): pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    d.stop()
    backup = {p: d.read(p) for p in FILES}

    def focused():
        node = next((n for n in d.nodes() if n.get("focused") == "true"), None)
        return (node.get("content-desc") or node.get("text", "")) if node is not None else ""

    def catalog(): return is_tv_channel_catalog(d.nodes())

    def preview_channel(number):
        description = f"Apri TV Test {number} a schermo intero. In visione"
        return any(node.get("content-desc") == description for node in d.nodes())

    try:
        d.run("logcat", "-c")
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        _, first = fixture(d, server.server_port)
        if args.media_button_dex:
            d.run("push", str(args.media_button_dex), "/data/local/tmp/lume-media-button.dex")
            lines = d.read("files/channels.m3u").decode().splitlines()
            for i, line in enumerate(lines):
                if line.endswith(",TV Test 2"): lines[i + 1] = lines[i + 1].replace("/video.mp4", "/slow.mp4")
            d.write("files/channels.m3u", ("\n".join(lines) + "\n").encode())
        d.launch("channel/" + first)
        d.wait("first TV channel plays full screen", lambda: d.playing("TV Test 1") and not catalog())
        if args.media_button_dex:
            def replace_and_stop(kind):
                d.key(4)
                d.wait("preview before " + kind + " STOP", lambda: preview_channel(1))
                d.wait("first card focused before " + kind + " STOP", lambda: "TV Test 1" in focused())
                d.key(22)
                d.wait("second card focused before " + kind + " STOP", lambda: "TV Test 2" in focused())
                d.key(23)
                d.run("shell", "CLASSPATH=/data/local/tmp/lume-media-button.dex app_process /system/bin MediaButtonProbe " + kind)

            replace_and_stop("synthetic")
            d.wait("untimed synthetic STOP cannot cancel the opening replacement", lambda: d.playing("TV Test 2") and not catalog(), timeout=25)
            d.key(4)
            d.wait("replacement preview survives the synthetic STOP", lambda: preview_channel(2))
            d.run("shell", "CLASSPATH=/data/local/tmp/lume-media-button.dex app_process /system/bin MediaButtonProbe synthetic")
            d.wait("synthetic STOP is accepted after the replacement is ready", lambda: not d.playing() and not preview_channel(2))
            d.launch("channel/" + first)
            d.wait("first channel reopens for a real STOP", lambda: d.playing("TV Test 1") and not catalog())
            replace_and_stop("real")
            d.wait("timestamped remote STOP still cancels an opening replacement", lambda: not d.playing() and catalog() and not preview_channel(2))
            (args.output / "report.json").write_text(json.dumps({"passed": d.checks, "count": len(d.checks)}, ensure_ascii=False, indent=2) + "\n")
            return

        d.run("shell", "input", "keyevent", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_UP")
        d.wait("two quick Up presses switch channels through the brief banner", lambda: d.playing("TV Test 3"))
        d.key("KEYCODE_DPAD_DOWN")
        d.wait("Down switches to the previous channel", lambda: d.playing("TV Test 2"))
        d.key("KEYCODE_DPAD_DOWN")
        d.wait("Down returns to the first channel", lambda: d.playing("TV Test 1"))

        d.key("KEYCODE_DPAD_LEFT")
        d.wait("the live channel list focuses the playing channel", lambda: "TV Test 1" in focused())
        d.key("KEYCODE_DPAD_DOWN")
        d.wait("Down moves the list focus while the channel keeps playing",
               lambda: "TV Test 2" in focused() and d.playing("TV Test 1"))
        d.key("KEYCODE_BACK")

        d.key("KEYCODE_DPAD_CENTER")
        d.wait("OK opens programme information on Details", lambda: focused() == "Dettagli")
        d.key("KEYCODE_DPAD_DOWN")
        d.wait("Down focuses Options in programme information",
               lambda: focused() == "Opzioni riproduzione" and d.playing("TV Test 1"))
        d.key("KEYCODE_DPAD_UP")
        d.wait("Up returns to Details in programme information",
               lambda: focused() == "Dettagli" and d.playing("TV Test 1"))

        d.key("KEYCODE_DPAD_RIGHT")
        d.wait("playback options focus Audio", lambda: focused().startswith("Audio"))
        d.key("KEYCODE_DPAD_DOWN")
        d.wait("Down moves the playback options focus to Subtitles",
               lambda: focused().startswith("Sottotitoli") and d.playing("TV Test 1"))
        d.key("KEYCODE_BACK")

        for number in [2, 3, 4]:
            d.key(4)
            d.wait("Back keeps the current TV preview " + str(number - 1),
                   lambda number=number: d.playing("TV Test " + str(number - 1)) and preview_channel(number - 1))
            d.wait("catalog focuses the current channel " + str(number - 1),
                   lambda number=number: "TV Test " + str(number - 1) in focused())
            d.key(22 if number < 4 else 20)
            if number == 4:
                d.key(21); d.key(21)
            d.wait("remote selects the next card " + str(number), lambda number=number: "TV Test " + str(number) in focused())
            d.key(23)
            d.wait("OK opens the selected channel full screen " + str(number),
                   lambda number=number: d.playing("TV Test " + str(number)) and not catalog())
        d.key(4)
        d.wait("Back restores the latest preview", lambda: preview_channel(4))
        d.key(23)
        d.wait("OK reopens the same current channel", lambda: d.playing("TV Test 4") and not catalog())
        d.key(4)
        d.wait("catalog is ready for a touch selection", catalog)
        d.click("TV Test 1")
        d.wait("selecting a different card also works with touch", lambda: d.playing("TV Test 1") and not catalog())
        d.capture("switched-player.png")
        d.key(4)
        d.wait("final preview survives the switch", lambda: preview_channel(1))
        d.capture("switched-preview.png")
        d.key(3)
        d.wait("leaving the TV app still stops playback", lambda: not d.playing())
        (args.output / "report.json").write_text(json.dumps({"passed": d.checks, "count": len(d.checks)}, ensure_ascii=False, indent=2) + "\n")
    finally:
        (d.output / "logcat.txt").write_bytes(d.run("logcat", "-d", "-s", "LumePlayer:I", "AndroidRuntime:E"))
        d.stop()
        for path, data in backup.items(): d.write(path, data)
        server.shutdown()


if __name__ == "__main__":
    main()
