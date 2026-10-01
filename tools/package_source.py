"""Create a reviewable source archive without build output, credentials or personal captures."""
from pathlib import Path
import argparse
import hashlib
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('output', type=Path)
args = parser.parse_args()
allowed_roots = {'app', 'third_party', 'tools', '.github'}
allowed_files = {'README.md', 'PRIVACY.md', 'CONTRIBUTING.md', 'OPEN_SOURCE.md', 'RELEASE_NOTES.md',
                 'LICENSE', 'NOTICE', 'MAINTAINERS.md', '.gitignore', '.gitattributes', 'build.ps1', 'build.gradle', 'settings.gradle', 'version.properties'}
excluded_parts = {'.git', '.build', '.gradle', 'build', 'private', 'captures', 'screenshots', 'diagnostics', '__pycache__'}
excluded_suffixes = {'.keystore', '.jks', '.p12', '.pfx', '.log', '.pyc', '.apk', '.idsig'}
files = []
for path in root.rglob('*'):
    if not path.is_file():
        continue
    relative = path.relative_to(root)
    if relative.parts[0] not in allowed_roots and relative.as_posix() not in allowed_files:
        continue
    if set(relative.parts) & excluded_parts or path.suffix.lower() in excluded_suffixes:
        continue
    if path.name.startswith(('.env', 'Screenshot_', 'codex-clipboard-')) or path.name == 'local.properties':
        continue
    files.append(path)
args.output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(files):
        archive.write(path, 'SplashSkip/' + path.relative_to(root).as_posix())
with zipfile.ZipFile(args.output) as archive:
    assert not any(set(Path(name).parts) & excluded_parts or Path(name).suffix.lower() in excluded_suffixes for name in archive.namelist())
    assert 'SplashSkip/version.properties' in archive.namelist()
    assert 'SplashSkip/LICENSE' in archive.namelist()
digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
args.output.with_suffix(args.output.suffix + '.sha256').write_text(digest + '  ' + args.output.name + '\n', encoding='utf-8')
print(f'Source archive: {len(files)} files, {args.output.stat().st_size:,} bytes; excluded keys, captures and build output.')
