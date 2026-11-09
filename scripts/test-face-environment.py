import base64
import importlib.util
from pathlib import Path
import json
import struct
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('face_check',Path(__file__).with_name('check-face-environment.py'))
check=importlib.util.module_from_spec(spec);spec.loader.exec_module(check)
class FaceEnvironmentTest(unittest.TestCase):
    def test_generated_x64_header_is_distinguished_from_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'synthetic.bin';content=bytearray(128);content[:2]=b'MZ';struct.pack_into('<I',content,60,64);content[64:68]=b'PE\0\0';struct.pack_into('<H',content,68,0x8664);path.write_bytes(content)
            self.assertTrue(check.is_x64_dll(path))
            struct.pack_into('<H',content,68,0x14c);path.write_bytes(content)
            self.assertFalse(check.is_x64_dll(path))
            path.write_bytes(b'not a DLL');self.assertFalse(check.is_x64_dll(path))
    def test_secrets_never_appear_in_report_and_unavailable_sdk_stays_not_run(self):
        secret='TEST_ONLY_SECRET_SENTINEL'
        with patch.object(check.shutil,'which',return_value=None):
            report=check.inspect_environment({'LIANZHI_ARCSOFT_APP_ID':secret,'LIANZHI_ARCSOFT_SDK_KEY':secret,'LIANZHI_FACE_ENCRYPTION_KEY':secret})
        self.assertNotIn(secret,json.dumps(report))
        self.assertFalse(report['ready']);self.assertEqual('NOT_RUN',report['actualSdkActivation'])
    def test_old_default_java_does_not_hide_another_compatible_runtime(self):
        from types import SimpleNamespace
        outputs=[SimpleNamespace(returncode=0,stdout='',stderr='java version "1.8.0"\n sun.arch.data.model = 64'),SimpleNamespace(returncode=0,stdout='',stderr='openjdk version "17.0.1"\n sun.arch.data.model = 64')]
        with patch.object(check.shutil,'which',return_value='synthetic-java'),patch.object(check.subprocess,'run',side_effect=outputs):
            report=check.inspect_environment({'JAVA_HOME':'synthetic-home'})
        self.assertEqual('PASS',next(item for item in report['checks'] if item['check']=='java_17_x64')['result'])
    def test_key_and_score_validation_rejects_nan_and_incomplete_keys(self):
        self.assertTrue(check.encrypted_key_ready(base64.b64encode(bytes(32)).decode()))
        self.assertFalse(check.encrypted_key_ready('not base64'))
        for value in ['nan','inf','-1','0','1','invalid']: self.assertFalse(check.score_ready(value))
        self.assertTrue(check.score_ready('0.8'))
if __name__=='__main__': unittest.main()
