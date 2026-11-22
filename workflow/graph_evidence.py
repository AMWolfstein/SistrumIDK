"""Mandatory graph-exploration evidence: recorded from real tool output, tied to source state.

An evidence file lives under the ignored .work/graph/ directory. It is concise: the
queries that were run, what they returned, the impact scope and known limitations.
Completion is refused when evidence is missing, stale, or fabricated (no queries).
"""
from __future__ import annotations
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import registry

SCHEMA = 1
FOLDER = '.work/graph'


def git(root, *args):
    done = subprocess.run(['git', *args], cwd=root, capture_output=True, check=True)
    return done.stdout


def state(root):
    """HEAD plus content hashes of dirty tracked/untracked files: staleness is content-based."""
    head = git(root, 'rev-parse', 'HEAD').decode().strip()
    raw = git(root, 'status', '--porcelain=v1', '-z').decode(errors='replace')
    entries = [item for item in raw.split('\0') if item]
    dirty = {}
    skip = False
    for entry in entries:
        code, name = entry[:2], entry[3:]
        if skip:  # rename target already captured
            skip = False
            continue
        if 'R' in code:
            skip = True
        path = root / name
        dirty[name] = (hashlib.sha256(path.read_bytes()).hexdigest()
                       if path.is_file() and not path.is_symlink() else 'absent')
    digest = hashlib.sha256(json.dumps({'head': head, 'dirty': dirty}, sort_keys=True).encode()).hexdigest()
    return {'head': head, 'digest': digest, 'dirty': sorted(dirty)}


def tool(root):
    folder = Path(__file__).with_name('codegraph')
    sys.path.insert(0, str(folder))
    try:
        import cgc_adapter
        info = cgc_adapter.version()
    finally:
        sys.path.pop(0)
    return {'name': cgc_adapter.PACKAGE, 'version': info.get('version'), 'pin': info.get('pin'),
            'database': info.get('database'), 'available': bool(info.get('available'))}


def path(root, task):
    return registry.safe(root, f'{FOLDER}/{task}.json')


def record(root, task, queries, impact, limitations=(), exempt=None):
    if not task or '/' in task or '..' in task:
        raise ValueError('Task identifier must be a plain name')
    queries = [query for query in queries if query.strip()]
    if not queries and not exempt:
        raise ValueError('At least one real query is required (or an explicit exemption reason)')
    if not impact and not exempt:
        raise ValueError('Impact scope is required (or an explicit exemption reason)')
    payload = {'schema': SCHEMA, 'task': task, 'recorded_at': subprocess.run(
        ['date', '-u', '+%Y-%m-%dT%H:%M:%SZ'], capture_output=True, text=True).stdout.strip(),
        'source': state(root), 'tool': tool(root),
        'queries': [{'query': item} for item in queries], 'impact': impact,
        'limitations': list(limitations), 'exempt': exempt}
    target = path(root, task)
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', dir=target.parent, delete=False) as out:
        json.dump(payload, out, indent=2, sort_keys=True)
        temporary = Path(out.name)
    os.replace(temporary, target)
    return {'status': 'PASS', 'evidence': str(target), 'task': task}


def verify(root, task):
    target = path(root, task)
    if not target.is_file():
        return {'status': 'FAIL', 'reason': f'No graph evidence recorded for {task}; run: python3 workflow/work.py graph record --task {task}',
                'recovery': 'Explore the graph first, then record queries and impact.'}
    try:
        payload = json.loads(target.read_text())
    except (OSError, ValueError) as error:
        return {'status': 'FAIL', 'reason': f'Unreadable graph evidence: {error}'}
    if payload.get('task') != task:
        return {'status': 'FAIL', 'reason': 'Graph evidence belongs to another task'}
    if payload.get('exempt'):
        return {'status': 'PASS', 'task': task, 'exempt': payload['exempt'],
                'reason': f"Explicit exemption: {payload['exempt']}"}
    current = state(root)
    if payload.get('source', {}).get('digest') != current['digest']:
        return {'status': 'FAIL', 'reason': 'Graph evidence is stale: source state changed since it was recorded',
                'recorded': payload.get('source'), 'current': current,
                'recovery': 'Re-run the graph queries against the current tree and record them again.'}
    if not payload.get('queries'):
        return {'status': 'FAIL', 'reason': 'Graph evidence has no executed queries'}
    if not payload.get('impact'):
        return {'status': 'FAIL', 'reason': 'Graph evidence has no impact scope'}
    recorded = payload.get('tool', {})
    live = tool(root)
    if not live['available'] or live['version'] != recorded.get('version'):
        return {'status': 'FAIL', 'reason': f"Graph tool changed since recording ({recorded.get('version')} -> {live.get('version')})",
                'recovery': 'Re-run queries with the pinned tool and record again.'}
    return {'status': 'PASS', 'task': task, 'queries': len(payload['queries']),
            'tool': recorded.get('version'), 'revision': payload['source']['head'], 'impact': payload['impact']}


def status(root):
    folder = registry.safe(root, FOLDER)
    items = []
    if folder.is_dir():
        for file in sorted(folder.glob('*.json')):
            try:
                payload = json.loads(file.read_text())
                items.append({'task': payload.get('task', file.stem),
                              'stale': payload.get('source', {}).get('digest') != state(root)['digest'],
                              'exempt': bool(payload.get('exempt')),
                              'queries': len(payload.get('queries', []))})
            except (OSError, ValueError):
                items.append({'task': file.stem, 'error': 'unreadable'})
    return {'status': 'PASS', 'evidence': items, 'folder': FOLDER,
            'note': 'Evidence files are local and ignored; commit the summary in the task record.'}
