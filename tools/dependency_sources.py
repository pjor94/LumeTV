#!/usr/bin/env python3
"""Fetch and verify the exact dependency sources distributed with an APK."""
import argparse
import hashlib
import io
import json
import tarfile
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_entries(root):
    lock = json.loads((root / 'third_party/sources.lock.json').read_text())
    if lock.get('schema') != 1:
        raise ValueError('Unsupported dependency source lock format')
    sources = {}
    for artifact in lock['artifacts']:
        source = artifact['source']
        name = source['file']
        if Path(name).name != name or not name.endswith(('.jar', '.zip', '.tar.gz', '.tar')):
            raise ValueError('Unsafe dependency source filename')
        if len(source['sha256']) != 64 or any(c not in '0123456789abcdef' for c in source['sha256']):
            raise ValueError('Invalid dependency source checksum')
        if not source['url'].startswith('https://'):
            raise ValueError('Dependency sources require HTTPS')
        if source.get('normalization') not in (None, 'gitiles-tar-v1'):
            raise ValueError('Unknown dependency archive normalization')
        if name in sources and sources[name] != source:
            raise ValueError('Conflicting dependency source entries')
        sources[name] = source
    return sources


def normalize_gitiles(data):
    """Gitiles stamps downloads with the current time; preserve files, normalize headers."""
    output = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as source, tarfile.open(fileobj=output, mode='w', format=tarfile.PAX_FORMAT) as target:
        members = source.getmembers()
        if len({m.name for m in members}) != len(members):
            raise ValueError('Duplicate source archive paths')
        for member in sorted(members, key=lambda m: m.name):
            path = Path(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir() or member.issym()):
                raise ValueError('Unsafe source archive entry')
            info = tarfile.TarInfo(member.name)
            info.mode = member.mode
            info.type = member.type
            info.linkname = member.linkname
            info.size = member.size if member.isfile() else 0
            target.addfile(info, source.extractfile(member) if member.isfile() else None)
    return output.getvalue()


def verify_report(root, report):
    locked = json.loads((root / 'third_party/sources.lock.json').read_text())['artifacts']
    fields = ('coordinate', 'artifact', 'sha256')
    expected = sorted(tuple(a[k] for k in fields) for a in locked)
    actual = sorted(tuple(a[k] for k in fields) for a in json.loads(report.read_text()))
    if expected != actual:
        raise ValueError('APK dependencies changed: review licenses and update third_party/sources.lock.json')


def verified_sources(root, cache):
    paths = []
    for name, entry in sorted(source_entries(root).items()):
        # Extracted source archives already contain these files; no download is needed.
        bundled = root / 'third_party/sources' / name
        path = bundled if bundled.is_file() else cache / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'Missing dependency source: {name}; run tools/dependency_sources.py fetch')
        if digest(path) != entry['sha256']:
            raise ValueError(f'Dependency source checksum mismatch: {name}')
        paths.append(path)
    return paths


def fetch(root, cache):
    cache.mkdir(parents=True, exist_ok=True)
    entries = source_entries(root)
    def download(item):
        name, entry = item
        target = cache / name
        if target.is_file() and not target.is_symlink() and digest(target) == entry['sha256']:
            return
        with urllib.request.urlopen(entry['url'], timeout=60) as response:
            data = response.read(150 * 1024 * 1024 + 1)
        if len(data) > 150 * 1024 * 1024:
            raise ValueError(f'Download too large: {name}')
        if entry.get('normalization') == 'gitiles-tar-v1':
            data = normalize_gitiles(data)
        if len(data) > 150 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError(f'Download checksum mismatch: {name}')
        temporary = target.with_suffix(target.suffix + '.part')
        temporary.write_bytes(data)
        temporary.replace(target)
    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(download, entries.items()))
    verified_sources(root, cache)
    print(f'Verified {len(entries)} dependency source archives')


def verify_legal(root):
    assets = root / 'app/src/main/assets'
    for original, copy in [('LICENSE', 'LICENSE.txt'), ('THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md')]:
        if (root / original).read_bytes() != (assets / copy).read_bytes():
            raise ValueError(f'Legal notice differs from APK asset: {original}')
    required = ['Apache-2.0.txt', 'GPL-2.0-with-classpath-exception.txt', 'MPL-2.0.txt',
                'BSD-3-Clause-R8.txt', 'XZ-PUBLIC-DOMAIN.txt', 'EPL-1.0.txt',
                'DESUGAR-ADDITIONAL_LICENSE_INFO.txt', 'OPENJDK-ASSEMBLY_EXCEPTION.txt']
    for name in required:
        if not (assets / 'licenses' / name).is_file() or not (assets / 'licenses' / name).stat().st_size:
            raise ValueError(f'Missing license: {name}')
    if '"CLASSPATH" EXCEPTION' not in (assets / 'licenses/GPL-2.0-with-classpath-exception.txt').read_text():
        raise ValueError('Missing Classpath Exception')
    if not (assets / 'DEPENDENCY_NOTICES.txt').is_file():
        raise ValueError('Missing original dependency notices')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['fetch', 'verify'])
    parser.add_argument('--cache-dir', type=Path, default=ROOT / 'build/dependency-sources')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        verify_legal(ROOT)
        if args.report:
            verify_report(ROOT, args.report)
        if args.command == 'fetch':
            fetch(ROOT, args.cache_dir)
        else:
            verified_sources(ROOT, args.cache_dir)
            print('Dependency sources and legal notices verified')
    except (ValueError, OSError) as error:
        parser.exit(1, f'Source verification failed: {error}\n')
