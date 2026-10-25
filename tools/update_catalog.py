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
RSI_NAME = re.compile(r'^rsi\s*la\s*[12](?![a-z0-9])', re.IGNORECASE)
RSI_ID = re.compile(r'^rsila[12](?:vpn|instabile|hd|it|ch)?$', re.IGNORECASE)
RSI_SOURCE = re.compile(r'(?:^|[/=?&])rsila[12](?:hd)?(?=$|[/.?&#])', re.IGNORECASE)
PURPOSE = 'Offline first-launch fallback, excluding RSI LA1/LA2; the app applies the same exclusions to WLTV updates.'


def excluded_entry(lines: list[str]) -> bool:
    metadata = lines[0].strip()
    attributes = dict(re.findall(r'([\w-]+)="([^"]*)"', metadata))
    name = attributes.get('tvg-name', '')
    quoted = False
    for index, char in enumerate(metadata):
        if char == '"':
            quoted = not quoted
        elif char == ',' and not quoted:
            name = metadata[index + 1:].strip()
            break
    source = next((line.strip().split('|')[0] for line in lines[1:]
                   if line.strip() and not line.strip().startswith('#')), '')
    return bool(re.match(r'plugin://plugin\.video\.wltvhelper/\??/?play/rsi/', source, re.IGNORECASE)
                or RSI_ID.fullmatch(re.sub(r'[^a-zA-Z0-9]', '', attributes.get('tvg-id', '')))
                or RSI_NAME.match(name)
                or RSI_SOURCE.search(source))


def filter_playlist(body: bytes) -> bytes:
    """Drop RSI LA1/LA2 entry blocks using the same identities as the app policy."""
    kept = []
    entry = []

    def flush():
        if entry and not excluded_entry(entry):
            kept.extend(entry)
        entry.clear()

    for line in body.decode('utf-8').splitlines(keepends=True):
        if line.strip().startswith('#EXTINF:'):
            flush()
            entry.append(line)
        elif entry:
            entry.append(line)
        else:
            kept.append(line)
    flush()
    return ''.join(kept).encode('utf-8')

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
    validate(body)
    body = filter_playlist(body)
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
        'purpose': PURPOSE,
    }
    # Validate everything before touching either tracked file. A mismatch also fails CI.
    atomic_write(PLAYLIST, body)
    atomic_write(METADATA, (json.dumps(metadata, indent=2, ensure_ascii=False) + '\n').encode())
    print(f'Updated catalogue: {sum(counts.values())} entries')

def check():
    body = PLAYLIST.read_bytes()
    counts = validate(body)
    if filter_playlist(body) != body:
        raise ValueError('Bundled catalogue contains excluded RSI LA1/LA2 entries')
    metadata = json.loads(METADATA.read_text())
    if metadata['source'] != SOURCE or metadata['sha256'] != hashlib.sha256(body).hexdigest() or metadata['by_provider'] != counts or metadata['entries'] != sum(counts.values()):
        raise ValueError('Catalogue metadata does not match playlist')
    print(f'Catalogue valid: {sum(counts.values())} entries')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh', action='store_true', help='Fetch WLTV and replace the bundled fallback if valid')
    parser.add_argument('--check', action='store_true', help='Validate bundled data without accessing the network (default)')
    args = parser.parse_args()
    refresh() if args.refresh else check()
