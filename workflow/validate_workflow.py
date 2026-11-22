#!/usr/bin/env python3
"""Representative validation of the graph-aware workflow (sixteen scenarios).

Runs against temporary fixtures only; the checkout is never modified. Scenarios that
need the pinned graph engine report SKIP when it is not installed, never PASS.
"""
from __future__ import annotations
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'workflow'))
sys.path.insert(0, str(ROOT / 'workflow/codegraph'))

import code_tools  # noqa: E402
import graph_evidence  # noqa: E402
import optimize  # noqa: E402
import registry  # noqa: E402

SCENARIOS = []


def scenario(name):
    def register(function):
        SCENARIOS.append((name, function))
        return function
    return register


FIXTURE = {
    'app/src/main/kotlin/com/example/Player.kt':
        'package com.example\nclass Player {\n fun start(): Int {\n  val frames = decodeFrame()\n  return frames\n }\n fun decodeFrame(): Int = 1\n}\n',
    'app/src/main/kotlin/com/example/ui/Buttons.kt':
        'package com.example.ui\nimport androidx.compose.runtime.Composable\n@Composable\nfun GlassButton(label: String) {}\n@Composable\nfun ShinyButton(label: String) {}\n',
    'app/src/main/cpp/player.cpp':
        'extern "C" int decodeFrameImpl(void* env) { return 0; }\nint helper(void* env) { return decodeFrameImpl(env); }\n',
    'app/src/main/cpp/player.c':
        'int c_helper(void) { return 1; }\nint c_entry(void) { return c_helper(); }\n',
    'app/src/main/jni/Native.kt':
        'package com.example\nclass Native {\n external fun nativeDecode(): Int\n fun call(): Int = nativeDecode()\n}\n',
    'app/src/test/kotlin/PlayerTest.kt':
        'package com.example\nimport org.junit.Test\nclass PlayerTest {\n @Test\n fun starts() { Player().start() }\n}\n',
    'app/src/main/res/values/strings.xml': '<resources><string name="app">Sistrum</string></resources>\n',
    'app/src/main/res/layout/main.xml': '<LinearLayout><TextView android:text="@string/app" /></LinearLayout>\n',
    'app/src/main/CMakeLists.txt': 'add_library(sistrum SHARED player.cpp)\n',
    'docs/readme.md': '# Fixture\n',
}


def repository():
    temporary = tempfile.TemporaryDirectory(prefix='workflow-validation-')
    root = Path(temporary.name)
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    subprocess.run(['git', '-C', str(root), 'config', 'user.email', 'fixture@example.invalid'], check=True)
    subprocess.run(['git', '-C', str(root), 'config', 'user.name', 'Fixture'], check=True)
    (root / '.gitignore').write_text('.work/\n')
    for name, text in FIXTURE.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    subprocess.run(['git', '-C', str(root), 'add', '-A'], check=True)
    subprocess.run(['git', '-C', str(root), 'commit', '-qm', 'fixture'], check=True)
    return temporary, root


def graph_ready():
    try:
        import cgc_adapter
        return cgc_adapter.version().get('available', False)
    except (OSError, ValueError):
        return False


def updated(root):
    path, data = code_tools.update(root)
    return data


def pinned_tool():
    """Evidence scenarios must not depend on whether the graph engine is installed."""
    return mock.patch.object(graph_evidence, 'tool', return_value={
        'name': 'codegraphcontext', 'version': '0.6.13', 'pin': '0.6.13',
        'database': 'kuzu', 'available': True})


@scenario('01 Kotlin symbol discovery')
def kotlin_symbols(root):
    data = updated(root)
    names = {node['label'] for node in data['nodes']}
    assert {'Player', 'start', 'decodeFrame'} <= names, names


@scenario('02 Compose component reuse discovery')
def compose_reuse(root):
    data = updated(root)
    composable = [n for n in data['nodes'] if n.get('composable')]
    assert {n['label'] for n in composable} >= {'GlassButton', 'ShinyButton'}, composable
    duplicate = [n['label'] for n in data['nodes'] if n['label'].endswith('Button')]
    assert len(duplicate) >= 2


