"""Supplementary resource parsers and parse-only Bash evidence. Never executes project scripts."""
from __future__ import annotations
import json
import hashlib
import os
import posixpath
import re
import subprocess
import sys
import shlex
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
try:
    import tomllib
except ImportError:  # Python 3.10: a small explicit optional dependency, never downloaded here.
    try:
        import tomli as tomllib
    except ImportError:
        tomllib = None

FORMATS = {'.json', '.xml', '.toml'}
MASK = re.compile(r'"""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\'|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')


def masked(text):
    return MASK.sub(lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), text)


def fact(kind='extracted', confidence='high', via='resource:parser'):
    return {'fact': kind, 'confidence': confidence, 'via': via}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON key: {key}')
        result[key] = value
    return result


def parse_resource(path, text):
    ext = Path(path).suffix
    if ext == '.json':
        return json.loads(text, object_pairs_hook=unique_object)
    if ext == '.toml':
        if tomllib is None: raise RuntimeError('Python 3.10 TOML support needs tomli; no parser installed')
        return tomllib.loads(text)
    if re.search(r'<!\s*(DOCTYPE|ENTITY)\b', text, re.I):
        raise ValueError('DTD/entity declarations are not supported by the resource index')
    return ET.fromstring(text)


def pointer(value):
    return str(value).replace('~', '~0').replace('/', '~1')


def symbol(path, name, pointer='', number=1, kind='resource'):
    return {'id': 'resource:' + path + '#' + pointer, 'name': name, 'label': name,
            'file': path, 'line': number, 'lang': 'resource', 'kind': kind, 'pointer': pointer, **fact()}


def resolve_path(base, relative, texts):
    if not relative or '\\' in relative or relative.startswith(('/', '$')):
        return None
    value = posixpath.normpath(posixpath.join(base, relative))
    return value if not value.startswith('../') and value in texts else None


_CAPABILITIES = {}


def shell_python():
    override = os.environ.get('CODEGRAPH_SHELL_PYTHON')
    if override:return shlex.split(override)
    root=Path(__file__).resolve().parents[2]
    for name in ['code-tools-python','python']:
        candidate=root/'.work'/name/('Scripts/python.exe' if os.name=='nt' else 'bin/python')
        if candidate.is_file():return [str(candidate)]
    return [sys.executable]


def capabilities(command=None):
    key = json.dumps(shell_python())
    if key not in _CAPABILITIES:
        try:
            done = subprocess.run([*shell_python(), str(Path(__file__).with_name('shell_adapter.py')), 'version'],
                                  capture_output=True, text=True, timeout=15)
            info = json.loads(done.stdout) if done.returncode == 0 else {'available': False, 'reason': done.stderr[-400:]}
        except (OSError, ValueError, subprocess.TimeoutExpired) as error:
            info = {'available': False, 'reason': str(error)}
        _CAPABILITIES[key] = info
    return {'toml': (getattr(tomllib, '__name__', None), getattr(tomllib, '__version__', None)), 'python': sys.version.split()[0],
            'bash': _CAPABILITIES[key]}


def shell_parse(texts, command=None):
    paths = sorted(p for p in texts if p.endswith(('.sh', '.bash')))
    if not paths: return [], None
    info = capabilities()['bash']
    if not info.get('available'): return [], info.get('reason', 'Bash parser unavailable')
    with tempfile.TemporaryDirectory(prefix='shell-map-') as temporary:
        root = Path(temporary);request = root / 'request.json';items = []
        for index, path in enumerate(paths):
            source = root / (str(index) + '.sh');source.write_text(texts[path])
            items.append({'file': path, 'path': str(source)})
        request.write_text(json.dumps(items))
        done = subprocess.run([*shell_python(), str(Path(__file__).with_name('shell_adapter.py')),
                               'extract', '--request', str(request)], capture_output=True, text=True, timeout=60)
        if done.returncode: return [], done.stderr[-1000:]
        return json.loads(done.stdout), None


