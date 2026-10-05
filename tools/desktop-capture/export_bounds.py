"""Read saved automatic bounds over authorized ADB, without launching apps or taking screenshots."""
from pathlib import Path
import argparse
import json
import re
import subprocess
from capture_adb import decode_reply


def read_bounds(run, package):
    frames, seen, offset = [], set(), 0
    while offset >= 0:
        if offset in seen or len(seen) > 64:
            raise RuntimeError('Invalid bounds pagination')
        seen.add(offset)
        page = decode_reply(run('shell', 'content', 'call', '--uri',
            'content://com.codex.splashskip.capture', '--method', 'bounds_records',
            '--arg', package, '--extra', 'offset:i:' + str(offset)))
        frames.extend(page['frames'])
        offset = page.get('next_offset', -1)
    # Frames can rotate while a multi-page read is in progress; keep each ID once.
    unique = {frame['id']: frame for frame in frames}
    return dict(schema=1, package=package, screen_capture=False,
                frames=list(unique.values()), pages=len(seen))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z][\w]*(?:\.[\w]+)+', args.package):
        parser.error('Invalid package')
    command = [str(args.adb.resolve()), '-s', args.serial]
    def run(*items):
        return subprocess.run(command + list(items), capture_output=True,
                              check=True, timeout=15).stdout
    result = read_bounds(run, args.package)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
    print(f"Saved {len(result['frames'])} frames; screenshots=0; {args.output.resolve()}")


if __name__ == '__main__':
    main()
