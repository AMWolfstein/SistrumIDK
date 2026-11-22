import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import audit


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        self.config=json.loads(Path(audit.__file__).with_name('audit-config.json').read_text())

    def write(self,path,text):
        target=self.root/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)

    def test_reports_findings_and_gaps_without_modifying_source(self):
        self.write('workflow/main.py','import unused');self.write('android/Main.kt','class Main')
        found=[{'path':'workflow/main.py','line':1,'code':'F401','message':'candidate'}]
        def run(root,language,*args):return (found,{'status':'RAN'}) if language=='python' else ([],{'status':'UNAVAILABLE'})
        with mock.patch.object(audit,'run_tool',side_effect=run):
            result,report=audit.scan(self.root,kind='code',languages=['python','kotlin'],paths=['.'])
        self.assertEqual(result['status'],'INCOMPLETE');self.assertEqual(result['gaps'],['kotlin'])
        self.assertEqual(len(result['findings']),1);self.assertTrue(report.is_file())
        self.assertEqual((self.root/'workflow/main.py').read_text(),'import unused')

    def test_requires_scope(self):
        with self.assertRaises(ValueError):audit.scan(self.root)

    def test_jvm_languages_share_one_lint_run_and_filter_selected_files(self):
        self.write('androidApp/src/main/kotlin/Main.kt','class Main')
        self.write('androidApp/src/main/java/Other.java','class Other {}')
        output=[{'path':'androidApp/src/main/kotlin/Main.kt','line':1,'code':'ObsoleteSdkInt','message':'obsolete'},
                {'path':'androidApp/src/main/java/Other.java','line':1,'code':'UseValueOf','message':'boxing'},
                {'path':'outside.java','line':1,'code':'UseValueOf','message':'ignored'}]
        with mock.patch('native_audit.run_android',return_value=(output,{'status':'RAN'})) as run:
            result,_=audit.scan(self.root,kind='code',languages=['kotlin','java'])
        run.assert_called_once();self.assertEqual(result['status'],'FINDINGS')
        self.assertEqual(len(result['findings']),2)
        self.assertEqual(result['tools']['java']['shared_languages'],['java','kotlin'])

    def test_jvm_outside_variant_roots_is_not_silently_cleared(self):
        self.write('other/Main.kt','class Main')
        with mock.patch('native_audit.run_android') as run:
            result,_=audit.scan(self.root,kind='code',languages=['kotlin'])
        run.assert_not_called();self.assertEqual(result['status'],'INCOMPLETE')
        self.assertEqual(result['tools']['kotlin']['uncovered_files'],['other/Main.kt'])

    def test_jvm_mixed_scope_remains_partial_even_when_lint_passes(self):
        self.write('androidApp/src/main/kotlin/Main.kt','class Main')
        self.write('androidApp/src/release/kotlin/Release.kt','class Release')
        with mock.patch('native_audit.run_android',return_value=([],{'status':'RAN'})):
            result,_=audit.scan(self.root,kind='code',languages=['kotlin'])
        self.assertEqual(result['status'],'INCOMPLETE')
        self.assertEqual(result['tools']['kotlin']['status'],'PARTIAL')
        self.assertEqual(result['tools']['kotlin']['uncovered_files'],['androidApp/src/release/kotlin/Release.kt'])

    def test_android_resource_diagnostics_survive_the_jvm_file_filter(self):
        self.write('androidApp/src/main/kotlin/Main.kt','class Main')
        self.write('androidApp/src/main/res/drawable/tv_logo.png','x')
        output=[{'path':'androidApp/src/main/kotlin/Main.kt','line':1,'code':'ObsoleteSdkInt','message':'folder'},
                {'path':'androidApp/src/main/res/drawable/tv_logo.png','line':0,'code':'UnusedResources','message':'unused'}]
        with mock.patch('native_audit.run_android',return_value=(output,{'status':'RAN'})):
            result,_=audit.scan(self.root,kind='dead-code',languages=['kotlin'])
        self.assertEqual(sorted(f['code'] for f in result['findings']),['ObsoleteSdkInt','UnusedResources'])
        self.assertEqual(result['tools']['android-resources']['status'],'RAN')
        self.assertEqual(result['tools']['android-resources']['selected_diagnostics'],1)
        self.assertEqual(result['status'],'FINDINGS')

    def test_gradle_kotlin_dsl_is_not_reported_as_uncovered_kotlin(self):
        self.write('androidApp/build.gradle.kts','plugins {}')
        self.write('androidApp/src/main/kotlin/Main.kt','class Main')
        with mock.patch('native_audit.run_android',return_value=([],{'status':'RAN'})):
            result,_=audit.scan(self.root,kind='code',languages=['kotlin'])
        self.assertEqual(result['status'],'PASS');self.assertEqual(result['tools']['kotlin']['status'],'RAN')

    def test_python_and_shell_diagnostics_are_classified(self):
        ruff=[{'filename':f'{self.root}/workflow/work.py','location':{'row':4},'code':'F401','message':'unused import'}]
        shell={'comments':[{'file':f'{self.root}/scripts/build.sh','line':9,'level':'warning','code':2034,'message':'unused variable'}]}
        found=audit.parse_ruff_json(json.dumps(ruff),self.root,{'cwd':'.'})+audit.parse_shellcheck_json(json.dumps(shell),self.root,{'cwd':'.'})
        self.assertEqual([f['path'] for f in found],['workflow/work.py','scripts/build.sh'])
        self.assertEqual([f['code'] for f in found],['F401','SC2034'])
        python=audit.classify(found[:1],self.config['categories'],'dead-code','python')
        sh=audit.classify(found[1:],self.config['categories'],'dead-code','shell')
        self.assertEqual(len(python),1);self.assertEqual(len(sh),1)

    def test_shellcheck_appends_selected_files_and_findings_exit_is_not_incomplete(self):
        output=json.dumps({'comments':[{'file':'scripts/a.sh','line':1,'level':'warning','code':2086,'message':'quote'}]})
        with mock.patch.object(audit.subprocess,'run',return_value=subprocess.CompletedProcess([],1,output,'')) as run:
            found,status=audit.run_tool(self.root,'shell',self.config['tools']['shell'],['scripts/a.sh','scripts/b.sh'],'code',self.root/'shell.log')
        self.assertEqual(run.call_args.args[0][-2:],['scripts/a.sh','scripts/b.sh'])
        self.assertEqual(status['status'],'RAN');self.assertEqual(found[0]['code'],'SC2086')

    def test_cpp_dispatch_and_wildcard_categories(self):
        diagnostic={'path':'main.cpp','line':1,'code':'performance-unnecessary-copy-initialization','message':'copy'}
        found=audit.classify([diagnostic],self.config['categories'],'optimization','cpp')
        self.assertEqual(len(found),1)
        with mock.patch('native_audit.run_cpp',return_value=([diagnostic],{'status':'PARTIAL'})) as run:
            _,status=audit.run_tool(self.root,'cpp',self.config['tools']['cpp'],['main.cpp'],'code',self.root/'cpp.log')
        run.assert_called_once();self.assertEqual(status['status'],'PARTIAL')