def index(texts, command):
    symbols, links, coverage, documents, diagnostics = [], [], {}, {}, []
    resource_names = {}
    for path, text in texts.items():
        ext = Path(path).suffix
        if ext not in FORMATS: continue
        try:
            data = parse_resource(path, text)
            documents[path] = data
            status = {'file': path, 'status': 'resource-indexed', 'errors': [], 'symbols': 1,
                      'via': 'resource:parser', 'scope': 'structured data, not a code call graph'}
            symbols.append(symbol(path, posixpath.basename(path)))
            if isinstance(data, dict):
                # Structured top-level/table names, not every repeated field of large fixture arrays.
                for key, value in data.items():
                    if key.startswith('@'): continue
                    number = 1
                    if ext == '.json':
                        found = re.search(r'^\s*' + re.escape(json.dumps(key, ensure_ascii=False)) + r'\s*:', text, re.M)
                        if found: number = text.count('\n', 0, found.start()) + 1
                    symbols.append(symbol(path, key, '/' + pointer(key), number))
                    if ext == '.toml' and isinstance(value, dict):
                        for entry in value: symbols.append(symbol(path, str(entry), '/' + pointer(key) + '/' + pointer(entry)))
            if ext == '.xml':
                parts = path.split('/res/')
                if len(parts) == 2:
                    category = parts[1].split('/')[0].split('-')[0]
                    if category != 'values': resource_names.setdefault(category + '/' + Path(path).stem, set()).add(path)
                for element in data.iter():
                    name = element.attrib.get('name')
                    if name and data.tag == 'resources' and element in list(data):
                        category = element.attrib.get('type') or element.tag.rsplit('}', 1)[-1]
                        resource_names.setdefault(category + '/' + name, set()).add(path)
                        symbols.append(symbol(path, category + '/' + name, pointer(category) + '/' + pointer(name)))
                    for value in element.attrib.values():
                        for match in re.finditer(r'@\+id/([\w.]+)', value):
                            resource_names.setdefault('id/' + match.group(1), set()).add(path)
            status['symbols'] = sum(s['file'] == path for s in symbols);coverage[path] = status
        except (ValueError, RuntimeError, ET.ParseError, RecursionError) as error:
            coverage[path] = {'file': path, 'status': 'resource-unavailable' if isinstance(error, RuntimeError) else 'resource-error', 'errors': [str(error)], 'symbols': 0,
                              'via': 'resource:parser'}

    def link(start, end, kind, detail, confidence='medium', via='resource:reference'):
        if start != end:
            links.append({'from': start, 'to': end, 'kind': kind, 'detail': detail,
                          **fact('inferred', confidence, via)})

    unresolved = []
    def android_targets(name, source):
        targets = resource_names.get(name, set())
        module = source.split('/src/', 1)[0] if '/src/' in source else None
        if module is not None:
            local = {p for p in targets if p.startswith(module + '/src/')}
            if local: return local
            if targets: unresolved.append({'file': source, 'name': name, 'reason': 'resource belongs to another module', 'candidates': sorted(targets)})
            return set()
        modules = {p.split('/src/', 1)[0] for p in targets if '/src/' in p}
        if len(modules) > 1:
            unresolved.append({'file': source, 'name': name, 'reason': 'Android module ambiguous', 'candidates': sorted(targets)})
            return set()
        return targets

    for path, text in texts.items():
        ext = Path(path).suffix;code = masked(text)
        if ext in ('.kt', '.java'):
            for match in re.finditer(r'(?<![\w.])R\.(\w+)\.(\w+)', code):
                for target in android_targets(match.group(1) + '/' + match.group(2), path):
                    link(path, target, 'android-resource', match.group(0))
        if ext == '.xml' and path in documents:
            for element in documents[path].iter():
                values = list(element.attrib.values()) + ([element.text.strip()] if element.text else [])
                for value in values:
                    match = re.fullmatch(r'@(?!\+|[\w.]+:)(\w+/[\w.]+)', value)
                    if match:
                        for target in android_targets(match.group(1), path): link(path, target, 'android-resource', value)
    shell_files = [p for p in texts if p.endswith(('.sh', '.bash'))]
    try:
        parsed, failure = shell_parse(texts, command)
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        parsed, failure = [], str(error)
    shell = []
    for item in parsed:
        path = item['file'];shell.append(item)
        for function in item['functions']:
            symbols.append({**symbol(path, function['name'], 'function:' + str(function['line']), function['line'], 'function'),
                            'lang': 'shell', 'end_line': function['end_line'], 'via': 'shell:tree-sitter-bash'})
        coverage[path] = {'file': path, 'status': 'shell-partial' if item['errors'] else 'shell-indexed',
                          'errors': item['errors'], 'symbols': len(item['functions']),
                          'via': 'shell:tree-sitter-bash', 'scope': 'Bash syntax and command inventory; dynamic execution unresolved'}
        for call in item['commands']:
            args = call['args'];name = call['name']
            if name in ('source', '.', 'bash', 'sh') and args and args[0]:
                # Shell relative paths depend on cwd. A repository-root candidate is explicitly low confidence.
                target = resolve_path('', args[0], texts)
                if target: link(path, target, 'shell-script', args[0], 'low', 'shell:cwd-candidate')
    if failure:
        for path in shell_files:
            coverage[path] = {'file': path, 'status': 'shell-unavailable', 'errors': [failure], 'symbols': 0}
    # Deduplicate occurrences, retaining their relationship kind and detail.
    links = list({(d['from'], d['to'], d['kind'], d['detail']): d for d in links}.values())
    return {'symbols': list({s['id']: s for s in symbols}.values()), 'links': links,
            'coverage': coverage, 'shell': shell, 'unresolved': unresolved}
