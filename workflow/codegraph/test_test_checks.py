import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import audit
import test_checks as checks


class TestAuditChecks(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        self.config=json.loads(Path(audit.__file__).with_name('audit-config.json').read_text())

    def write(self,path,text):
        file=self.root/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text);return file

    def test_assertions_in_comments_strings_do_not_hide_missing_assertion(self):
        self.write('androidApp/src/test/kotlin/ExampleTest.kt', '''// @Test
// fun fake() {}
@Test
fun first() { val text = "expect(x)"; /* assert(true); */ println(text) }
@Test
fun second() { assertEquals(1, 1) }
''')
        findings,status=checks.inspect_tests(self.root,{'kotlin':['androidApp/src/test/kotlin/ExampleTest.kt']},{},lambda p:True)
        self.assertEqual(status['tests_read'],2)
        self.assertEqual([f['code'] for f in findings],['assertion_free'])

    def test_duplicate_bodies_are_leads_and_string_whitespace_is_significant(self):
        body = '() { assertEquals("long result", transform("a b")) }'
        self.write('androidApp/src/test/kotlin/ExampleTest.kt', f"@Test\nfun one{body}\n@Test\nfun two{body}\n@Test\nfun three{body.replace('a b', 'ab')}\n")
        findings,_=checks.inspect_tests(self.root,{'kotlin':['androidApp/src/test/kotlin/ExampleTest.kt']},{},lambda p:True)
        self.assertEqual([f['code'] for f in findings],['same_body'])
        self.assertIn('setup',findings[0]['message'])

    def test_unsupported_test_syntax_is_explicit_partial_coverage(self):
        self.write('tests/a.cpp','TEST(Audio, Plays) { EXPECT_TRUE(play()); }')
        _,status=checks.inspect_tests(self.root,{'cpp':['tests/a.cpp']},{},lambda p:True)
        self.assertEqual(status['status'],'PARTIAL');self.assertIn('tests/a.cpp',status['syntax_limits'])

    def test_direct_reference_check_reads_tests_beyond_selected_source_scope(self):
        self.write('androidApp/src/main/kotlin/Main.kt','class Main')
        self.write('androidApp/src/test/kotlin/ExampleTest.kt',"import app.Main\n@Test\nfun check() { assertEquals(1, 1) }")
        with mock.patch('native_audit.run_android',return_value=([],{'status':'RAN'})):
            result,_=audit.scan(self.root,paths=['androidApp/src/main/kotlin/Main.kt'])
        self.assertNotIn('no_direct_test',[f['code'] for f in result['findings']])
        self.assertIn('coverage-kotlin',result['gaps'])
        self.assertEqual(result['status'],'INCOMPLETE')

    def test_combined_default_collects_code_and_test_findings_without_running_tests(self):
        self.write('androidApp/src/main/kotlin/Main.kt','class Main')
        self.write('androidApp/src/test/kotlin/ExampleTest.kt',"@Test\nfun empty() { work() }")
        diagnostic={'path':'androidApp/src/main/kotlin/Main.kt','line':1,'code':'ObsoleteSdkInt','message':'unused'}
        with mock.patch('native_audit.run_android',return_value=([diagnostic],{'status':'RAN'})) as run:
            result,report=audit.scan(self.root,paths=['.'])
        self.assertEqual(run.call_count,1)
        self.assertEqual(result['kind'],'all')
        categories={c for finding in result['findings'] for c in finding['categories']}
        self.assertTrue({'dead-code','tests','gaps'} <= categories)
        self.assertIn('INCOMPLETE',report.read_text())

    def test_tests_only_skips_compilers_and_missing_coverage(self):
        self.write('androidApp/src/test/kotlin/ExampleTest.kt',"@Test\nfun empty() { work() }")
        with mock.patch('native_audit.run_android') as run:
            result,_=audit.scan(self.root,kind='tests',paths=['.'])
        run.assert_not_called();self.assertEqual(result['status'],'FINDINGS');self.assertEqual(result['gaps'],[])

    def test_lcov_aggregates_records_and_rejects_stale_or_malformed_evidence(self):
        source=self.write('sharedLogic/src/main/kotlin/Main.kt','class Main {}\n')
        report=self.write('coverage/lcov.info','SF:sharedLogic/src/main/kotlin/Main.kt\nDA:1,0\nend_of_record\nSF:sharedLogic/src/main/kotlin/Main.kt\nDA:1,2\nDA:2,0\nend_of_record\n')
        settings={'kotlin':{'lcov':'coverage/lcov.info'}}
        findings,status=checks.inspect_coverage(self.root,{'kotlin':['sharedLogic/src/main/kotlin/Main.kt']},settings)
        self.assertEqual([f['line'] for f in findings],[2]);self.assertEqual(status['kotlin']['status'],'RAN')
        os.utime(source,(report.stat().st_mtime+10,report.stat().st_mtime+10))
        findings,status=checks.inspect_coverage(self.root,{'kotlin':['sharedLogic/src/main/kotlin/Main.kt']},settings)
        self.assertEqual(findings,[]);self.assertEqual(status['kotlin']['status'],'PARTIAL')
        report.write_text('SF:sharedLogic/src/main/kotlin/Main.kt\nDA:bad,0\n')
        _,status=checks.inspect_coverage(self.root,{'kotlin':['sharedLogic/src/main/kotlin/Main.kt']},settings)
        self.assertEqual(status['kotlin']['status'],'INCOMPLETE')

    def test_cli_default_combines_checks_and_can_target_whole_repo(self):
        args=audit.configure_parser(__import__('argparse').ArgumentParser()).parse_args([])
        self.assertEqual(args.kind,'all')
        args.root=self.root
        with mock.patch.object(audit,'scan',return_value=({'status':'PASS','findings':[],'gaps':[]},self.root/'report.md')) as scan:
            audit.execute(args)
        self.assertEqual(scan.call_args.args[3],['.'])

    def test_gaps_only_test_scope_does_not_claim_sources_were_checked(self):
        self.write('androidApp/src/test/kotlin/ExampleTest.kt',"@Test\nfun check() { assertEquals(1, 1) }")
        result,_=audit.scan(self.root,kind='gaps',paths=['androidApp/src/test'])
        self.assertEqual(result['status'],'NO_FILES')
