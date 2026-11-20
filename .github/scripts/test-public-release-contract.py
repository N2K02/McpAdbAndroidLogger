#!/usr/bin/env python3
"""Guard the public release contract without Android builds or credentials."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class PublicReleaseContract(unittest.TestCase):
    def test_token_free_source(self):
        for p in (ROOT / '.github/workflows').glob('*.yml'):
            source = p.read_text()
            self.assertNotIn('secrets.ANDROID_DISTRIBUTION_TOKEN', source, str(p))
            self.assertNotIn('publish-distribution.sh', source, str(p))
        self.assertFalse((ROOT / '.github/scripts/publish-distribution.sh').exists())

    def test_runtime_uses_public_release_adapter(self):
        build = (ROOT / 'app/build.gradle.kts').read_text()
        versions = re.findall(r'dev.n2k02:updater:(\d+\.\d+\.\d+)', build)
        self.assertTrue(versions)
        self.assertTrue(all(tuple(map(int, v.split('.'))) >= (0, 1, 34) for v in versions))
        for p in (ROOT / 'app/src').rglob('*.kt'):
            if '/test/' not in str(p):
                self.assertNotIn('publishedSource = PublishedUpdateSource(', p.read_text(), str(p))

    def test_both_channels_keep_real_version_metadata(self):
        workflows = [p.read_text() for p in (ROOT / '.github/workflows').glob('*.yml')]
        publishers = [t for t in workflows if '--notes-file release-notes.md' in t or 'body_path: release-notes.md' in t]
        self.assertTrue(publishers)
        for t in publishers:
            self.assertIn('Version code:', t)
            self.assertIn('--prerelease', t)
            self.assertIn('--latest=false', t)
            self.assertTrue('RELEASE_TRACK:' in t or re.search(r'(make_latest: true|--latest[)\s])', t))


    def test_shizuku_only_direct_release_channels(self):
        workflows = ROOT / '.github/workflows'
        build = (workflows / 'build.yml').read_text()
        verify = (workflows / 'experimental-apk.yml').read_text()
        updater = (ROOT / 'app/src/main/java/dev/n2k02/mcplogger/LiveUpdater.kt').read_text()
        naming = (ROOT / '.github/scripts/experimental_naming.py').read_text()
        self.assertIn('branches: [main]', build)
        self.assertIn('branches: [main]', verify)
        self.assertIn('RELEASE_TRACK: shizuku', build)
        self.assertIn('tag="shizuku-v$VERSION"', build)
        self.assertIn('tag="experimental-shizuku-pr-$PR_NUMBER-run-$GITHUB_RUN_NUMBER"', build)
        self.assertIn('extra=(--latest)', build)
        self.assertIn('extra=(--prerelease --latest=false)', build)
        self.assertIn('releaseTrack = "shizuku"', updater)
        self.assertIn('shizuku-v', naming)
        self.assertFalse((workflows / 'apk-retention.yml').exists())
        self.assertFalse((ROOT / '.github/scripts/group-shizuku-downloads.py').exists())
        for file in workflows.glob('*.yml'):
            source = file.read_text()
            self.assertNotIn('group-downloads.yml', source, str(file))
            self.assertNotIn('downloads-stable', source, str(file))
            self.assertNotIn('downloads-experimental', source, str(file))

if __name__ == '__main__':
    unittest.main()
