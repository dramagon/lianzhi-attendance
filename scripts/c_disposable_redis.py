"""Own a real isolated Redis server and speak RESP; no system service or shared DB is modified."""
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time

from c_disposable_mysql import free_port


class RedisClient:
    def __init__(self, host, port, password='', username=''):
        self.host, self.port, self.password, self.username = host, port, password, username

    @staticmethod
    def _send(connection, parts):
        encoded = [part if isinstance(part, bytes) else str(part).encode() for part in parts]
        connection.sendall(b'*' + str(len(encoded)).encode() + b'\r\n' +
                           b''.join(b'$' + str(len(p)).encode() + b'\r\n' + p + b'\r\n' for p in encoded))

    @staticmethod
    def _read(stream):
        marker = stream.read(1)
        if not marker:
            raise EOFError('Redis connection closed')
        line = stream.readline()
        if not line.endswith(b'\r\n'):
            raise RuntimeError('Invalid Redis response')
        value = line[:-2]
        if marker == b'+': return value
        if marker == b'-': raise RuntimeError('Redis rejected command: ' + value.decode(errors='replace'))
        if marker == b':': return int(value)
        if marker == b'$':
            size = int(value)
            if size == -1: return None
            data = stream.read(size)
            if len(data) != size or stream.read(2) != b'\r\n': raise RuntimeError('Truncated Redis response')
            return data
        if marker == b'*':
            size = int(value)
            return None if size == -1 else [RedisClient._read(stream) for _ in range(size)]
        raise RuntimeError('Unsupported Redis response')

    def command(self, *parts):
        with socket.create_connection((self.host, self.port), timeout=3) as connection:
            with connection.makefile('rb') as stream:
                if self.password:
                    credentials = ('AUTH', self.username, self.password) if self.username else ('AUTH', self.password)
                    self._send(connection, credentials)
                    self._read(stream)
                self._send(connection, parts)
                return self._read(stream)

    def keys(self, pattern):
        cursor, found = b'0', []
        while True:
            cursor, batch = self.command('SCAN', cursor, 'MATCH', pattern, 'COUNT', 100)
            found.extend(batch)
            if cursor == b'0': return found


def linux_path(path):
    path = path.resolve()
    if os.name != 'nt': return str(path)
    if len(path.drive) != 2 or path.drive[1] != ':': raise RuntimeError('WSL verification needs a local drive path')
    return '/mnt/' + path.drive[0].lower() + '/' + '/'.join(path.parts[1:])


class DisposableRedis:
    def __init__(self, executable, folder, distro=None, library_directory=None, image=None):
        self.executable, self.folder = executable, folder.resolve()
        self.distro, self.library_directory = distro, library_directory
        self.image, self.container = image, None
        self.port, self.password = free_port(), secrets.token_hex(24)
        self.client = RedisClient('127.0.0.1', self.port, self.password)
        self.process = self.log = None

    def __enter__(self):
        self.folder.mkdir(parents=True, exist_ok=False)
        self.config = self.folder / ('redis.env' if self.image else 'redis.conf')
        self.pid_file = self.folder / 'redis.pid'
        contents = ('bind 127.0.0.1\nprotected-mode yes\nport ' + str(self.port) +
                '\nrequirepass ' + self.password + '\nsave ""\nappendonly yes\nappendfsync always\n'
                'dir "' + (linux_path(self.folder) if self.distro else self.folder.as_posix()) + '"\n'
                'pidfile "' + (linux_path(self.pid_file) if self.distro else self.pid_file.as_posix()) +
                '"\nlogfile ""\ndaemonize no\n')
        self.config.write_text('LIANZHI_REDIS_PASSWORD=' + self.password + '\n' if self.image else contents, encoding='utf-8')
        self.config.chmod(0o600)
        self.log = (self.folder / 'redis.log').open('ab')
        try:
            self.start()
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def start(self):
        argv = [self.executable, linux_path(self.config) if self.distro else str(self.config)]
        env = os.environ.copy()
        if self.image:
            self.container = 'lianzhi-redis-verify-' + secrets.token_hex(6)
            argv = ['docker','run','--rm','--name',self.container,'--env-file',str(self.config),
                    '--user',(str(os.getuid())+':'+str(os.getgid()) if hasattr(os,'getuid') else '1000:1000'),'-p','127.0.0.1:'+str(self.port)+':6379',
                    '-v',str(self.folder)+':/data','-v',str(Path(__file__).resolve().parents[1]/'deploy/redis-start.sh')+':/opt/lianzhi/redis-start.sh:ro',
                    self.image,'sh','/opt/lianzhi/redis-start.sh']
        elif self.distro:
            argv = ['wsl.exe', '-d', self.distro, '--', 'env'] + (
                    ['LD_LIBRARY_PATH=' + self.library_directory] if self.library_directory else []) + argv
        elif self.library_directory:
            env['LD_LIBRARY_PATH'] = self.library_directory
        self.process = subprocess.Popen(argv, env=env, stdout=self.log, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        deadline = time.monotonic() + (120 if self.image else 45)
        while time.monotonic() < deadline:
            if self.process.poll() is not None: raise RuntimeError('Owned Redis exited; inspect its isolated log')
            try:
                if self.client.command('PING') == b'PONG': return
            except (OSError, RuntimeError, EOFError): time.sleep(.5)
        raise RuntimeError('Owned Redis readiness timed out; inspect its isolated log')

    def stop(self):
        if self.process is None or self.process.poll() is not None: return
        try:
            self.client.command('SHUTDOWN', 'SAVE')
        except (OSError, EOFError): pass
        try:
            self.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            if self.image:
                assert self.container.startswith('lianzhi-redis-verify-')
                subprocess.run(['docker','stop',self.container],stdout=subprocess.DEVNULL,check=True,timeout=20)
            elif self.distro and self.pid_file.is_file():
                # Check both the generated PID file and /proc command line before signalling an owned WSL process.
                code = "import pathlib,os,signal; p=pathlib.Path(__import__('sys').argv[1]); pid=int(p.read_text()); args=pathlib.Path('/proc/'+str(pid)+'/cmdline').read_bytes(); expected=__import__('sys').argv[2].encode(); assert expected in args; os.kill(pid,signal.SIGTERM)"
                subprocess.run(['wsl.exe','-d',self.distro,'--','python3','-c',code,linux_path(self.pid_file),linux_path(self.config)],check=True,timeout=10)
            else:
                self.process.terminate()
            self.process.wait(timeout=15)

    def __exit__(self, *_):
        try: self.stop()
        finally:
            self.config.unlink(missing_ok=True)
            if self.log: self.log.close()
