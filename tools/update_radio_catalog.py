#!/usr/bin/env python3
"""Refresh the offline Italian WorldRadio catalog supplied by WLTV."""
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request
import zlib

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "https://www.worldtvlive.eu/world/files/worldradio/IT.bin"
LIMIT = 4 * 1024 * 1024


def main():
    with urllib.request.urlopen(SOURCE, timeout=30) as response:
        body = response.read(LIMIT + 1)
    if len(body) > LIMIT:
        raise ValueError("Encoded catalog exceeds size limit")
    inflater = zlib.decompressobj(-15)
    decoded = inflater.decompress(base64.b64decode(body, validate=True), LIMIT + 1)
    if len(decoded) > LIMIT or not inflater.eof:
        raise ValueError("Invalid or oversized compressed catalog")
    data = json.loads(decoded)
    entries = [s for c in data["countries"] for p in c["places"] for s in p["channels"] if s.get("enabled", True)]
    if len(entries) < 10:
        raise ValueError("Incomplete radio catalog")
    (ROOT / "app/src/main/assets/radios.json").write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")
    provenance = {"source": SOURCE, "retrieved_at": datetime.now(timezone.utc).isoformat(),
                  "sha256": hashlib.sha256(body).hexdigest(), "entries": len(entries),
                  "purpose": "Offline Italian radio fallback; catalog by WorldLiveTV / WLTV Group."}
    (ROOT / "catalog/radio-source.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n")
    print(f"Saved {len(entries)} Italian radio entries")


if __name__ == "__main__":
    main()
