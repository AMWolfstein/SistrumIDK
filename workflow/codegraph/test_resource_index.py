"""Structured-resource and real Bash parser regressions; no project script is executed."""
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import resource_index


class ResourceTests(unittest.TestCase):
    def parse(self, texts): return resource_index.index(texts, ['codegraph'])

    def test_android_resources_and_external_namespaces_are_distinguished(self):
        texts = {'android/app/src/main/res/layout/player.xml': '<View xmlns:android="http://schemas.android.com/apk/res/android" android:id="@+id/play" android:background="@color/bg"/>',
                 'android/app/src/main/res/values/colors.xml': '<resources><color name="bg">#fff</color></resources>',
                 'android/app/src/main/res/values/styles.xml': '<resources><style name="App"><item name="android:windowBackground">@color/bg</item></style></resources>',
                 'android/app/src/main/kotlin/Player.kt': 'fun show() { R.layout.player; R.id.play; android.R.color.bg; val fake = "R.color.bg" }'}
        data = self.parse(texts)
        links = [d for d in data['links'] if d['from'].endswith('.kt')]
        self.assertEqual({d['detail'] for d in links}, {'R.layout.player', 'R.id.play'})
        self.assertTrue(any(d['from'].endswith('player.xml') and d['to'].endswith('colors.xml') for d in data['links']))
        self.assertTrue(any(d['from'].endswith('styles.xml') and d['to'].endswith('colors.xml') for d in data['links']))
        self.assertFalse(any(s['name'] == 'item/android:windowBackground' for s in data['symbols']))

    def test_invalid_json_xml_and_dtd_are_reported(self):
        texts = {'bad.json': '{bad', 'bad.xml': '<resources>',
                 'entity.xml': '<!DOCTYPE x [<!ENTITY y "z">]><x>&y;</x>', 'duplicate.json': '{"id":1,"id":2}'}
        data = self.parse(texts)
        self.assertEqual(len(data['coverage']), len(texts))
        self.assertTrue(all(c['status'] == 'resource-error' for c in data['coverage'].values()))
        self.assertFalse(data['symbols'])

    def test_missing_toml_parser_is_unavailable_not_valid_or_malformed(self):
        with mock.patch.object(resource_index, 'tomllib', None):
            data = self.parse({'gradle/libs.versions.toml': '[versions]\nname="app"'})
        self.assertEqual(data['coverage']['gradle/libs.versions.toml']['status'], 'resource-unavailable')

    def test_android_module_boundaries_and_ambiguous_sources_are_explicit(self):
        texts = {'one/src/main/res/layout/player.xml': '<View/>', 'two/src/main/res/layout/player.xml': '<View/>',
                 'one/src/main/kotlin/App.kt': 'fun show() { R.layout.player }', 'unknown/App.kt': 'fun show() { R.layout.player }'}
        data = self.parse(texts)
        links = [d for d in data['links'] if d['from'] == 'one/src/main/kotlin/App.kt']
        self.assertEqual([d['to'] for d in links], ['one/src/main/res/layout/player.xml'])
        self.assertFalse([d for d in data['links'] if d['from'] == 'unknown/App.kt'])
        self.assertTrue(data['unresolved'])

    def test_commented_file_names_in_python_and_config_are_not_references(self):
        data = self.parse({'fixture.json': '{}', 'example.py': '# File("fixture.json")\n',
                           'settings.toml': '# File("fixture.json")\nvalue=1\n'})
        self.assertFalse(data['links'])

    def test_toml_pointer_escaping_keeps_distinct_symbols(self):
        if resource_index.tomllib is None: self.skipTest('TOML parser unavailable')
        data = self.parse({'a.toml': '"a/b"=1\n[a]\nb=2\n'})
        ids = [s['id'] for s in data['symbols']]
        self.assertIn('resource:a.toml#/a~1b', ids)
        self.assertIn('resource:a.toml#/a/b', ids)
        self.assertEqual(len(ids), len(set(ids)))

    def test_missing_bash_parser_keeps_explicit_gap(self):
        with mock.patch.object(resource_index, 'shell_parse', return_value=([], 'parser absent')):
            data = self.parse({'scripts/build.sh': 'echo ok'})
        self.assertEqual(data['coverage']['scripts/build.sh']['status'], 'shell-unavailable')


BASH = resource_index.capabilities()['bash'].get('available', False)


@unittest.skipUnless(BASH, 'Pinned Bash parser unavailable; install requirements.txt or set CODEGRAPH_SHELL_PYTHON')
class BashTests(unittest.TestCase):
    def test_real_ast_functions_commands_sources_and_dynamic_receivers(self):
        data = resource_index.index({'scripts/run.sh': 'build_project() { echo ok; }\nbuild_project\nbash scripts/helper.sh\n$COMMAND arg\n',
                                     'scripts/helper.sh': 'echo helper\n'}, [])
        self.assertTrue(all(c['status'] == 'shell-indexed' for c in data['coverage'].values()))
        self.assertTrue(any(s['name'] == 'build_project' for s in data['symbols']))
        commands = data['shell'][0]['commands'] + data['shell'][1]['commands']
        self.assertTrue(any(c['dynamic'] and c['expression'] == '$COMMAND' for c in commands))
        self.assertTrue(any(d['to'] == 'scripts/helper.sh' and d['confidence'] == 'low' for d in data['links']))

    def test_scripts_are_never_executed_and_heredoc_text_is_not_a_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            sentinel = Path(temporary) / 'must-not-exist'
            script = f'touch "{sentinel}"\ncat <<\'TEXT\'\nnot_a_command\nTEXT\necho "also_not_a_command"\n'
            data = resource_index.index({'test.sh': script}, [])
            self.assertEqual(data['coverage']['test.sh']['status'], 'shell-indexed')
            names = [c['name'] for c in data['shell'][0]['commands']]
            self.assertNotIn('not_a_command', names)
            self.assertNotIn('also_not_a_command', names)
            self.assertFalse(sentinel.exists())

    def test_bash_syntax_errors_are_partial_not_indexed(self):
        data = resource_index.index({'bad.sh': 'if true; then\necho missing_fi\n'}, [])
        self.assertEqual(data['coverage']['bad.sh']['status'], 'shell-partial')
        self.assertTrue(data['coverage']['bad.sh']['errors'])


if __name__ == '__main__': unittest.main()
