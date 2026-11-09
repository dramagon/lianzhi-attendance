import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec=importlib.util.spec_from_file_location('mobile_builder',Path(__file__).with_name('build-mobile-package.py'))
builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)
class MobilePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        files={'mobile/src/main.js':'// fixture','mobile/package-lock.json':'{}','mobile/vite.config.js':'// fixture','mobile/README.md':'public guide','mobile/Caddyfile.example':'# example','scripts/check-face-environment.py':'# public tool','docs/mobile-checklist.md':'manual acceptance'}
        for name,value in files.items():
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
        for name,value in {'mobile/dist/build/h5/index.html':'fixture','mobile/dist/build/h5/assets/app.js':'fixture js','mobile/dist/build/h5/assets/app.js.map':'not shipped','mobile/dist/build/mp-weixin/app.json':'{}','mobile/dist/build/mp-weixin/pages/index/index.json':'{"usingComponents":{"acceptance-panel":"../../components/AcceptancePanel"}}','mobile/dist/build/mp-weixin/components/AcceptancePanel.js':'// component','mobile/dist/build/mp-weixin/components/AcceptancePanel.json':'{}','mobile/dist/build/mp-weixin/components/AcceptancePanel.wxml':'<view>fixture</view>','mobile/dist/build/mp-weixin/project.private.config.json':'private','mobile/dist/build/mp-weixin/private.json':'not compiled'}.items():
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
    def build(self,dirty):
        with patch.object(builder,'ROOT',self.root),patch.object(builder.subprocess,'check_output',side_effect=['abcdef123456\n',' M changed\n' if dirty else '']),contextlib.redirect_stdout(io.StringIO()):builder.main()
    def test_working_preview_never_overwrites_clean_release(self):
        release=self.root/'artifacts/lianzhi-mobile-abcdef1.zip';release.parent.mkdir();release.write_bytes(b'keep existing release')
        self.build(True)
        self.assertEqual(b'keep existing release',release.read_bytes())
        with zipfile.ZipFile(release.with_name('lianzhi-mobile-abcdef1-working.zip')) as archive:
            self.assertTrue(json.loads(archive.read('lianzhi-mobile/manifest.json'))['working_tree_dirty'])
    def test_clean_hashes_match_and_private_developer_files_are_excluded(self):
        self.build(False)
        with zipfile.ZipFile(self.root/'artifacts/lianzhi-mobile-abcdef1.zip') as archive:
            self.assertIn('lianzhi-mobile/mp-weixin/components/AcceptancePanel.wxml',archive.namelist())
            self.assertFalse(any(name.endswith('.map') or 'private' in name for name in archive.namelist()))
            manifest=json.loads(archive.read('lianzhi-mobile/manifest.json'));self.assertFalse(manifest['working_tree_dirty'])
            for name,digest in manifest['fileHashes'].items():self.assertEqual(digest,hashlib.sha256(archive.read('lianzhi-mobile/'+name)).hexdigest())
    def test_missing_referenced_component_is_rejected_before_release_write(self):
        (self.root/'mobile/dist/build/mp-weixin/components/AcceptancePanel.wxml').unlink()
        with self.assertRaisesRegex(RuntimeError,'Missing Weixin component asset'):
            self.build(False)
if __name__=='__main__':unittest.main()
