import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deploy_pterodactyl import DeploymentError
from forward_prebuilt import validate, main
from publish_local import check_source


class PrebuiltTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'artifact').mkdir()
        (self.root / 'scripts').mkdir()
        self.config = {'repository': 'RepubliCraft/RCPlatform', 'identity': 'RCPlatform'}
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('plugin.yml', 'name: RCPlatform\nversion: 4.1.0\n')
        self.jar = self.root / 'artifact/RCPlatform.jar'
        self.jar.write_bytes(data.getvalue())
        self.manifest = dict(schema=1, repository=self.config['repository'], identity='RCPlatform',
                             jar='RCPlatform.jar', source_commit='a'*40,
                             sha256=hashlib.sha256(data.getvalue()).hexdigest())

    def test_accepts_tested_jar(self):
        self.assertEqual(validate(self.manifest, self.config, self.root), self.jar)

    def test_rejects_corrupt_jar(self):
        self.jar.write_bytes(b'changed')
        with self.assertRaises(DeploymentError):
            validate(self.manifest, self.config, self.root)

    def test_rejects_other_repository(self):
        self.manifest['repository'] = 'Other/Repo'
        with self.assertRaises(DeploymentError):
            validate(self.manifest, self.config, self.root)

    def test_rejects_path_traversal(self):
        self.manifest['jar'] = '../RCPlatform.jar'
        with self.assertRaises(DeploymentError):
            validate(self.manifest, self.config, self.root)

    def test_rejects_non_commit_reference(self):
        self.manifest['source_commit'] = 'main'
        with self.assertRaises(DeploymentError):
            validate(self.manifest, self.config, self.root)

    def test_rejects_wrong_identity_with_valid_checksum(self):
        self.config['identity'] = self.manifest['identity'] = 'RCUI'
        self.manifest['jar'] = 'RCUI.jar'
        self.jar.rename(self.root / 'artifact/RCUI.jar')
        with self.assertRaises(DeploymentError):
            validate(self.manifest, self.config, self.root)

    def test_publish_rejects_commit_different_from_tested_tree(self):
        with patch('publish_local.git', side_effect=['main', 'different']):
            with self.assertRaisesRegex(RuntimeError, 'differs from the tested'):
                check_source(self.root, {'source_tree': 'tested'})

    def test_publish_rejects_staged_changes_after_build(self):
        with patch('publish_local.git', side_effect=['main', 'tested', 'different']):
            with self.assertRaisesRegex(RuntimeError, 'staged changes'):
                check_source(self.root, {'source_tree': 'tested'})

    def test_publish_accepts_exact_tested_tree(self):
        with patch('publish_local.git', side_effect=['main', 'tested', 'tested']):
            check_source(self.root, {'source_tree': 'tested'})

    def forward(self, latest, deployment_status=0):
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        (self.root / 'scripts/publish-config.json').write_text(json.dumps(self.config))
        with patch('forward_prebuilt.__file__', str(self.root / 'scripts/forward_prebuilt.py')), \
             patch('forward_prebuilt.subprocess.check_output', return_value=latest), \
             patch('forward_prebuilt.deploy_main', return_value=deployment_status) as deploy:
            status = main()
            return status, deploy.call_count

    def test_skips_stale_source_without_upload(self):
        self.assertEqual(self.forward('b'*40 + '\trefs/heads/main'), (None, 0))

    def test_successful_upload(self):
        self.assertEqual(self.forward('a'*40 + '\trefs/heads/main'), (0, 1))

    def test_failed_upload_fails_job(self):
        self.assertEqual(self.forward('a'*40 + '\trefs/heads/main', 1), (1, 1))


if __name__ == '__main__':
    unittest.main()
