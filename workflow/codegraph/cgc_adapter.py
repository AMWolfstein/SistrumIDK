#!/usr/bin/env python3
"""Repository-scoped CodeGraphContext adapter for the local workflow.

Indexes a private mirror of the requested inputs into an embedded KuzuDB graph and
returns the normalized node/edge snapshot consumed by code_tools. The application
never depends on CodeGraphContext; only this tooling does.

Capability notes (verified against 0.6.13): Kotlin/Java/C/C++ symbols and calls are
extracted, with per-edge confidence labels. JNI Kotlin -> native relationships are
not linked across languages; use bridge.py/native_bindings.py for those. Gradle,
CMake and Android resources remain in resource_index.py/shell_adapter.py.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path, PurePosixPath

PACKAGE = 'codegraphcontext'
PIN = '0.6.13'
SCHEMA = 2
DATABASE = 'kuzudb'
FUNCTION = ('Function', 'Class', 'Object', 'Interface', 'Enum', 'Extension')


def repository():
    return Path(__file__).resolve().parents[2]


def _argv(name):
    value = os.environ.get(name)
    if value is None:
        return None
    parsed = json.loads(value)
    if not isinstance(parsed, list) or not parsed or not all(isinstance(v, str) and v for v in parsed):
        raise ValueError(f'{name} must be a JSON argv list')
    return parsed


def python():
    """Interpreter that can import kuzu (provided by the pinned CodeGraphContext env)."""
    if os.environ.get('CGC_PYTHON'):
        return os.environ['CGC_PYTHON']
    candidates = [repository() / '.work/cgc-python/bin/python',
                  repository() / '.work/cgc-python/Scripts/python.exe']
    for path in candidates:
        if path.is_file():
            return str(path)
    if shutil.which('codegraphcontext'):
        return sys.executable
    raise ValueError('CodeGraphContext interpreter unavailable; run python3 workflow/work.py setup')


def command():
    configured = _argv('CGC_COMMAND')
    if configured:
        return configured
    for path in [repository() / '.work/cgc-python/bin/codegraphcontext',
                 repository() / '.work/cgc-python/Scripts/codegraphcontext.exe']:
        if path.is_file():
            return [str(path)]
    found = shutil.which('codegraphcontext') or shutil.which('cgc')
    if not found:
        raise ValueError('CodeGraphContext CLI unavailable; run python3 workflow/work.py setup')
    return [found]


def run(args, timeout=900):
    done = subprocess.run([*command(), *args], capture_output=True, text=True, errors='replace',
                          stdin=subprocess.DEVNULL, timeout=timeout,
                          env={**os.environ, 'DO_NOT_TRACK': '1', 'CGC_TELEMETRY': '0'})
    if done.returncode:
        raise RuntimeError(f'CodeGraphContext exit {done.returncode}: {(done.stderr + done.stdout)[-1500:]}')
    return done.stdout + done.stderr


def version():
    info = {'schema': SCHEMA, 'package': PACKAGE, 'pin': PIN, 'database': DATABASE}
    try:
        lines = [line.strip() for line in run(['--version'], timeout=60).splitlines() if line.strip()]
        installed = next((line for line in lines if PACKAGE.lower() in line.lower()), lines[-1])
        match = re.search(r'\d+\.\d+\.\d+', installed)
        found = match.group(0) if match else installed
        return {**info, 'available': True, 'version': found, 'compatible': found == PIN}
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired, IndexError) as error:
        return {**info, 'available': False, 'compatible': False, 'reason': str(error)}


def safe_relative(value):
    if not isinstance(value, str) or not value or '\\' in value:
        raise ValueError('invalid corpus path')
    path = PurePosixPath(value)
    if (not path.parts or ':' in value or path.as_posix() != value or path.is_absolute()
            or '..' in path.parts or path.parts[0] in ('.git', '.codegraph', '.cgc', '.work')):
        raise ValueError(f'unsafe corpus path: {value}')
    return path


def stage(root, cache, paths):
    """Persistent private source mirror; unchanged mtimes are preserved for incremental sync."""
    mirror = cache / 'source'
    mirror.mkdir(parents=True, exist_ok=True)
    manifest = cache / 'inputs.json'
    previous = json.loads(manifest.read_text()) if manifest.exists() else []
    for rel in set(previous) - set(paths):
        obsolete = mirror / safe_relative(rel)
        obsolete.unlink(missing_ok=True)
        parent = obsolete.parent
        while parent != mirror:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent
    for rel in paths:
        safe_relative(rel)
        source, target = root / rel, mirror / rel
        if source.is_symlink() or not source.resolve().is_relative_to(root):
            raise ValueError(f'symlink/outside-root input: {rel}')
        content = source.read_bytes()
        if not target.exists() or target.read_bytes() != content:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
    manifest.write_text(json.dumps(sorted(paths)))
    return mirror


def _relative(mirror, path):
    if not path:
        return None
    try:
        return Path(path).resolve().relative_to(mirror.resolve()).as_posix()
    except (ValueError, OSError):
        return None


def _rows(connection, query):
    try:
        result = connection.execute(query)
    except Exception:  # Schema drift must degrade, never crash a read-only snapshot.
        return []
    rows = []
    while result.has_next():
        rows.append(result.get_next())
    return rows


def snapshot(database, mirror, requested):
    import kuzu
    db = kuzu.Database(str(database), read_only=True)
    connection = kuzu.Connection(db)
    nodes, by_id, per_file, languages, package_by_file = [], {}, {}, {}, {}
    for path, language, package in _rows(connection, "MATCH (f:File) RETURN f.path, f.language, f.package_name"):
        rel = _relative(mirror, path)
        if not rel:
            continue
        if language:
            languages[rel] = language
        if rel in requested:
            identifier = f'file:{rel}'
            by_id[identifier] = {'id': identifier, 'label': PurePosixPath(rel).name, 'kind': 'file',
                                 'source_file': rel, 'source_location': 'L1', 'end_line': None,
                                 'language': language, 'qualified_name': rel}
            nodes.append(by_id[identifier])
        if package:
            package_by_file[rel] = package


    def add(uid, name, path, line, end, lang, kind, qualified=None, **extra):
        lang = lang or languages.get(_relative(mirror, path) or '')
        rel = _relative(mirror, path)
        if rel is None or rel not in requested:
            return
        identifier = uid or f'{kind}:{rel}:{line}:{name}'
        if identifier in by_id:
            return
        node = {'id': identifier, 'label': name, 'kind': kind, 'source_file': rel,
                'source_location': f'L{line or 1}', 'end_line': end or line,
                'language': lang, 'qualified_name': qualified or name}
        node.update({k: v for k, v in extra.items() if v is not None})
        by_id[identifier] = node
        nodes.append(node)
        per_file[rel] = per_file.get(rel, 0) + (1 if kind not in ('module', 'file') else 0)

    for row in _rows(connection, "MATCH (f:Function) RETURN f.uid, f.name, f.path, f.line_number, "
                                "f.end_line, f.lang, f.class_context, f.decorators, "
                                "f.cyclomatic_complexity, f.is_composable"):
        uid, name, path, line, end, lang, owner, decorators, complexity, composable = row
        add(uid, name, path, line, end, lang, 'function',
            f'{owner}.{name}' if owner else name, decorators=decorators or None,
            complexity=complexity, composable=True if composable else None)
    for label, kind in (('Class', 'class'), ('Object', 'object'), ('Interface', 'interface'),
                        ('Enum', 'enum'), ('Extension', 'extension')):
        for row in _rows(connection, f"MATCH (n:{label}) RETURN n.name, n.path, n.line_number, n.lang"):
            add(None, row[0], row[1], row[2], None, row[3], kind)
    for row in _rows(connection, "MATCH (m:Module) RETURN m.name, m.path, m.line_number, m.lang"):
        add(None, row[0], row[1], row[2], None, row[3], 'module')
    import_targets = {rel: rel for rel in requested}
    for rel, package in package_by_file.items():
        import_targets[package] = rel
    for node in nodes:
        if node['kind'] in ('class', 'object', 'interface', 'enum'):
            import_targets.setdefault(node['label'], node['source_file'])
            package = package_by_file.get(node['source_file'])
            if package:
                import_targets[f'{package}.{node["label"]}'] = node['source_file']
    for rel in requested:
        import_targets.setdefault(PurePosixPath(rel).stem, rel)

    edges = []

    def edge(source, target, relation, line, confidence=None, evidence=None, path=None):
        if source not in by_id or target not in by_id:
            return False
        edges.append({'source': source, 'target': target, 'relation': relation,
                      'source_file': by_id[source]['source_file'],
                      'source_location': f'L{line or 1}',
                      'confidence': confidence or 'EXTRACTED',
                      'context': (evidence or {}).get('resolution'),
                      'evidence': evidence or {}})
        return True

    rejected = 0
    for row in _rows(connection, "MATCH (a:Function)-[r:CALLS]->(b:Function) RETURN a.uid, b.uid, "
                                  "r.line_number, r.confidence, r.confidence_label, r.resolution_method, "
                                  "r.called_name"):
        source, target, line, score, label, method, called = row
        left, right = by_id.get(source), by_id.get(target)
        if left and right and left['language'] != right['language'] and not (
                {left['language'], right['language']} <= {'c', 'cpp'}):
            rejected += 1
            continue  # Cross-language calls need an explicit bridge, not a shared name.
        confidence = 'EXTRACTED' if label == 'EXTRACTED' else 'INFERRED'
        edge(source, target, 'calls', line, confidence,
             {'confidence': score, 'label': label, 'resolution': method or label, 'called_name': called})
    for row in _rows(connection, "MATCH (a:Class)-[r:INHERITS]->(b:Class) RETURN a.uid, b.uid, r.line_number"):
        edge(row[0], row[1], 'inherits', row[2])
    for row in _rows(connection, "MATCH (a)-[r:IMPLEMENTS]->(b) RETURN a.uid, b.uid, r.line_number"):
        edge(row[0], row[1], 'implements', row[2])
    for row in _rows(connection, "MATCH (a:File)-[r:IMPORTS]->(b:Module) RETURN a.path, b.name, "
                                  "r.line_number"):
        source = by_id.get(f'file:{_relative(mirror, row[0])}')
        target = (import_targets.get(row[1]) or import_targets.get(str(row[1]).rsplit('.', 1)[-1])) if row[1] else None
        if source and target:
            edge(source['id'], f'file:{target}', 'imports', row[2], 'INFERRED',
                 {'resolution': 'package-map', 'module': row[1]})
    coverage = []
    for path in sorted(requested):
        symbols = per_file.get(path, 0)
        language = languages.get(path) or next(
            (n['language'] for n in nodes if n['source_file'] == path and n['language']), None)
        coverage.append({'file': path, 'language': language,
                         'status': 'indexed' if symbols else 'not-indexed',
                         'errors': [], 'symbols': symbols, 'unresolved_references': 0})
    return nodes, edges, coverage, rejected


def extract(request_path, out):
    request = json.loads(Path(request_path).read_text())
    root = Path(request['root']).resolve()
    cache = Path(os.environ.get('CGC_CACHE', str(repository() / '.work/codegraph/cgc')))
    if not cache.is_absolute():
        raise ValueError('CGC_CACHE must be absolute')
    cache.mkdir(parents=True, exist_ok=True)
    requested = set(request['files'])
    paths = sorted(requested | set(request.get('auxiliary', [])))
    info = version()
    if not info['available']:
        raise RuntimeError(info.get('reason', 'CodeGraphContext unavailable'))
    mirror = stage(root, cache, paths)
    database = cache / 'graph.kuzu'
    action = ['update', str(mirror)] if database.exists() else ['index', str(mirror)]
    run(['--database', DATABASE, '--db-path', str(database), *action])
    temporary = out.with_suffix('.tmp')
    subprocess.run([python(), str(Path(__file__).resolve()), 'snapshot', '--database', str(database),
                    '--mirror', str(mirror), '--out', str(temporary),
                    '--files', json.dumps(sorted(requested))], check=True, timeout=900)
    os.replace(temporary, out)


def write_snapshot(database, mirror, requested, out):
    mirror = Path(mirror).resolve()
    nodes, edges, coverage, rejected = snapshot(Path(database), mirror, set(requested))
    result = {'schema': SCHEMA, 'tool': version(), 'database': DATABASE,
              'nodes': nodes, 'edges': edges, 'coverage': coverage,
              'stats': {'extracted_sources': sum(c['status'] == 'indexed' for c in coverage),
                        'partial_sources': 0,
                        'unindexed_sources': sum(c['status'] == 'not-indexed' for c in coverage),
                        'rejected_cross_language_calls': rejected},
              'limitations': ['CodeGraphContext does not link JNI Kotlin externals to native symbols.',
                              'Gradle, CMake, Android resources and shell remain adapter-backed.',
                              'Static CALLS are not runtime reachability or proof of dead code.']}
    Path(out).write_text(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    sub.add_parser('version')
    cmd = sub.add_parser('extract')
    cmd.add_argument('--request', type=Path, required=True)
    cmd.add_argument('--out', type=Path, required=True)
    snap = sub.add_parser('snapshot')
    snap.add_argument('--database', type=Path, required=True)
    snap.add_argument('--mirror', type=Path, required=True)
    snap.add_argument('--out', type=Path, required=True)
    snap.add_argument('--files', required=True)
    args = parser.parse_args()
    try:
        if args.action == 'version':
            print(json.dumps(version()))
        elif args.action == 'extract':
            extract(args.request, args.out)
        else:
            write_snapshot(args.database, args.mirror, json.loads(args.files), args.out)
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
