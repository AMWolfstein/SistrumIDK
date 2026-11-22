import tempfile
from pathlib import Path
import unittest
import toolchains


class ToolchainTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)

    def test_jdk_version_comes_from_the_gradle_build(self):
        self.assertIsNone(toolchains.jdk_version(self.root))
        path=self.root/'androidApp/build.gradle.kts';path.parent.mkdir(parents=True)
        path.write_text('android {\n  compileOptions {\n    sourceCompatibility = JavaVersion.VERSION_26\n  }\n}\n')
        self.assertEqual(toolchains.jdk_version(self.root),26)

        daemon = self.root / 'gradle/gradle-daemon-jvm.properties'
        daemon.parent.mkdir()
        daemon.write_text('toolchainVersion=21\n')
        self.assertEqual(toolchains.jdk_version(self.root), 21)
