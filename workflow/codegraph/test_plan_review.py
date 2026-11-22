import json
from pathlib import Path
import tempfile
import unittest

import plan_review


class PlanReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.feature = self.root / 'specs/001-demo'
        self.feature.mkdir(parents=True)
        (self.root / 'sharedLogic').mkdir()
        (self.root / 'sharedLogic/existing.kt').write_text('class Existing {}\n')

    def write_plan(self, text, tasks=None):
        (self.feature / 'spec.md').write_text('# Spec\n\nRequirements.\n')
        (self.feature / 'plan.md').write_text(text)
        (self.feature / 'tasks.md').write_text(tasks or '- T001 implement and test `sharedLogic/existing.kt`\n')

    def test_clean_plan_passes_with_existing_evidence(self):
        self.write_plan('# Plan\n\nChange `sharedLogic/existing.kt`; add a regression test for error handling and rollback.\n',
                        '- T001 update `sharedLogic/existing.kt`\n- T002 add error regression test\n')
        result = plan_review.review(self.root, self.feature)
        self.assertEqual(result['decision'], 'PASS')
        self.assertEqual(result['findings'], [])

    def test_missing_test_work_needs_revision(self):
        self.write_plan('# Plan\n\nChange `sharedLogic/existing.kt` and add rollback.\n', '- T001 update `sharedLogic/existing.kt`\n')
        result = plan_review.review(self.root, self.feature)
        self.assertEqual(result['decision'], 'NEEDS_REVISION')
        self.assertIn('test-missing', {item['check'] for item in result['findings']})

    def test_bridge_plan_without_evidence_needs_revision(self):
        self.write_plan('# Plan\n\nChange the JNI bridge in `sharedLogic/existing.kt`; add a test and rollback.\n')
        result = plan_review.review(self.root, self.feature)
        checks = {item['check'] for item in result['findings']}
        self.assertIn('bridge-evidence', checks)
        self.assertEqual(result['decision'], 'NEEDS_REVISION')

    def test_failed_bridge_contract_blocks_the_plan(self):
        self.write_plan('# Plan\n\nChange the JNI bridge; add a test and rollback.\n')
        report = self.root / 'bridge.json'
        report.write_text(json.dumps({'scorecard': {'failed_contracts': {'ids': ['BR-1']},
                                                    'unresolved_contracts': {'ids': []}}}))
        result = plan_review.review(self.root, self.feature, bridge_report=report)
        self.assertEqual(result['decision'], 'BLOCKED')
        self.assertIn('bridge-failed', {item['check'] for item in result['findings']})

    def test_write_is_read_only_for_sources(self):
        self.write_plan('# Plan\n\nChange `sharedLogic/existing.kt`; add a regression test for error handling and rollback.\n',
                        '- T001 update `sharedLogic/existing.kt`\n- T002 add error regression test\n')
        before = (self.root / 'sharedLogic/existing.kt').read_text()
        payload = plan_review.write(self.root, plan_review.review(self.root, self.feature))
        self.assertEqual((self.root / 'sharedLogic/existing.kt').read_text(), before)
        self.assertTrue((self.root / '.work/quality/plan-review.json').is_file())
        self.assertEqual(payload['decision'], 'PASS')


if __name__ == '__main__':
    unittest.main()
