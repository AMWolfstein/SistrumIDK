import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import code_tools as tools
import serena_adapter as serena


class NavigationTests(unittest.TestCase):
    def test_inputs_reject_escape_and_symlinks_and_exclude_builds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'main.kt').write_text('fun main() {}')
            (root/'linked.kt').symlink_to(root/'main.kt')
            self.assertEqual(tools.inputs(root,['main.kt','target/a.cc','README.md']),
                             (['main.kt'],['target/a.cc','README.md']))
            for path in ['../escape.cc','/tmp/escape.cc','linked.kt']:
                with self.assertRaises(ValueError): tools.inputs(root,[path])

    def test_native_owners_are_only_single_enclosing_classes(self):
        texts = {'Native.kt':'package app\nobject Native {\n external fun play(): Long\n class Nested {\n external fun skip(): Long\n }\n}'}
        nodes = [{'source_file':'Native.kt','source_location':'L3','label':'play'},
                 {'source_file':'Native.kt','source_location':'L5','label':'skip'}]
        self.assertEqual(tools.jvm_symbols(nodes,texts),[{'file':'Native.kt','line':3,'name':'play','owner':'Native'}])

    def test_query_keeps_incoming_edges_and_native_candidates(self):
        data = {'scope':{},'nodes':[{'id':'a','label':'play','source_file':'main.kt'},
                {'id':'b','label':'run','source_file':'other.kt'}],
                'edges':[{'source':'b','target':'a'}],
                'resources':{'links':[{'from':'main.kt','to':'data.json'}], 'coverage':{}},
                'native_bindings':[{'file':'Main.kt','candidates':[{'file':'native.cpp'}]}],
                'coverage':[{'file':'main.kt','status':'partial'}], 'limitations':[]}
        result = tools.query(data,symbol='play')
        self.assertEqual(result['totals']['relationships'],1)
        self.assertEqual(result['coverage'][0]['status'],'partial')
        self.assertEqual(len(tools.query(data,file='native.cpp')['native_bindings']),1)
        self.assertEqual(tools.query(data,file='data.json')['totals']['resources'],1)

    def test_failed_update_preserves_previous_map(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);cache=root/'.work/codegraph';cache.mkdir(parents=True)
            (cache/'map.json').write_text('previous map')
            with mock.patch.object(tools.graph,'extract',side_effect=RuntimeError('index failed')):
                with self.assertRaises(RuntimeError): tools.update(root,[])
            self.assertEqual((cache/'map.json').read_text(),'previous map')

    def test_serena_allowlist_rejects_write_tools_and_escaping_paths(self):
        for operation, file in [('replace_symbol_body','main.kt'),('references','../main.kt'),('symbols','/tmp/main.kt')]:
            with self.assertRaises(ValueError): serena.arguments(operation,file,'main')
        self.assertEqual(serena.arguments('references','main.kt','Player/play')['name_path'],'Player/play')
        self.assertEqual(serena.arguments('definition','main.kt','Player')['name_path_pattern'],'Player')
