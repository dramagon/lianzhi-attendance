#!/usr/bin/env python3
"""Launch real local AI and the same-origin application; stop only owned processes."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import webbrowser

import psutil

ROOT = Path(__file__).resolve().parents[2]
SUFFIX = '.exe' if os.name == 'nt' else ''


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port', type=int, default=8088)
    p.add_argument('--no-browser', action='store_true')
    p.add_argument('--text-only', action='store_true', help='Chat and embeddings without OCR/transcription')
    p.add_argument('--mysql', action='store_true', help='Use DB_URL/DB_USERNAME/DB_PASSWORD; never create demo accounts')
    p.add_argument('--env-file', type=Path, help='Literal local settings, never executed as code')
    p.add_argument('--runtime-directory', type=Path, help='Installed runtime/model directory; defaults to project artifacts')
    p.add_argument('--data-directory', type=Path, help='Application working directory, logs and avatars')
    p.add_argument('--jar', type=Path, help='Explicit verified same-origin application JAR')
    p.add_argument('--java', type=Path, help='Java 17+ executable')
    p.add_argument('--ollama', type=Path, help='Ollama executable (Windows or Linux)')
    p.add_argument('--media-python', type=Path, help='Python environment with media dependencies installed')
    p.add_argument('--ollama-port', type=int, default=11439)
    p.add_argument('--media-port', type=int, default=11440)
    p.add_argument('--check', action='store_true', help='Validate configuration without starting or writing data')
    p.add_argument('--reuse-classroom-data', type=Path, help='Preserve the existing classroom H2 database')
    return p


def load_environment(path):
    env = os.environ.copy()
    if path:
        for line in path.read_text('utf-8-sig').splitlines():
            match = re.fullmatch(r'\s*([A-Z][A-Z0-9_]*)\s*=(.*)', line)
            if not match:
                continue
            key, value = match.groups()
            if not (key.startswith(('LIANZHI_', 'DB_', 'OLLAMA_')) or
                    key in {'JAVA_HOME', 'ADMIN_INITIAL_PASSWORD', 'SESSION_COOKIE_SECURE'}):
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            env[key] = value  # Literal values: no shell execution or secret logging.
    return env


def executable(path, label):
    path = Path(path).expanduser().resolve()
    if not path.is_file():
        raise RuntimeError(label + ' was not found; see c-media-services.md')
    return path


def service_environment(env, name):
    child = env.copy()
    if name != 'application':
        for key in list(child):
            if key.startswith(('DB_', 'SPRING_DATASOURCE_', 'LIANZHI_REDIS_', 'LIANZHI_ARCSOFT_', 'LIANZHI_FACE_')) or key in {
                    'ADMIN_INITIAL_PASSWORD', 'LIANZHI_OCR_TOKEN', 'LIANZHI_TRANSCRIPTION_TOKEN'}:
                child.pop(key)
        if name != 'media':
            child.pop('LIANZHI_MEDIA_TOKEN', None)
    return child


def java_executable(explicit, env):
    candidates = [explicit] if explicit else []
    if not explicit:
        if env.get('JAVA_HOME'):
            candidates.append(Path(env['JAVA_HOME']) / ('bin/java' + SUFFIX))
        if shutil.which('java', path=env.get('PATH')):
            candidates.append(Path(shutil.which('java', path=env.get('PATH'))))
        candidates += list((Path.home() / '.jdks').glob('*/bin/java' + SUFFIX))
        if os.name == 'nt':
            candidates += list((Path(env.get('ProgramFiles', 'C:/Program Files')) / 'Java').glob('jdk-*/bin/java.exe'))
    for candidate in candidates:
        if candidate and candidate.is_file():
            version = subprocess.run([str(candidate), '--version'], capture_output=True, text=True, timeout=10)
            match = re.search(r'\b(\d+)[. ]', version.stdout)
            if version.returncode == 0 and match and int(match[1]) >= 17:
                return candidate.resolve()
    raise RuntimeError('Java 17 or newer is required; set JAVA_HOME or --java')


class Configuration:
    def __init__(self, args):
        self.args = args
        self.env = load_environment(args.env_file)
        env = self.env
        self.runtime = (args.runtime_directory or Path(env.get('LIANZHI_RUNTIME_DIRECTORY', ROOT / 'artifacts'))).resolve()
        if args.jar:
            self.jar = args.jar.resolve()
        else:
            latest = ROOT / 'artifacts/c-package-latest.json'
            if not latest.is_file():
                raise RuntimeError('Build first: python scripts/build-c-package.py, or set --jar')
            self.jar = (Path(json.loads(latest.read_text('utf-8'))['distribution']) / 'lianzhi-c.jar').resolve()
            if not self.jar.is_relative_to((ROOT / 'artifacts').resolve()):
                raise RuntimeError('Invalid default package path; use --jar for an explicit external package')
        executable(self.jar, 'Application JAR')
        self.java = java_executable(args.java, env)
        self.ollama = executable(args.ollama or env.get('LIANZHI_OLLAMA_EXECUTABLE') or
                                 self.runtime / ('ollama-cpu/ollama' + SUFFIX), 'Ollama')
        default_data = ROOT / 'artifacts' / ('c-mysql-data' if args.mysql else 'c-full-data')
        self.folder = (args.data_directory or Path(env.get('LIANZHI_DATA_DIRECTORY', default_data))).resolve()
        if args.mysql and args.reuse_classroom_data:
            raise RuntimeError('--mysql cannot reuse an H2 classroom database')
        if args.reuse_classroom_data:
            self.folder = args.reuse_classroom_data.resolve()
            if not self.folder.is_relative_to((ROOT / 'artifacts').resolve()) or not (self.folder / 'demo-data/lianzhi.mv.db').is_file():
                raise RuntimeError('Expected an existing classroom database inside project artifacts')
        if args.mysql:
            if not env.get('DB_URL', '').startswith('jdbc:mysql://') or not env.get('DB_USERNAME') or not env.get('DB_PASSWORD'):
                raise RuntimeError('--mysql requires DB_URL (jdbc:mysql://), DB_USERNAME and DB_PASSWORD in the environment/local env file')
            env.update(SPRING_DATASOURCE_URL=env['DB_URL'], SPRING_DATASOURCE_USERNAME=env['DB_USERNAME'],
                       SPRING_DATASOURCE_PASSWORD=env['DB_PASSWORD'])
        else:
            if env.get('SPRING_DATASOURCE_URL') and not env['SPRING_DATASOURCE_URL'].startswith('jdbc:h2:'):
                raise RuntimeError('Demo mode refuses an inherited non-H2 datasource; use --mysql explicitly')
        ports = [args.ollama_port, args.port] + ([] if args.text_only else [args.media_port])
        if any(not 1 <= port <= 65535 for port in ports) or len(set(ports)) != len(ports):
            raise RuntimeError('Application, Ollama and media ports must be distinct and between 1 and 65535')
        self.origin = 'http://127.0.0.1:' + str(args.port)
        self.model_origin = 'http://127.0.0.1:' + str(args.ollama_port)
        self.media_origin = 'http://127.0.0.1:' + str(args.media_port)
        self.chat_model = env.get('LIANZHI_AI_MODEL') or 'qwen2.5:1.5b'
        self.embedding_model = env.get('LIANZHI_EMBEDDING_MODEL') or 'qwen3-embedding:0.6b'
        self.whisper = Path(env.get('LIANZHI_WHISPER_MODEL', self.runtime / 'local-ai/whisper-tiny')).resolve()
        self.media_python = None
        if not args.text_only:
            python_name = 'Scripts/python.exe' if os.name == 'nt' else 'bin/python'
            python_candidates = [self.runtime / name / python_name for name in ('c-media-venv', 'c-ai-venv')]
            self.media_python = executable(args.media_python or env.get('LIANZHI_MEDIA_PYTHON') or
                                           next((p for p in python_candidates if p.is_file()), sys.executable), 'Media Python')
            ffmpeg = Path(env.get('LIANZHI_FFMPEG_DIRECTORY', self.runtime / 'ffmpeg/bin'))
            if ffmpeg.is_dir():
                env['PATH'] = str(ffmpeg) + os.pathsep + env.get('PATH', '')
            if not all(shutil.which(name, path=env.get('PATH')) for name in ('ffmpeg', 'ffprobe')) or not (self.whisper / 'model.bin').is_file():
                raise RuntimeError('Missing FFmpeg/ffprobe or Whisper model; use --text-only to omit media services')
        token = secrets.token_hex(32)
        env.update(OLLAMA_HOST='127.0.0.1:' + str(args.ollama_port),
                   OLLAMA_MODELS=env.get('OLLAMA_MODELS') or str(self.runtime / 'local-ai/ollama-models'),
                   OLLAMA_NUM_PARALLEL=env.get('OLLAMA_NUM_PARALLEL', '1'),
                   OLLAMA_MAX_LOADED_MODELS=env.get('OLLAMA_MAX_LOADED_MODELS', '2'),
                   OLLAMA_CONTEXT_LENGTH=env.get('OLLAMA_CONTEXT_LENGTH', '8192'),
                   LIANZHI_AI_BASE_URL=self.model_origin, LIANZHI_AI_MODEL=self.chat_model,
                   LIANZHI_AI_TIMEOUT_SECONDS=env.get('LIANZHI_AI_TIMEOUT_SECONDS') or '60',
                   LIANZHI_EMBEDDING_BASE_URL=self.model_origin, LIANZHI_EMBEDDING_MODEL=self.embedding_model,
                   LIANZHI_EMBEDDING_REVISION=env.get('LIANZHI_EMBEDDING_REVISION') or '1', LIANZHI_MEDIA_TOKEN=token,
                   LIANZHI_OCR_URL='' if args.text_only else self.media_origin + '/ocr',
                   LIANZHI_TRANSCRIPTION_URL='' if args.text_only else self.media_origin + '/transcribe',
                   LIANZHI_OCR_TOKEN='' if args.text_only else token, LIANZHI_TRANSCRIPTION_TOKEN='' if args.text_only else token)
        self.app_args = [self.java, '-jar', self.jar,
                         '--spring.profiles.active=' + ('mysql,standalone' if args.mysql else 'demo,standalone'),
                         '--lianzhi.demo=' + ('false' if args.mysql else 'true'),
                         '--server.address=127.0.0.1', '--server.port=' + str(args.port)]
        if args.reuse_classroom_data:
            self.app_args += ['--spring.datasource.url=jdbc:h2:file:./demo-data/lianzhi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;OPTIMIZE_REUSE_RESULTS=FALSE',
                              '--lianzhi.avatar-directory=./demo-data/avatars']


class Services:
    """Owned process lifecycle, also used by the isolated real-MySQL verifier."""
    def __init__(self, config):
        self.config = config
        self.children = {}
        self.handles = []

    def __enter__(self):
        args = self.config.args
        for port in [args.port, args.ollama_port] + ([] if args.text_only else [args.media_port]):
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', port))
        self.config.folder.mkdir(parents=True, exist_ok=True)
        self.logs = self.config.folder / ('run-' + time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3))
        self.logs.mkdir()
        try:
            self.start_model()
            if not args.text_only:
                self.start([self.config.media_python, ROOT / 'tools/c-media/server.py', '--port', str(args.media_port),
                            '--model', self.config.whisper], 'media')
                self.wait(self.config.media_origin + '/health', 'media')
            self.start_app()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def start(self, argv, name):
        if name in self.children and self.children[name].poll() is None:
            raise RuntimeError('Service already started: ' + name)
        handle = (self.logs / (name + '.log')).open('a', encoding='utf-8')
        self.handles.append(handle)
        self.children[name] = subprocess.Popen([str(a) for a in argv], cwd=self.config.folder, env=service_environment(self.config.env, name),
                                              stdout=handle, stderr=subprocess.STDOUT,
                                              creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

    def wait(self, url, name):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.children[name].poll() is not None:
                raise RuntimeError('Service exited: ' + name + '; inspect ' + str(self.logs))
            try:
                with urllib.request.urlopen(url, timeout=2) as response:
                    if response.status == 200:
                        return
            except (OSError, TimeoutError):
                time.sleep(.5)
        raise RuntimeError('Service startup timed out: ' + name + '; inspect ' + str(self.logs))

    def start_model(self):
        self.start([self.config.ollama, 'serve'], 'ollama')
        self.wait(self.config.model_origin + '/api/tags', 'ollama')
        with urllib.request.urlopen(self.config.model_origin + '/api/tags', timeout=5) as response:
            models = {m['name'] for m in json.load(response)['models']}
        if not {self.config.chat_model, self.config.embedding_model} <= models:
            raise RuntimeError('Required chat/embedding models have not been downloaded')

    def start_app(self):
        self.start(self.config.app_args, 'application')
        self.wait(self.config.origin + '/api/auth/csrf', 'application')

    def stop(self, name):
        child = self.children.get(name)
        if child is None or child.poll() is not None:
            return
        try:
            process = psutil.Process(child.pid)
            descendants = process.children(recursive=True)
            for descendant in reversed(descendants):
                try:
                    descendant.terminate()
                except psutil.NoSuchProcess:
                    pass
            process.terminate()
            _, alive = psutil.wait_procs(descendants + [process], timeout=10)
            for remaining in alive:
                remaining.kill()
            child.wait(timeout=10)
        except psutil.NoSuchProcess:
            pass

    def __exit__(self, *_):
        for name in reversed(self.children):
            self.stop(name)
        for handle in self.handles:
            handle.close()


def main():
    args = parser().parse_args()
    config = Configuration(args)
    if args.check:
        print('Configuration valid | database=' + ('MySQL (non-demo)' if args.mysql else 'H2 demo') +
              ' | media=' + str(not args.text_only) + ' | no services started; reachability not verified')
        return
    try:
        with Services(config) as services:
            print('READY ' + config.origin + ' | ' + ('MySQL' if args.mysql else 'H2 demo') +
                  ' | Real local models' + ('' if args.text_only else ' / OCR / transcription') + '. Ctrl+C stops this run.', flush=True)
            if not args.mysql:
                print('Demo accounts: admin / hr / employee; password: Lianzhi@2026. Local synthetic data only.', flush=True)
            if not args.no_browser:
                webbrowser.open(config.origin)
            while all(child.poll() is None for child in services.children.values()):
                time.sleep(.5)
            raise RuntimeError('A service stopped; inspect ' + str(services.logs))
    except KeyboardInterrupt:
        print('Stopping this launch; local data is retained.', flush=True)


if __name__ == '__main__':
    main()
