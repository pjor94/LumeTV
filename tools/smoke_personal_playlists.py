#!/usr/bin/env python3
"""Personal M3U checks on disposable emulators, with local media and restored app data."""
import argparse
import functools
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import sqlite3
from threading import Thread
import time

from smoke_playback import Device, FILES, PACKAGE, ROOT, fixture
from smoke_playlist_compatibility import playlist_actions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mode", choices=["phone", "tv"], required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--file-only", action="store_true", help="Exercise the system document picker without the URL scenario")
    parser.add_argument("--preview-only", action="store_true", help="Capture URL editor, validation and preview without importing")
    parser.add_argument("--capture-management", action="store_true", help="Capture stable editor and reenabled source states")
    parser.add_argument("--delete-only", action="store_true", help="Verify the post-deletion focus fallback")
    parser.add_argument("--output", type=Path, default=ROOT / "analysis/personal-playlists")
    args = parser.parse_args()
    d = Device(args.adb, args.serial, args.output)
    state = {"renamed": False, "broken": False, "initial_invalid": True, "requests": []}

    class Handler(SimpleHTTPRequestHandler):
        def do_GET(self):
            state["requests"].append(self.path)
            if self.path == "/personal.m3u":
                if state["initial_invalid"]:
                    body = b"<html>Invalid playlist fixture</html>"
                elif state["broken"]:
                    body = b"#EXTM3U\n#EXTINF:-1,Truncated"
                else:
                    title = "Personale Uno Nuovo" if state["renamed"] else "Personale Uno"
                    path = "/renewed.mp4" if state["renamed"] else "/video.mp4"
                    body = (f'#EXTM3U\n#EXTINF:-1 tvg-id="personal-one" tvg-chno="5" group-title="Sport",{title}\n'
                            f'http://10.0.2.2:{self.server.server_port}{path}\n'
                            f'#EXTINF:-1 tvg-id="personal-two" tvg-chno="5" group-title="Cinema",Personale Due\n'
                            f'http://10.0.2.2:{self.server.server_port}/video.mp4\n'
                            '#EXTINF:-1 tvg-id="personal-radio" radio="true" group-title="Radio",Radio Personale\n'
                            f'http://10.0.2.2:{self.server.server_port}/video.mp4\n'
                            '#EXTINF:-1,Non supportato\nudp://239.1.1.1:1234\n').encode()
                self.send_response(200); self.send_header("Content-Type", "audio/x-mpegurl")
                self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
            if self.path in ["/video.mp4", "/renewed.mp4"]:
                self.path = "/" + args.video.name
            super().do_GET()

        def log_message(self, *unused):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=str(args.video.resolve().parent)))
    Thread(target=server.serve_forever, daemon=True).start()
    d.stop()
    backup = {p: d.read(p) for p in FILES}
    index_path = "files/personal-playlists/index.json"
    original_index = d.read(index_path)
    original_font = d.run("shell", "settings", "get", "system", "font_scale").decode().strip()
    personal_backup = {}
    if original_index:
        for source in json.loads(original_index)["ids"]:
            if not re.fullmatch(r"user_[a-f0-9]{32}", source):
                raise ValueError("Invalid existing source ID")
            for suffix in [".json", ".sqlite"]:
                path = "files/personal-playlists/" + source + suffix
                personal_backup[path] = d.read(path)

    def click_contains(label, description=False):
        attr = "content-desc" if description else "text"
        nodes = playlist_actions(d.nodes(),label,description,contains=True)
        if not nodes:
            d.capture("missing-" + str(len(d.checks)) + ".png")
            raise AssertionError("Missing control containing: " + label)
        node = nodes[-1]
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
        d.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def click(label, description=False):
        attr = "content-desc" if description else "text"
        nodes = playlist_actions(d.nodes(),label,description)
        if not nodes:
            d.capture("missing-control.png")
            raise AssertionError("Missing control: " + label)
        left, top, right, bottom = map(int, re.findall(r"\d+", nodes[-1].get("bounds")))
        d.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def manager():
        click("Opzioni", description=args.mode == "phone")
        d.wait("options page", lambda: any("Le mie liste:" in n.get("content-desc", "") for n in d.nodes()))
        click_contains("Le mie liste:", True)
        d.wait("personal sources page", lambda: "Aggiungi URL" in d.texts())

    def open_source():
        if any(t.startswith("Stato e ultima importazione") for t in d.texts()): return
        click("Lista Test")

    def close_to_overview():
        # Errors and reports now belong to pages rather than dismissible windows.
        if not any(t.startswith("Stato e ultima importazione") for t in d.texts()): click("Indietro")
        if any(t.startswith("Stato e ultima importazione") for t in d.texts()): click("Indietro")

    def set_enabled(value):
        open_source(); click("Sorgente")
        node = next(n for n in d.nodes() if n.get("text")=="Lista attiva")
        if (node.get("checked")=="true") != value: click("Lista attiva")
        click("Salva modifiche")
        d.wait("source settings saved", lambda:any(t.startswith("Stato e ultima importazione") for t in d.texts()))
        click("Indietro")

    def record():
        raw = d.read(index_path)
        if not raw: return None
        ids = json.loads(raw)["ids"]
        if not ids: return None
        raw = d.read("files/personal-playlists/" + ids[-1] + ".sqlite")
        if raw:
            path = args.output / args.serial / "inspection.sqlite"
            path.write_bytes(raw)
            with sqlite3.connect(path) as connection:
                result = json.loads(connection.execute("SELECT value FROM metadata WHERE key='source'").fetchone()[0])
                result["records"] = [dict(json.loads(payload), id=key, active=bool(active), seen=seen)
                                     for key, payload, active, seen in connection.execute("SELECT id,payload,active,seen FROM records ORDER BY position")]
                return result
        raw = d.read("files/personal-playlists/" + ids[-1] + ".json")
        return json.loads(raw) if raw else None

    def focused():
        node = next((n for n in d.nodes() if n.get("focused") == "true"), None)
        return (node.get("content-desc") or node.get("text", "")) if node is not None else ""

    def title_height():
        node = next(n for n in d.nodes() if n.get("text") == "Le mie liste")
        bounds = list(map(int, re.findall(r"\d+", node.get("bounds"))))
        return bounds[3] - bounds[1]

    def file_import():
        sample = args.output / args.serial / "lume-personal.m3u"
        sample.write_text('#EXTM3U\n#EXTINF:-1 tvg-id="file-one",Canale da file\n'
                          f'http://10.0.2.2:{server.server_port}/video.mp4\n')
        d.run("shell", "mkdir", "-p", "/sdcard/Download")
        d.run("push", str(sample), "/sdcard/Download/lume-personal.m3u")
        click("Importa file")
        if args.mode == "tv":
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if any("selettore" in t.casefold() and ("non disponibile" in t.casefold() or "non offre" in t.casefold()) for t in d.texts()):
                    d.checks.append("TV without a document provider offers the URL fallback")
                    d.capture("08-file-picker-unavailable.png"); return
                if any("documentsui" in n.get("package", "") for n in d.nodes()): break
        d.wait("system document picker opens", lambda: any("documentsui" in n.get("package", "") for n in d.nodes()))
        d.capture("08-file-picker.png")
        nodes = d.nodes()
        if not any("lume-personal.m3u" in n.get("text", "") for n in nodes):
            roots = next((n for n in nodes if n.get("content-desc") in ["Show roots", "Mostra radici", "Open navigation drawer"]), None)
            if roots is not None:
                click(roots.get("content-desc"), True)
            if "Downloads" in d.texts(): click("Downloads")
            elif "Download" in d.texts(): click("Download")
        # Landscape / large text can place a grid tile's filename below the viewport.
        # The document provider's list view exposes the complete selectable file row.
        if any(n.get("content-desc") == "List view" for n in d.nodes()): click("List view", True)
        d.wait("M3U file is selectable", lambda: any("lume-personal.m3u" in t for t in d.texts()))
        click("lume-personal.m3u")
        d.wait("selected document returns to import editor", lambda: "Verifica lista".casefold() in [t.casefold() for t in d.texts()])
        consent = next(n for n in d.nodes() if n.get("text") == "Consenti HTTP per questa lista")
        if consent.get("checked") != "true": click("Consenti HTTP per questa lista")
        click("Verifica lista")
        d.wait("file preview contains the channel", lambda: any("1 voci importabili" in t or "1 voce importabile" in t for t in d.texts()) and "Canale da file" in d.texts())
        d.capture("09-file-preview.png")
        click("Importa lista"); d.wait("source details ready", lambda:any(t.startswith("Stato e ultima importazione") for t in d.texts())); click("Indietro")
        d.wait("file source is copied into private storage", lambda: record() is not None and record()["location"] == "FILE")
        d.run("shell", "rm", "/sdcard/Download/lume-personal.m3u")
        d.stop(); d.launch("catalog")
        d.wait("file source survives deletion of the original", lambda: record() is not None and record()["records"][0]["name"] == "Canale da file")

    try:
        d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files", "shared_prefs")
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists")
        fixture(d, server.server_port)
        d.launch("catalog")
        d.wait("catalog loads", lambda: "Canali" in d.texts())
        manager(); d.capture("01-empty.png")
        if args.file_only:
            file_import()
            (args.output / args.serial / "file-report.json").write_text(json.dumps({"passed": d.checks, "count": len(d.checks)}, ensure_ascii=False, indent=2) + "\n")
            return
        if args.mode == "tv":
            d.wait("remote focus enters the personal sources page", lambda: focused() == "Aggiungi URL")
            d.key(23)
        else: click("Aggiungi URL")
        click("Nome della lista", description=True); d.run("shell", "input", "text", "Lista%sTest")
        click("Indirizzo HTTP o HTTPS della lista", description=True)
        d.run("shell", "input", "text", f"http://10.0.2.2:{server.server_port}/personal.m3u")
        d.key(4)
        d.wait("editor is visible after dismissing the keyboard", lambda: any(t.casefold() == "verifica lista" for t in d.texts()))
        click("Consenti HTTP per questa lista")
        d.capture("02-editor.png")
        click("Verifica lista")
        d.wait("invalid response stays in the editor without saving a source", lambda: any("#EXTM3U" in t for t in d.texts()) and record() is None)
        d.capture("02-editor-error.png")
        state["initial_invalid"] = False
        click("Verifica lista")
        d.wait("preview counts importable and excluded entries", lambda: any("3 voci importabili" in t and "1 esclusa" in t for t in d.texts()))
        d.capture("03-preview.png")
        if args.preview_only: return
        click("Importa lista"); d.wait("source details ready", lambda:any(t.startswith("Stato e ultima importazione") for t in d.texts())); click("Indietro")
        d.wait("source saved", lambda: record() is not None and any("Lista Test" in t for t in d.texts()))
        saved = record()
        ids = {r["tvgId"]: r["id"] for r in saved["records"] if r["active"]}
        d.capture("04-sources.png")
        if args.delete_only:
            open_source(); click("Elimina lista"); click("Elimina definitivamente")
            d.wait("post-deletion empty page is settled", lambda: record() is None and "Nessuna lista personale" in d.texts())
            if args.mode == "tv": d.wait("post-deletion remote focus returns to Add URL", lambda: focused() == "Aggiungi URL")
            d.capture("07-deleted.png")
            (args.output / args.serial / "capture-focus.json").write_text(json.dumps({"postDeleteFocus": focused(), "checks": d.checks}, ensure_ascii=False, indent=2) + "\n")
            return
        if args.capture_management:
            set_enabled(False)
            d.wait("disabled source is visible in management", lambda: not record()["enabled"] and "Aggiungi URL" in d.texts())
            set_enabled(True)
            d.wait("reenabled source is visible without a dialog", lambda: record()["enabled"] and "Aggiungi URL" in d.texts())
            d.capture("06-restored.png")
            state["broken"] = True
            open_source(); click("Aggiorna lista")
            d.wait("invalid refresh error is visible", lambda: any("lista è incompleta" in t for t in d.texts()))
            d.capture("06-refresh-error.png"); close_to_overview()
            open_source(); click("Stato e ultima importazione")
            d.wait("retry state is visible", lambda: any("Prossimo tentativo:" in t for t in d.texts()))
            d.capture("06-refresh-state.png"); close_to_overview()
            normal_height = title_height()
            d.run("shell", "settings", "put", "system", "font_scale", "1.3")
            d.wait("management page uses larger text", lambda: "Aggiungi URL" in d.texts() and title_height() > normal_height)
            if args.mode == "tv": d.wait("visible source retains remote focus", lambda: focused().startswith("Lista Test,"))
            d.capture("06-large-text.png")
            d.run("shell", "settings", "put", "system", "font_scale", "1.0")
            d.wait("normal text has returned", lambda: "Aggiungi URL" in d.texts() and title_height() == normal_height)
            open_source(); click("Elimina lista"); click("Elimina definitivamente")
            d.wait("post-deletion empty page is settled", lambda: record() is None and "Nessuna lista personale" in d.texts())
            if args.mode == "tv": d.wait("post-deletion remote focus returns to Add URL", lambda: focused() == "Aggiungi URL")
            d.capture("07-deleted.png")
            (args.output / args.serial / "capture-focus.json").write_text(json.dumps({"postDeleteFocus": focused(), "checks": d.checks}, ensure_ascii=False, indent=2) + "\n")
            return
        click("Indietro"); d.key(4)
        d.wait("source selector appears", lambda: "Lista: Catalogo Lume" in d.texts())
        click("Lista: Catalogo Lume"); click("Lista Test")
        d.wait("personal TV catalog is isolated", lambda: any("Personale Uno" in t for t in d.texts()) and not any("TV Test 1" in t or "Radio Personale" in t for t in d.texts()))
        d.capture("05-catalog.png")
        click("Tutti i gruppi"); click("Sport")
        d.wait("group filter is isolated from navigation labels", lambda: any("Personale Uno" in t for t in d.texts()) and not any("Personale Due" in t for t in d.texts()))
        click("Sport"); click("Tutti i gruppi")
        d.wait("clearing the group restores both personal channels", lambda: any("Personale Uno" in t for t in d.texts()) and any("Personale Due" in t for t in d.texts()))
        if args.mode == "tv":
            d.key(12)
            d.wait("duplicate channel numbers require a choice", lambda: "Scegli il canale 5" in d.texts())
            d.capture("05-duplicate-number.png"); click("Annulla")
        else:
            click("Aggiungi Personale Uno ai preferiti", True)
            d.wait("personal favorite saved", lambda: ids["personal-one"].encode() in d.read("shared_prefs/lume.xml"))
        d.launch("channel/" + ids["personal-one"])
        d.wait("personal stream plays", lambda: d.playing("Personale Uno"))
        d.key(87); d.wait("screen zapping stays in the personal TV source", lambda: d.playing("Personale Due"))
        d.run("shell", "cmd", "media_session", "dispatch", "next")
        d.wait("service zapping wraps within the same source", lambda: d.playing("Personale Uno"))
        d.key(4)
        manager()
        count = len([r for r in state["requests"] if r.endswith(".mp4")])
        state["renamed"] = True
        open_source(); click("Aggiorna lista")
        d.wait("refresh preserves IDs and updates names", lambda: record()["records"][0]["name"] == "Personale Uno Nuovo")
        if args.mode == "phone": assert ids["personal-one"].encode() in d.read("shared_prefs/lume.xml")
        assert {r["tvgId"]: r["id"] for r in record()["records"] if r["active"]} == ids
        d.wait("metadata updates without stopping playback", lambda: d.playing("Personale Uno Nuovo"))
        assert len([r for r in state["requests"] if r.endswith(".mp4")]) == count
        set_enabled(False)
        d.wait("disabling stops its current player", lambda: not d.playing() and not record()["enabled"])
        set_enabled(True)
        d.wait("reenabling restores the same channels", lambda: record()["enabled"] and "Aggiungi URL" in d.texts())
        d.capture("06-restored.png")
        time.sleep(max(0, (record()["attempted"] + 31_000 - int(time.time() * 1000)) / 1000))
        state["broken"] = True
        open_source(); click("Aggiorna lista")
        d.wait("invalid refresh retains the saved channels", lambda: bool(record()["error"]) and record()["records"][0]["name"] == "Personale Uno Nuovo")
        d.wait("failed refresh explains the invalid document", lambda: any("lista è incompleta" in t for t in d.texts()))
        d.capture("06-refresh-error.png"); close_to_overview()
        open_source(); click("Stato e ultima importazione")
        d.wait("source error explains the next retry", lambda: any("Prossimo tentativo:" in t for t in d.texts()))
        d.capture("06-refresh-state.png"); close_to_overview()
        if args.mode == "tv": d.wait("returning to the overview restores focus to the visible source", lambda: focused().startswith("Lista Test,"))
        normal_height = title_height()
        d.run("shell", "settings", "put", "system", "font_scale", "1.3")
        d.wait("sources page survives larger text", lambda: "Aggiungi URL" in d.texts() and title_height() > normal_height)
        if args.mode == "tv": d.wait("large text recreation preserves focus on the sources page", lambda: focused().startswith("Lista Test,"))
        d.capture("06-large-text.png")
        d.run("shell", "settings", "put", "system", "font_scale", "1.0")
        d.stop(); d.launch("catalog")
        d.wait("source survives process recreation", lambda: record() is not None and record()["enabled"] and
               any(t in ["Lista: Catalogo Lume", "Aggiungi URL"] for t in d.texts()))
        if "Aggiungi URL" not in d.texts(): manager()
        open_source(); click("Elimina lista"); click("Elimina definitivamente")
        d.wait("deletion removes the source", lambda: record() is None and "Nessuna lista personale" in d.texts())
        d.capture("07-deleted.png")
        (args.output / args.serial / "report.json").write_text(json.dumps({"passed": d.checks, "count": len(d.checks)}, ensure_ascii=False, indent=2) + "\n")
    finally:
        d.stop()
        d.run("shell", "rm", "-f", "/sdcard/Download/lume-personal.m3u")
        if original_font and original_font != "null": d.run("shell", "settings", "put", "system", "font_scale", original_font)
        else: d.run("shell", "settings", "delete", "system", "font_scale")
        d.run("shell", "run-as", PACKAGE, "rm", "-rf", "files/personal-playlists")
        if original_index:
            d.run("shell", "run-as", PACKAGE, "mkdir", "-p", "files/personal-playlists")
            d.write(index_path, original_index)
            for path, content in personal_backup.items(): d.write(path, content)
        for path, content in backup.items(): d.write(path, content)
        server.shutdown()


if __name__ == "__main__":
    main()
