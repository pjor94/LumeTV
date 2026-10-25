#!/usr/bin/env python3
"""Validate or refresh the offline catalogue. Playlist data is never executed."""
import argparse
import hashlib
import json
import os
import re
import tempfile
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYLIST = ROOT / 'app/src/main/assets/channels.m3u'
METADATA = ROOT / 'catalog/source.json'
SOURCE = 'https://tvchannels.worldtvlive.eu/tv/_dtt_kodi/list.m3u8'
LIMIT = 2 * 1024 * 1024
SUPPORTED = {'rai', 'rainewsrg', 'mediaset', 'sky', 'la7', 'discovery', 'direct'}

def validate(body: bytes) -> dict:
    if len(body) > LIMIT:
        raise ValueError('Playlist too large')
    text = body.decode('utf-8-sig')
    if not text.lstrip().startswith('#EXTM3U'):
        raise ValueError('Not an M3U playlist')
    counts = Counter()
    pending = False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith('#EXTINF:'):
            if pending:
                raise ValueError('Entry has no source')
            pending = True
        elif line and not line.startswith('#'):
            if not pending:
                raise ValueError('Source has no metadata')
            pending = False
            match = re.match(r'plugin://plugin\.video\.wltvhelper/(?:\??/?)(?:play/)([^/]+)/(.+)', line)
            if match:
                counts[match[1]] += 1
            elif line.startswith(('https://', 'http://')):
                counts['direct'] += 1
            else:
                counts['unsupported'] += 1
    if pending:
        raise ValueError('Truncated playlist')
    if sum(counts[k] for k in SUPPORTED) < 20 or counts['rai'] + counts['rainewsrg'] < 3 or counts['mediaset'] < 3:
        raise ValueError('Incomplete catalogue: Rai and Mediaset must be present')
    return dict(sorted(counts.items()))

def atomic_write(path: Path, body: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix='.catalog-')
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(body)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)

def refresh():
    request = urllib.request.Request(SOURCE, headers={'User-Agent': 'LumeTV-catalog-sync/1.0', 'Accept': 'audio/x-mpegurl,text/plain,*/*'})
    with urllib.request.urlopen(request, timeout=30) as response:
        if not response.url.startswith('https://'):
            raise ValueError('HTTPS required')
        body = response.read(LIMIT + 1)
    counts = validate(body)
    if PLAYLIST.exists() and PLAYLIST.read_bytes() == body and METADATA.exists():
        check()
        print('Catalogue unchanged')
        return
    metadata = {
        'source': SOURCE,
        'retrieved_at': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'sha256': hashlib.sha256(body).hexdigest(),
        'entries': sum(counts.values()),
        'by_provider': counts,
        'purpose': 'Offline first-launch fallback; the installed app refreshes directly from WLTV.',
    }
    # Validate everything before touching either tracked file. A mismatch also fails CI.
    atomic_write(PLAYLIST, body)
    atomic_write(METADATA, (json.dumps(metadata, indent=2, ensure_ascii=False) + '\n').encode())
    print(f'Updated catalogue: {sum(counts.values())} entries')

def check():
    body = PLAYLIST.read_bytes()
    counts = validate(body)
    metadata = json.loads(METADATA.read_text())
    if metadata['source'] != SOURCE or metadata['sha256'] != hashlib.sha256(body).hexdigest() or metadata['by_provider'] != counts:
        raise ValueError('Catalogue metadata does not match playlist')
    print(f'Catalogue valid: {sum(counts.values())} entries')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh', action='store_true', help='Fetch WLTV and replace the bundled fallback if valid')
    parser.add_argument('--check', action='store_true', help='Validate bundled data without accessing the network (default)')
    args = parser.parse_args()
    refresh() if args.refresh else check()
