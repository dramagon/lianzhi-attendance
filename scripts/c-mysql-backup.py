"""Back up a LiANZhi MySQL schema or restore into a newly created schema."""
import argparse
import os
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['backup', 'restore'])
    parser.add_argument('--client-config', required=True, type=Path)
    parser.add_argument('--database', required=True)
    parser.add_argument('--file', required=True, type=Path)
    args = parser.parse_args()
    database = args.database
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', database) or database.lower() in {
        'mysql', 'sys', 'information_schema', 'performance_schema',
    }:
        parser.error('Use an application database name')
    if not args.client_config.is_file():
        parser.error('MySQL client configuration file is missing')

    config = '--defaults-file=' + str(args.client_config.resolve())
    mysql = ['mysql', config, '--default-character-set=utf8mb4']
    if args.operation == 'backup':
        # Exclusive creation protects existing backups. A partial dump is never retained.
        with args.file.open('xb') as output:
            try:
                subprocess.run(
                    ['mysqldump', config, '--single-transaction', '--no-tablespaces',
                     '--set-gtid-purged=OFF', '--skip-add-drop-table', '--hex-blob',
                     '--default-character-set=utf8mb4', database],
                    stdout=output, check=True,
                )
            except BaseException:
                output.close()
                args.file.unlink()
                raise
    else:
        with args.file.open('rb') as source:
            if not os.fstat(source.fileno()).st_size:
                parser.error('Backup file is empty')
            # CREATE without IF NOT EXISTS also rejects an existing empty database.
            subprocess.run(mysql + ['--execute',
                                     f'CREATE DATABASE `{database}` CHARACTER SET utf8mb4'], check=True)
            try:
                subprocess.run(mysql + ['--binary-mode', '--database=' + database],
                               stdin=source, check=True)
            except BaseException:
                print('Restore failed. Inspect the new schema before using it.')
                raise


if __name__ == '__main__':
    main()
