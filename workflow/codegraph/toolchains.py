"""Read toolchain versions from the repository's own pin files, not copies."""
from pathlib import Path
import re


def jdk_version(root):
    """Prefer the Gradle daemon pin, with the Android module's Java target as fallback."""
    root = Path(root)
    for relative, pattern in (
        ('gradle/gradle-daemon-jvm.properties', r'^\s*toolchainVersion\s*=\s*(\d+)\s*$'),
        ('androidApp/build.gradle.kts', r'JavaVersion\.VERSION_(\d+)'),
    ):
        try:
            content = (root / relative).read_text(encoding='utf-8')
        except OSError:
            continue
        found = re.search(pattern, content, re.M)
        if found:
            return int(found.group(1))
    return None
