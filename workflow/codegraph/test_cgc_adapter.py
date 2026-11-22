"""Real-engine regressions and staging boundaries for the CodeGraphContext adapter."""
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import cgc_adapter as adapter


class StagingTests(unittest.TestCase):
    def test_untrusted_paths_and_symlinks_cannot_escape_snapshot(self):
        for value in ('../outside', '/absolute', '.', '.git/config', '.cgc/db', '.work/x', 'C:/drive', 'a/../b'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                adapter.safe_relative(value)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'project'; source.mkdir(); cache = root / 'cache'; cache.mkdir()
            (source / 'bad.kt').symlink_to('/etc/passwd')
            with self.assertRaises(ValueError):
                adapter.stage(source, cache, ['bad.kt'])

    def test_incremental_snapshot_handles_directory_to_file_and_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'project'; source.mkdir(); cache = root / 'cache'; cache.mkdir()
            directory = source / 'module.kt'; directory.mkdir(); (directory / 'child.kt').write_text('class A {}')
            mirror = adapter.stage(source, cache, ['module.kt/child.kt'])
            (directory / 'child.kt').unlink(); directory.rmdir(); directory.write_text('class B {}')
            adapter.stage(source, cache, ['module.kt'])
            self.assertEqual((mirror / 'module.kt').read_text(), 'class B {}')
            adapter.stage(source, cache, [])
            self.assertFalse((mirror / 'module.kt').exists())

    def test_unchanged_source_mtime_is_preserved_for_incremental_sync(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); source = root / 'project'; source.mkdir(); cache = root / 'cache'; cache.mkdir()
            (source / 'a.kt').write_text('class A {}')
            mirror = adapter.stage(source, cache, ['a.kt']); before = (mirror / 'a.kt').stat().st_mtime_ns
            adapter.stage(source, cache, ['a.kt'])
            self.assertEqual((mirror / 'a.kt').stat().st_mtime_ns, before)


def available():
    try:
        return bool(adapter.command()) and Path(adapter.python()).is_file()
    except (OSError, ValueError):
        return False


@unittest.skipUnless(available(), 'CodeGraphContext not installed; run workflow/work.py setup')
class RealCodeGraphRegressions(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='cgc regression ')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name); cls.source = cls.root / 'source'; cls.source.mkdir(); cls.cache = cls.root / 'cache'
        cls.fixture = {
            'lib/calls.kt': "import external.api\nclass First {\n fun runJob() {\n  processItem();\n  val text = \"ghostCall()\";\n  calculateChecksum();\n }\n fun processItem() {}\n}\nclass Second {\n fun processItem() {}\n}\nfun ghostCall() {}\n",
            'kotlin/Engine.kt': 'package sample\nclass Engine {\n fun driveKotlin() {\n  stepKotlin()\n }\n fun stepKotlin() {}\n}\n',
            'cpp/engine.cpp': 'void calculateChecksum() {}\nvoid targetCpp() {}\nvoid driveCpp() {\n targetCpp();\n}\n',
            'c/engine.c': 'void targetC(void) {}\nvoid driveC(void) {\n targetC();\n}\n',
            'java/App.java': 'class App {\n int computeJava() { return 1; }\n int driveJava() {\n  return computeJava();\n }\n}\n',
            'py/helper.py': 'def target_python():\n    return 1\n',
            'py/main.py': 'from helper import target_python\ndef drive_python():\n    return target_python()\n',
            'scripts/run.sh': 'build_project() {\n echo hello\n}\n',
            'res/values.xml': '<resources><string name="title">Sistrum</string></resources>\n',
        }
        for name, text in cls.fixture.items():
            path = cls.source / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
        with cls.environment():
            cls.raw = cls.extract()
        cls.nodes = {n['id']: n for n in cls.raw['nodes']}

    @classmethod
    def environment(cls):
        return mock.patch.dict(os.environ, {'CGC_CACHE': str(cls.cache)})

    @classmethod
    def extract(cls):
        req, out = cls.root / 'request.json', cls.root / 'out.json'
        req.write_text(json.dumps({'root': str(cls.source), 'files': sorted(cls.fixture)}))
        adapter.extract(req, out)
        return json.loads(out.read_text())

    def call_pairs(self):
        return [(self.nodes[e['source']], self.nodes[e['target']], e)
                for e in self.raw['edges'] if e['relation'] == 'calls']

    def test_core_languages_have_real_declarations_and_calls(self):
        pairs = {(s['label'], t['label']) for s, t, _ in self.call_pairs()}
        for pair in [('driveKotlin', 'stepKotlin'), ('driveCpp', 'targetCpp'),
                     ('driveC', 'targetC'), ('driveJava', 'computeJava'), ('drive_python', 'target_python')]:
            with self.subTest(pair=pair):
                self.assertIn(pair, pairs)

    def test_strings_do_not_become_calls_and_confidence_is_explicit(self):
        calls = [(s, t, e) for s, t, e in self.call_pairs()]
        self.assertFalse([e for s, t, e in calls if t['label'] == 'ghostCall'])
        self.assertTrue(all(e['confidence'] in ('EXTRACTED', 'INFERRED') for _, _, e in calls))

    def test_shell_gaps_and_resources_are_explicit(self):
        coverage = {c['file']: c for c in self.raw['coverage']}
        self.assertEqual(coverage['scripts/run.sh']['status'], 'not-indexed')
        self.assertEqual(coverage['lib/calls.kt']['status'], 'indexed')
        self.assertIn('unresolved_references', coverage['lib/calls.kt'])
        self.assertIn('res/values.xml', coverage)
        self.assertTrue(any('JNI' in item for item in self.raw['limitations']))

    def test_python_import_resolves_to_the_project_file(self):
        imports = {(self.nodes[e['source']]['source_file'], self.nodes[e['target']]['source_file'])
                   for e in self.raw['edges'] if e['relation'] == 'imports'}
        self.assertIn(('py/main.py', 'py/helper.py'), imports)

    def test_real_rename_and_delete_leave_no_stale_symbols(self):
        with tempfile.TemporaryDirectory() as temporary, mock.patch.dict(os.environ, {'CGC_CACHE': temporary}):
            req, out = Path(temporary) / 'r.json', Path(temporary) / 'o.json'

            def run(files):
                req.write_text(json.dumps({'root': str(self.source), 'files': files}))
                adapter.extract(req, out); return json.loads(out.read_text())
            self.assertTrue(run(['lib/calls.kt'])['nodes'])
            after = run(['kotlin/Engine.kt'])
            self.assertFalse(any(n['source_file'] == 'lib/calls.kt' for n in after['nodes']))
            self.assertEqual(run([])['nodes'], [])


if __name__ == '__main__':
    unittest.main()
