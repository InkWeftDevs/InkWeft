#!/usr/bin/env python3
"""Run one verification command and keep timestamped, sanitized evidence."""
import datetime
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

ROOT = Path(__file__).resolve().parent

def sanitize(text):
    def clean(match):
        raw = match.group(0)
        try:
            u = urlsplit(raw)
            host = u.netloc.rsplit('@', 1)[-1]
            return urlunsplit((u.scheme, host, u.path, '[REDACTED]' if u.query else '', ''))
        except ValueError:
            return '[REDACTED_URL]'
    return re.sub(r'https?://[^\s\"<>]+', clean, text)

def main():
    name, cwd, *args = sys.argv[1:]
    if args and args[0] == '--':
        args.pop(0)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    metadata = {'command': args, 'shell_display': shlex.join(args), 'cwd': cwd,
                'started_utc': started, 'sanitization': 'URL userinfo and query strings removed'}
    path = ROOT / 'evidence' / (name + '.log')
    with path.open('w') as log:
        header = json.dumps(metadata, ensure_ascii=False) + '\n'
        log.write(header)
        print(header, end='', flush=True)
        process = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            line = sanitize(line)
            log.write(line)
            log.flush()
            print(line, end='', flush=True)
        rc = process.wait()
        metadata['exit_code'] = rc
        metadata['finished_utc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        trailer = '\n' + json.dumps({'exit_code': rc, 'finished_utc': metadata['finished_utc']}) + '\n'
        log.write(trailer)
        print(trailer, end='', flush=True)
    (ROOT / 'evidence' / (name + '.json')).write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
    return rc

if __name__ == '__main__':
    sys.exit(main())