@scenario('03 Kotlin caller and callee traversal')
def kotlin_calls(root):
    data = updated(root)
    pairs = {(next(n['label'] for n in data['nodes'] if n['id'] == e['source']),
              next(n['label'] for n in data['nodes'] if n['id'] == e['target']))
             for e in data['edges'] if e['relation'] == 'calls'}
    assert ('start', 'decodeFrame') in pairs, pairs


@scenario('04 C and C++ symbol discovery')
def native_symbols(root):
    data = updated(root)
    names = {n['label'] for n in data['nodes']}
    assert {'decodeFrameImpl', 'helper', 'c_helper', 'c_entry'} <= names, names


@scenario('05 JNI boundary discovery')
def jni_boundary(root):
    import bridge
    report = bridge.discover(root, [])
    labels = {c.get('symbol') or c.get('kotlin_symbol') or c.get('name')
              for c in report['contracts']}
    assert any(value and 'nativeDecode' in str(value) for value in labels) or report['inventory'], report


@scenario('06 Android resource reference discovery')
def resources(root):
    data = updated(root)
    text = json.dumps(data['resources'])
    assert 'strings.xml' in text and 'layout/main.xml' in text, list(data['resources'])


@scenario('07 Test-impact analysis')
def test_impact(root):
    import test_intelligence
    updated(root)
    payload = test_intelligence.impact(root, ['app/src/main/kotlin/com/example/Player.kt'])
    assert payload['selected_tests'], payload
    assert any('PlayerTest' in item for item in payload['selected_tests']), payload


@scenario('08 Duplicate implementation discovery')
def duplicates(root):
    data = updated(root)
    counts = {}
    for node in data['nodes']:
        if node['kind'] == 'function':
            counts[node['label']] = counts.get(node['label'], 0) + 1
    assert counts.get('decodeFrame', 0) >= 1
    buttons = [n for n in data['nodes'] if n['label'] in ('GlassButton', 'ShinyButton')]
    assert len(buttons) == 2


@scenario('09 Graph refresh after a source change')
def refresh(root):
    updated(root)
    path = root / 'app/src/main/kotlin/com/example/Player.kt'
    path.write_text(path.read_text() + '\nclass QueueRunner {\n fun next(): Int = 1\n}\n')
    data = updated(root)
    assert 'QueueRunner' in {n['label'] for n in data['nodes']}


@scenario('10 Missing graph evidence rejection')
def missing_evidence(root):
    registry.initialize(root)
    identifier = registry.reserve(root, 'Graph gated task')['id']
    result = graph_evidence.verify(root, identifier)
    assert result['status'] == 'FAIL' and 'No graph evidence' in result['reason'], result


@scenario('11 Stale graph evidence detection')
def stale_evidence(root):
    task = 'STALE01'
    with pinned_tool():
        graph_evidence.record(root, task, ['callers of start -> Player.start'], ['Player.kt'])
        assert graph_evidence.verify(root, task)['status'] == 'PASS'
        target = root / 'app/src/main/kotlin/com/example/Player.kt'
        target.write_text(target.read_text() + '\n// changed\n')
        result = graph_evidence.verify(root, task)
    assert result['status'] == 'FAIL' and 'stale' in result['reason'].lower(), result


@scenario('12 Graph tool failure and documented fallback')
def tool_failure(root):
    task = 'TOOLFAIL'
    graph_evidence.record(root, task, ['symbol Player'], ['Player.kt'])
    with mock.patch.object(graph_evidence, 'tool', return_value={'available': False, 'version': None}):
        result = graph_evidence.verify(root, task)
    assert result['status'] == 'FAIL' and 'tool changed' in result['reason'].lower(), result


@scenario('13 Documentation-only exemption')
def documentation(root):
    task = 'DOCONLY'
    graph_evidence.record(root, task, [], [], exempt='documentation-only change')
    result = graph_evidence.verify(root, task)
    assert result['status'] == 'PASS' and result['exempt'] == 'documentation-only change', result


