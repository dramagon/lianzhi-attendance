"""Configuration boundary regressions. No services or real credentials are required."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('c_runtime', ROOT / 'tools/c-package/run-full.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class ConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.jar = self.folder / 'app.jar'
        self.jar.touch()
        self.ollama = self.folder / ('ollama' + runtime.SUFFIX)
        self.ollama.touch()
        self.env_patch = patch.dict(os.environ, {}, clear=True)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)
        java = patch.object(runtime, 'java_executable', return_value=Path('java'))
        java.start()
        self.addCleanup(java.stop)

    def args(self, *extra):
        return runtime.parser().parse_args(['--jar', str(self.jar), '--ollama', str(self.ollama),
                                           '--data-directory', str(self.folder / 'data'), '--text-only'] + list(extra))

    def mysql_env(self):
        os.environ.update(DB_URL='jdbc:mysql://127.0.0.1:33306/test', DB_USERNAME='test', DB_PASSWORD='synthetic')

    def test_mysql_requires_all_credentials_and_never_falls_back_to_demo(self):
        with self.assertRaisesRegex(RuntimeError, '--mysql requires'):
            runtime.Configuration(self.args('--mysql'))
        self.mysql_env()
        os.environ['SPRING_DATASOURCE_URL'] = 'jdbc:h2:mem:wrong'
        config = runtime.Configuration(self.args('--mysql'))
        self.assertIn('--spring.profiles.active=mysql,standalone', config.app_args)
        self.assertIn('--lianzhi.demo=false', config.app_args)
        self.assertEqual(config.env['SPRING_DATASOURCE_URL'], os.environ['DB_URL'])
        self.assertNotIn('synthetic', ' '.join(map(str, config.app_args)))
        self.assertFalse(config.folder.exists())

    def test_mysql_rejects_h2_and_classroom_reuse(self):
        self.mysql_env()
        os.environ['DB_URL'] = 'jdbc:h2:mem:wrong'
        with self.assertRaisesRegex(RuntimeError, '--mysql requires'):
            runtime.Configuration(self.args('--mysql'))
        with self.assertRaisesRegex(RuntimeError, 'cannot reuse'):
            runtime.Configuration(self.args('--mysql', '--reuse-classroom-data', str(self.folder)))

    def test_demo_refuses_inherited_production_datasource(self):
        os.environ['SPRING_DATASOURCE_URL'] = 'jdbc:mysql://127.0.0.1/production'
        with self.assertRaisesRegex(RuntimeError, 'Demo mode refuses'):
            runtime.Configuration(self.args())

    def test_literal_env_file_does_not_execute_or_interpolate(self):
        env_file = self.folder / '.env'
        payload = '$(touch should-not-exist); `echo secret`'
        env_file.write_text('DB_PASSWORD="' + payload + '"\nPATH=malicious\nLIANZHI_AI_MODEL=qwen2.5:1.5b\n', encoding='utf-8')
        env = runtime.load_environment(env_file)
        self.assertEqual(env['DB_PASSWORD'], payload)
        self.assertNotIn('PATH', env)
        self.assertFalse((self.folder / 'should-not-exist').exists())

    def test_custom_ports_and_text_only_clear_inherited_media_endpoints(self):
        os.environ.update(LIANZHI_OCR_URL='http://old/ocr', LIANZHI_OCR_TOKEN='old', LIANZHI_TRANSCRIPTION_URL='http://old/transcribe')
        config = runtime.Configuration(self.args('--port', '19080', '--ollama-port', '19439'))
        self.assertEqual(config.env['OLLAMA_HOST'], '127.0.0.1:19439')
        self.assertEqual(config.env['LIANZHI_AI_BASE_URL'], 'http://127.0.0.1:19439')
        self.assertEqual(config.env['LIANZHI_OCR_URL'], '')
        self.assertEqual(config.env['LIANZHI_OCR_TOKEN'], '')
        self.assertEqual(config.env['LIANZHI_TRANSCRIPTION_URL'], '')
        self.assertIn('--lianzhi.demo=true', config.app_args)

    def test_duplicate_and_out_of_range_ports_are_rejected(self):
        for extra in [('--port', '11439'), ('--port', '0'), ('--ollama-port', '65536')]:
            with self.subTest(extra=extra), self.assertRaisesRegex(RuntimeError, 'ports must be distinct'):
                runtime.Configuration(self.args(*extra))

    def test_port_conflict_does_not_start_or_stop_an_existing_listener(self):
        import socket
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            config = runtime.Configuration(self.args('--port', str(listener.getsockname()[1])))
            services = runtime.Services(config)
            with self.assertRaises(OSError):
                services.__enter__()
            self.assertEqual(services.children, {})
            self.assertGreater(listener.fileno(), 0)
            self.assertFalse(config.folder.exists())

    def test_only_the_backend_inherits_database_redis_and_sdk_credentials(self):
        env={'DB_PASSWORD':'db-private','LIANZHI_REDIS_PASSWORD':'redis-private','LIANZHI_ARCSOFT_SDK_KEY':'sdk-private',
             'ADMIN_INITIAL_PASSWORD':'admin-private','LIANZHI_MEDIA_TOKEN':'media-private','OLLAMA_MODELS':'models','PATH':'runtime'}
        self.assertEqual(runtime.service_environment(env,'application'),env)
        model=runtime.service_environment(env,'ollama')
        self.assertEqual(model,{'OLLAMA_MODELS':'models','PATH':'runtime'})
        media=runtime.service_environment(env,'media')
        self.assertEqual(media,{'LIANZHI_MEDIA_TOKEN':'media-private','OLLAMA_MODELS':'models','PATH':'runtime'})
        self.assertIn('DB_PASSWORD',env)


if __name__ == '__main__':
    unittest.main()
