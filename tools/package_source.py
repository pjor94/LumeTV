#!/usr/bin/env python3
"""Build corresponding source, including verified upstream dependency sources."""
import argparse
import hashlib
import re
import subprocess
import zipfile
from pathlib import Path
from dependency_sources import verified_sources, verify_legal, verify_report

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = {'.git', '.android', '.sdk', '.avd', '.gradle', '.gradle-user', '.signing', 'build', 'dist', '__pycache__', 'analysis'}
SOURCE_DIRS = {'.github', 'app', 'core', 'catalog', 'docs', 'gradle', 'test-fixtures', 'tools', 'third_party'}
SOURCE_SUFFIXES = {'.kt', '.kts', '.py', '.md', '.txt', '.xml', '.json', '.properties', '.pro', '.m3u', '.yml', '.yaml', '.png', '.svg', '.jar'}


def new_source(name):
    path = Path(name)
    return path.parts[0] in SOURCE_DIRS and path.suffix in SOURCE_SUFFIXES and not any(p in FORBIDDEN for p in path.parts)


def project_version(root: Path) -> str:
    text = (root / 'app/build.gradle.kts').read_text()
    return re.search(r'versionName\s*=\s*"([^"]+)"', text)[1]


def build_archive(root: Path, output_dir: Path, *, include_untracked=False, dependency_cache=None) -> Path:
    version = project_version(root)
    if not re.fullmatch(r'[0-9A-Za-z.-]+', version):
        raise ValueError('Invalid versionName')
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    deleted = set(subprocess.check_output(['git', 'ls-files', '--deleted', '-z'], cwd=root).decode().split('\0'))
    tracked = [name for name in tracked if name and name not in deleted]
    if not tracked:
        raise ValueError('No tracked source files. Add the project files to Git first.')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
    pending = [name for name in untracked if name and new_source(name)]
    if pending and not include_untracked:
        raise ValueError('Untracked source files would be omitted; add them to Git or use --include-untracked: ' + ', '.join(pending))
    tracked = sorted(set(tracked + (pending if include_untracked else [])))
    required = {'LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'app/build.gradle.kts', 'third_party/sources.lock.json', 'third_party/README.md'}
    if not required.issubset(tracked):
        raise ValueError('Missing required source or license files')
    verify_legal(root)
    verify_report(root, root / 'app/build/reports/release-dependencies.json')
    sources = verified_sources(root, dependency_cache or root / 'build/dependency-sources')
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f'LumeTV-{version}-source.zip'
    manifest = []
    temporary = output.with_suffix('.zip.part')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
        def write(name, data, mode=0o100644):
            info = zipfile.ZipInfo(f'LumeTV-{version}/{name}', date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = mode << 16
            archive.writestr(info, data)
            manifest.append(hashlib.sha256(data).hexdigest() + '  ' + name + '\n')
        for name in tracked:
            relative = Path(name)
            source = root / relative
            if relative.is_absolute() or '..' in relative.parts or any(part in FORBIDDEN for part in relative.parts):
                raise ValueError(f'Local-only path is tracked: {name}')
            if source.suffix in {'.jks', '.keystore', '.p12', '.apk', '.pyc'} or source.name in {'local.properties', 'keystore.properties'} or (source.name.startswith('.env') and source.name != '.env.example'):
                raise ValueError(f'Private or generated file is tracked: {name}')
            if source.is_symlink() or not source.is_file():
                raise ValueError(f'Expected a regular source file: {name}')
            if name.startswith('third_party/sources/'):
                raise ValueError('Keep dependency downloads out of Git; the lock controls bundled sources')
            write(relative.as_posix(), source.read_bytes(), source.stat().st_mode & 0xFFFF)
        for source in sources:
            write('third_party/sources/' + source.name, source.read_bytes())
        write('SOURCE-MANIFEST.sha256', ''.join(manifest).encode())
    temporary.replace(output)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'dist')
    parser.add_argument('--include-untracked', action='store_true', help='Include new, non-ignored project source/doc files for local preparation')
    parser.add_argument('--dependency-cache', type=Path, default=ROOT / 'build/dependency-sources')
    args = parser.parse_args()
    print(build_archive(ROOT, args.output_dir, include_untracked=args.include_untracked, dependency_cache=args.dependency_cache))