@scenario('14 Reviewer rejection and corrected completion')
def review_rounds(root):
    registry.initialize(root)
    identifier = registry.reserve(root, 'Reviewed task')['id']
    try:
        registry.complete(root, identifier, 'reviewer', 4, 'checks')
    except ValueError:
        pass
    else:
        raise AssertionError('Invalid review round was accepted')
    with pinned_tool():
        graph_evidence.record(root, identifier, ['symbol Player'], ['Player.kt'])
        checks = graph_evidence.verify(root, identifier)
    registry.complete(root, identifier, 'reviewer', 2, 'corrected after review', checks)
    state = registry.load(root)
    assert state['tasks'][identifier]['status'] == 'complete'
    assert state['tasks'][identifier]['evidence']['graph']['status'] == 'PASS'


@scenario('15 Safe optimization classification')
def optimization(root):
    assert set(optimize.PROD_ACTIONS) >= {'KEEP', 'SIMPLIFY', 'REFACTOR', 'MERGE', 'REMOVE', 'INVESTIGATE'}
    assert set(optimize.TEST_ACTIONS) >= {'KEEP', 'MERGE', 'REWRITE', 'REMOVE', 'ADD_COVERAGE', 'INVESTIGATE'}
    blocking = optimize._gate(root, {'domain': 'production', 'action': 'REMOVE',
                                     'operation': None, 'cross_language_impact': None})
    assert blocking, 'Production removal was not gated'
    allowed = optimize._gate(root, {'domain': 'test', 'action': 'MERGE',
                                    'operation': {'path': 'app/src/test/kotlin/PlayerTest.kt'},
                                    'cross_language_impact': None})
    assert allowed == [], allowed
    bridge = optimize._gate(root, {'domain': 'test', 'action': 'MERGE',
                                   'operation': {'path': 'app/src/test/kotlin/PlayerTest.kt'},
                                   'cross_language_impact': 'BRIDGE_REACHABLE'})
    assert bridge, 'Bridge-reachable target was not gated'


@scenario('16 Agent adapter consistency')
def adapters(root):
    agents = (ROOT / 'AGENTS.md').read_text()
    claude = (ROOT / 'workflow/assets/adapters/claude/CLAUDE.md').read_text()
    manifest = json.loads((ROOT / 'workflow/assets/adapters/codex/.specify/init-options.json').read_text())
    assert 'workflow/README.md' in agents and 'workflow/lifecycle.md' in agents
    assert '@AGENTS.md' in claude and 'workflow/lifecycle.md' in claude
    assert isinstance(manifest, dict)


def main():
    results = []
    graph = graph_ready()
    needs_graph = {'01', '02', '03', '04', '06', '07', '08', '09'}
    for name, function in SCENARIOS:
        if name[:2] in needs_graph and not graph:
            results.append({'scenario': name, 'status': 'SKIP', 'detail': 'CodeGraphContext not installed'})
            continue
        temporary, root = repository()
        try:
            with mock.patch.dict(os.environ, {}):
                detail = function(root)
            results.append({'scenario': name, 'status': 'PASS', 'detail': 'verified'})
        except Exception as error:  # Scenario failures must be reported, never hidden.
            results.append({'scenario': name, 'status': 'FAIL', 'detail': f'{type(error).__name__}: {error}'})
        finally:
            temporary.cleanup()
    status = 'FAIL' if any(item['status'] == 'FAIL' for item in results) else 'PASS'
    print(json.dumps({'status': status, 'graph_engine': graph,
                      'passed': sum(i['status'] == 'PASS' for i in results),
                      'skipped': sum(i['status'] == 'SKIP' for i in results),
                      'failed': sum(i['status'] == 'FAIL' for i in results),
                      'results': results}, indent=2))
    return 2 if status == 'FAIL' else 0


if __name__ == '__main__':
    raise SystemExit(main())
