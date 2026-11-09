#!/usr/bin/env python3
"""Portable package/launcher integration checks; no SDK, database or external service."""
import contextlib
import io
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('classroom_builder', ROOT/'scripts/build-classroom-demo.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class ClassroomPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='classroom package ')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root/'package with spaces'
        (self.root/'backend/target').mkdir(parents=True)
        (self.root/'frontend/dist').mkdir(parents=True)
        (self.root/'frontend/dist/index.html').write_text('<html>synthetic fixture</html>')
        (self.root/'backend/src/main').mkdir(parents=True)
        (self.root/'frontend/src').mkdir(parents=True)
        with zipfile.ZipFile(self.root/'backend/target/lianzhi-backend-0.1.0.jar', 'w') as jar:
            jar.writestr('BOOT-INF/classes/ClassroomConfiguration.class', b'fixture')
            jar.writestr('BOOT-INF/lib/example.jar', b'nested jar fixture', compress_type=zipfile.ZIP_STORED)
        (self.root/'tools').mkdir()
        shutil.copytree(ROOT/'tools/classroom', self.root/'tools/classroom')
        self.java_home = self.root/'fake java home'
        (self.java_home/'bin').mkdir(parents=True)
        self.java_version('17.0.1')
        self.env = {**os.environ, 'JAVA_HOME': str(self.java_home)}
        self.output.mkdir()
        (self.output/'demo-data').mkdir()
        (self.output/'demo-data/private.txt').write_text('never package runtime data')
        self.build()

    def java_version(self, version):
        binary = self.java_home/'bin/java'
        binary.write_text('#!/bin/sh\nif [ "$1" != "-version" ]; then exit 90; fi\nprintf \'openjdk version "'+version+'"\\n\' >&2\n')
        binary.chmod(0o755)

    def build(self):
        with patch.object(builder, 'ROOT', self.root), patch('sys.argv', ['builder', '--output', str(self.output)]), patch.object(builder.subprocess, 'check_output', side_effect=['fixture-sha\n', '']):
            with contextlib.redirect_stdout(io.StringIO()):
                builder.main()

    def check(self):
        return subprocess.run(['sh', str(self.output/'start-demo.sh'), '--check'], env=self.env, text=True, capture_output=True, timeout=10)

    def test_archive_integrity_and_no_runtime_data(self):
        with zipfile.ZipFile(self.output.with_suffix('.zip')) as archive:
            self.assertEqual(8, len(archive.namelist()))
            self.assertFalse(any('demo-data' in name for name in archive.namelist()))
            self.assertNotIn(b'\r', archive.read(self.output.name+'/checksums.sha256'))
            manifest = json.loads(archive.read(self.output.name+'/manifest.json'))
            for name, digest in manifest['fileHashes'].items():
                self.assertEqual(digest, hashlib.sha256(archive.read(self.output.name+'/'+name)).hexdigest())
            for name in ['start-demo.cmd', 'start-demo.ps1', 'check-demo.cmd']:
                contents = archive.read(self.output.name+'/'+name)
                self.assertIn(b'\r\n', contents)
                self.assertNotIn(b'\n', contents.replace(b'\r\n', b''))
        with zipfile.ZipFile(self.output/'app.jar') as jar:
            self.assertEqual(zipfile.ZIP_STORED, jar.getinfo('BOOT-INF/lib/example.jar').compress_type)
            self.assertIn('BOOT-INF/classes/static/index.html', jar.namelist())

    def test_check_passes_without_starting_java_app_or_creating_database(self):
        shutil.rmtree(self.output/'demo-data')
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('Preflight passed', result.stdout)
        self.assertFalse((self.output/'demo-data').exists())
        self.assertEqual([], list(self.output.glob('.demo-check-*')))

    def test_missing_file_stops_before_launch(self):
        (self.output/'app.jar').unlink()
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Incomplete package', result.stderr)

    def test_corrupt_jar_is_rejected_without_changing_data(self):
        with (self.output/'app.jar').open('ab') as stream:
            stream.write(b'corrupt-download')
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('checksum mismatch', result.stderr)
        self.assertEqual('never package runtime data', (self.output/'demo-data/private.txt').read_text())

    def test_old_java_is_rejected(self):
        self.java_version('1.8.0_400')
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Java 8 is too old. Java 17+ is required', result.stderr)

    def test_unreadable_java_version_is_rejected(self):
        self.java_version('unknown')
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Cannot determine Java version', result.stderr)

    def test_stale_artifact_is_rejected(self):
        source = self.root/'frontend/src/new-page.js'
        source.write_text('// changed since build')
        os.utime(source, (4102444800, 4102444800))
        with self.assertRaisesRegex(RuntimeError, 'Source is newer'):
            self.build()


if __name__ == '__main__':
    unittest.main()
