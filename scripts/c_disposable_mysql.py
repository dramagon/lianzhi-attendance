"""An owned loopback MySQL instance, never the user's installed MySQL service."""
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class DisposableMysql:
    def __init__(self, binary_directory, folder):
        self.bin = binary_directory.resolve()
        self.folder = folder.resolve()
        suffix = '.exe' if os.name == 'nt' else ''
        self.mysql, self.mysqld, self.admin = [self.bin / (name + suffix) for name in ('mysql', 'mysqld', 'mysqladmin')]
        if not self.mysqld.is_file() and os.name != 'nt':
            self.mysqld = Path(shutil.which('mysqld') or self.bin.parent / 'sbin/mysqld')
        if not all(p.is_file() for p in (self.mysql, self.mysqld, self.admin)):
            raise RuntimeError('MySQL server and client executables are required; no database was touched')
        self.port = free_port()
        self.password = secrets.token_hex(24)
        self.root_password = secrets.token_hex(24)
        self.config = self.folder / 'mysql-client.cnf'
        self.process = None
        self.log = None
        self.runtime = None

    def __enter__(self):
        self.folder.mkdir(parents=True, exist_ok=False)
        self.data = self.folder / 'data'
        self.data.mkdir()
        self.log = (self.folder / 'mysql.log').open('ab')
        if os.name != 'nt':
            self.runtime = tempfile.TemporaryDirectory(prefix='lianzhi-mysql-')
        try:
            subprocess.run([str(self.mysqld), '--no-defaults', '--initialize-insecure',
                            '--basedir=' + str(self.bin.parent), '--datadir=' + str(self.data), '--console'],
                           stdout=self.log, stderr=subprocess.STDOUT, check=True, timeout=120)
            self.start(initial=True)
            self.sql("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + self.root_password + "'; "
                     "CREATE DATABASE c_verify CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; "
                     "CREATE USER 'c_verify'@'localhost' IDENTIFIED BY '" + self.password + "'; "
                     "GRANT ALL PRIVILEGES ON c_verify.* TO 'c_verify'@'localhost';", initial=True)
            self.config.write_text('[client]\nuser=root\npassword=' + self.root_password +
                                   '\nhost=127.0.0.1\nport=' + str(self.port) + '\nprotocol=TCP\n', encoding='utf-8')
            self.config.chmod(0o600)
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def sql(self, query, initial=False):
        options = ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=' + str(self.port), '--user=root'] if initial else ['--defaults-extra-file=' + str(self.config)]
        result = subprocess.run([str(self.mysql)] + options + ['--default-character-set=utf8mb4', '--batch', '--skip-column-names'],
                                input=query.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        if result.returncode:
            raise RuntimeError('Isolated MySQL command failed; no credentials or SQL diagnostics are printed')
        return result.stdout.decode('utf-8').strip()

    def start(self, initial=False):
        flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        # Distro defaults point at system-owned /run; use our own short Unix socket path.
        runtime_options = ([] if self.runtime is None else
                           ['--socket=' + str(Path(self.runtime.name) / 'mysql.sock'),
                            '--pid-file=' + str(Path(self.runtime.name) / 'mysql.pid')])
        self.process = subprocess.Popen([str(self.mysqld), '--no-defaults', '--basedir=' + str(self.bin.parent),
                                         '--datadir=' + str(self.data), '--bind-address=127.0.0.1', '--port=' + str(self.port),
                                         '--mysqlx=OFF', '--skip-log-bin', '--innodb-buffer-pool-size=64M',
                                         '--max-connections=30', '--console'] + runtime_options,
                                        stdout=self.log, stderr=subprocess.STDOUT, creationflags=flags)
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError('Isolated MySQL exited; inspect ' + str(self.folder / 'mysql.log'))
            try:
                if self.sql('SELECT 1', initial) == '1':
                    return
            except RuntimeError:
                time.sleep(.5)
        raise RuntimeError('Isolated MySQL readiness timed out')

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            if self.config.is_file():
                result = subprocess.run([str(self.admin), '--defaults-extra-file=' + str(self.config), 'shutdown'],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
                if result.returncode == 0:
                    self.process.wait(timeout=30)
            if self.process.poll() is None:
                self.process.terminate()
                self.process.wait(timeout=15)

    def __exit__(self, *_):
        try:
            self.stop()
        finally:
            self.config.unlink(missing_ok=True)
            if self.log:
                self.log.close()
            if self.runtime:
                self.runtime.cleanup()
        # Retain this run's synthetic database and logs for inspection; never delete user data.
