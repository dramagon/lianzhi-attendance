#!/usr/bin/env python3
"""Disposable, loopback-only MySQL verification. Never connects to an existing database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import xml.etree.ElementTree as ET
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'mysql:8.4@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven-settings', type=Path)
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--face-candidate', action='store_true', help='Also verify candidate V4/V5 and face business flow in another disposable database, with a test-only SDK simulator')
    args = parser.parse_args()
    if not shutil.which('docker'):
        raise RuntimeError('Docker is required; no database was changed.')
    run_id = uuid.uuid4().hex[:12]
    name = 'lianzhi-b-mysql-' + run_id
    folder = ROOT / 'artifacts' / ('mysql-b-' + run_id)
    folder.mkdir(parents=True)
    folder.chmod(0o700)
    env_file = folder / 'mysql.env'
    password = secrets.token_hex(24)
    env_file.write_text('MYSQL_ROOT_PASSWORD=' + secrets.token_hex(32) + '\nMYSQL_DATABASE=lianzhi_b_verify\nMYSQL_USER=b_verify\nMYSQL_PASSWORD=' + password + '\n')
    env_file.chmod(0o600)
    def command(argv, **kwargs):
        return subprocess.check_output(argv, text=True, stderr=subprocess.STDOUT, **kwargs).strip()
    def sql(query):
        return command(['docker', 'exec', '-i', name, 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h127.0.0.1 -uroot --batch --raw --skip-column-names lianzhi_b_verify'], input=query)
    def ready():
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                if sql('SELECT 1;') == '1':
                    return
            except subprocess.CalledProcessError:
                pass
            time.sleep(1)
        raise RuntimeError('MySQL readiness timed out; see container.log')
    created = False
    try:
        print('Starting isolated MySQL; report directory:', folder, flush=True)
        command(['docker', 'run', '-d', '--name', name, '--env-file', str(env_file), '-p', '127.0.0.1::3306', IMAGE])
        created = True
        ready()
        env = os.environ.copy()
        env.update(
                   SPRING_DATASOURCE_USERNAME='b_verify', SPRING_DATASOURCE_PASSWORD=password,
                   SPRING_PROFILES_ACTIVE='test')
        def configure_endpoint(database='lianzhi_b_verify'):
            # Docker may allocate a different random host port after restart.
            port = command(['docker', 'port', name, '3306/tcp']).rsplit(':', 1)[1]
            deadline = time.monotonic() + 30
            while True:
                try:
                    with socket.create_connection(('127.0.0.1', int(port)), timeout=1):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Published MySQL port is not reachable')
                    time.sleep(0.5)
            url = 'jdbc:mysql://127.0.0.1:' + port + '/' + database + '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
            env.update(SPRING_DATASOURCE_URL=url, LIANZHI_ATTENDANCE_TEST_URL=url)
            return url
        configure_endpoint()
        maven = ['cmd', '/c', 'mvnw.cmd'] if os.name == 'nt' else ['sh', './mvnw']
        maven += ['-B', '-ntp']
        if args.maven_settings:
            maven += ['-s', str(args.maven_settings.resolve())]
        if args.maven_repo:
            maven += ['-Dmaven.repo.local=' + str(args.maven_repo.resolve())]
        def tests(classes, filename):
            invocation_started = time.time()
            with (folder / filename).open('w') as output:
                result = subprocess.run(maven + ['-Dtest=' + classes, 'test'], cwd=ROOT / 'backend', env=env,
                               stdout=output, stderr=subprocess.STDOUT, check=False)
            # Preserve current-run XML before the next Maven invocation.
            results = folder / filename.replace('.log', '-results')
            results.mkdir()
            for item in (ROOT / 'backend/target/surefire-reports').glob('TEST-*.xml'):
                if item.stat().st_mtime >= invocation_started:
                    shutil.copy2(item, results / item.name)
            result.check_returncode()
            total = 0
            for item in results.glob('TEST-*.xml'):
                root = ET.parse(item).getroot()
                if any(int(root.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
                    raise RuntimeError('A selected MySQL test did not pass: ' + item.name)
                total += int(root.get('tests', '0'))
            if not total:
                raise RuntimeError('No selected tests executed')
            return total
        main_count = tests('AttendanceWorkflowTest,AttendanceApiTest,AttendanceMysqlIT', 'mysql-tests.log')
        snapshot = '''SELECT id,user_id,workplace_id,business_date,event_type,occurred_at,request_id,qr_nonce,distance_meters FROM attendance_event ORDER BY id;
SELECT user_id,business_date,workplace_id,rule_json,holiday,created_by FROM attendance_schedule ORDER BY user_id,business_date;
SELECT approval_id,user_id,company_id,draft_json,approver_id,approved_at FROM attendance_adjustment ORDER BY approval_id;'''
        before = hashlib.sha256(sql(snapshot).encode()).hexdigest()
        command(['docker', 'restart', name])
        ready()
        after = hashlib.sha256(sql(snapshot).encode()).hexdigest()
        if before != after:
            raise RuntimeError('Database contents changed across MySQL restart')
        configure_endpoint()
        restart_count = tests('AttendanceMysqlRestartIT', 'mysql-restart.log')
        report = {'mysql_test_count': main_count, 'restart_test_count': restart_count, 'mysql_version': sql('SELECT VERSION();'), 'image': IMAGE,
                  'migration_versions': sql('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank;').splitlines(),
                  'events': int(sql('SELECT COUNT(*) FROM attendance_event;')),
                  'schedules': int(sql('SELECT COUNT(*) FROM attendance_schedule;')),
                  'adjustments': int(sql('SELECT COUNT(*) FROM attendance_adjustment;')),
                  'restart_fingerprint': after, 'restart_unchanged': True}
        if args.face_candidate:
            # Deliberately separate from the V1/V2/V3 business/restart database above.
            sql("CREATE DATABASE lianzhi_b_face_verify CHARACTER SET utf8mb4; GRANT ALL ON lianzhi_b_face_verify.* TO 'b_verify'@'%';")
            env['LIANZHI_FACE_TEST_URL'] = configure_endpoint('lianzhi_b_face_verify')
            report['face_candidate_test_count'] = tests('FaceWorkflowTest', 'face-candidate.log')
            report['face_candidate_migration_versions'] = sql('SELECT version FROM lianzhi_b_face_verify.flyway_schema_history WHERE success=1 ORDER BY installed_rank;').splitlines()
            report['face_test_provider'] = 'TEST_ONLY_SIMULATOR_NOT_REAL_RECOGNITION'
        (folder / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    finally:
        if created:
            with (folder / 'container.log').open('w') as output:
                subprocess.run(['docker', 'logs', name], stdout=output, stderr=subprocess.STDOUT)
            # Only the exact container created by this invocation and its disposable volume.
            subprocess.run(['docker', 'rm', '-f', '-v', name], stdout=subprocess.DEVNULL, check=True)
        env_file.unlink(missing_ok=True)
        print('Disposable container removed; logs retained:', folder, flush=True)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
