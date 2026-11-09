"""A uniquely named, loopback-only MySQL container for CI integration checks."""
from pathlib import Path
import secrets
import subprocess
import time

from c_disposable_mysql import free_port


class DisposableMysqlContainer:
    def __init__(self, image, folder):
        self.image, self.folder = image, folder.resolve()
        self.container = 'lianzhi-mysql-verify-' + secrets.token_hex(6)
        self.port, self.password = free_port(), secrets.token_hex(24)
        self.root_password = secrets.token_hex(24)
        self.created = False

    def __enter__(self):
        self.folder.mkdir(parents=True, exist_ok=False)
        self.data = self.folder / 'data'
        self.data.mkdir()
        self.config, self.environment = self.folder / 'client.cnf', self.folder / 'mysql.env'
        self.config.write_text('[client]\nuser=root\npassword=' + self.root_password + '\n', encoding='utf-8')
        self.environment.write_text('MYSQL_ROOT_PASSWORD=' + self.root_password +
                                    '\nMYSQL_DATABASE=c_verify\nMYSQL_USER=c_verify\nMYSQL_PASSWORD=' +
                                    self.password + '\n', encoding='utf-8')
        for path in (self.config, self.environment): path.chmod(0o600)
        try:
            with (self.folder / 'mysql-start.log').open('wb') as log:
                # Mark ownership before creation so a timeout can still clean up our unique name.
                self.created = True
                subprocess.run(['docker', 'run', '-d', '--name', self.container,
                                '--env-file', str(self.environment), '-p', '127.0.0.1:' + str(self.port) + ':3306',
                                '-v', str(self.data) + ':/var/lib/mysql', self.image,
                                '--mysqlx=OFF', '--skip-log-bin', '--innodb-buffer-pool-size=64M',
                                '--max-connections=30'], stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=180)
                # Desktop bind mounts may expose NTFS files as world-writable, which MySQL ignores.
                # Copy the secret into the owned container and set its actual Linux permissions.
                subprocess.run(['docker', 'cp', str(self.config), self.container + ':/run/lianzhi-client.cnf'],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
                subprocess.run(['docker', 'exec', self.container, 'chmod', '600', '/run/lianzhi-client.cnf'],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=15)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                try:
                    if self.sql('SELECT 1') == '1':
                        # The entrypoint initializes through a temporary socket-only server.
                        import socket
                        with socket.create_connection(('127.0.0.1', self.port), timeout=1): return self
                except (RuntimeError, OSError): pass
                time.sleep(.5)
            raise RuntimeError('Owned MySQL container readiness timed out; inspect its isolated logs')
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def sql(self, query):
        result = subprocess.run(['docker', 'exec', '-i', self.container, 'mysql',
                                 '--defaults-extra-file=/run/lianzhi-client.cnf',
                                 '--protocol=TCP', '--host=127.0.0.1', '--port=3306',
                                 '--default-character-set=utf8mb4', '--batch', '--skip-column-names'],
                                input=query.encode('utf-8'), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=15)
        if result.returncode:
            raise RuntimeError('Owned MySQL container query failed; credentials and SQL diagnostics are withheld')
        return result.stdout.decode('utf-8').strip()

    def __exit__(self, *_):
        try:
            if self.created:
                assert self.container.startswith('lianzhi-mysql-verify-')
                with (self.folder / 'mysql.log').open('wb') as log:
                    subprocess.run(['docker', 'logs', self.container], stdout=log,
                                   stderr=subprocess.STDOUT, timeout=20)
                subprocess.run(['docker', 'rm', '-f', self.container], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=30, check=True)
                self.created = False
        finally:
            self.config.unlink(missing_ok=True)
            self.environment.unlink(missing_ok=True)
