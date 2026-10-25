#!/usr/bin/env python3
"""Build a source archive from version-controlled files."""
import argparse
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = {'.git', '.android', '.sdk', '.avd', '.gradle', '.gradle-user', '.signing', 'build', 'dist', '__pycache__', 'analysis'}


def project_version(root: Path) -> str:
    text = (root / 'app/build.gradle.kts').read_text()
    return re.search(r'versionName\s*=\s*"([^"]+)"', text)[1]


def build_archive(root: Path, output_dir: Path) -> Path:
    version = project_version(root)
    if not re.fullmatch(r'[0-9A-Za-z.-]+', version):
        raise ValueError('Invalid versionName')
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    tracked = [name for name in tracked if name]
    if not tracked:
        raise ValueError('No tracked source files. Add the project files to Git first.')
    required = {'LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'app/build.gradle.kts'}
    if not required.issubset(tracked):
        raise ValueError('The Git index is missing required source or license files')
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / f'LumeTV-{version}-source.zip'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(tracked):
            relative = Path(name)
            source = root / relative
            if relative.is_absolute() or '..' in relative.parts or any(part in FORBIDDEN for part in relative.parts):
                raise ValueError(f'Local-only path is tracked: {name}')
            if source.suffix in {'.jks', '.keystore', '.p12', '.apk', '.pyc'} or source.name in {'local.properties', 'keystore.properties'} or (source.name.startswith('.env') and source.name != '.env.example'):
                raise ValueError(f'Private or generated file is tracked: {name}')
            if source.is_symlink() or not source.is_file():
                raise ValueError(f'Expected a regular source file: {name}')
            info = zipfile.ZipInfo(f'LumeTV-{version}/{relative.as_posix()}', date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (source.stat().st_mode & 0xFFFF) << 16
            archive.writestr(info, source.read_bytes())
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    print(build_archive(ROOT, args.output_dir))
