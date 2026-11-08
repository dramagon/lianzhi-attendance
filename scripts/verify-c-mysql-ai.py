#!/usr/bin/env python3
"""Opt-in real MySQL + local models. Writes only a new isolated synthetic test database."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid

from c_disposable_mysql import DisposableMysql, free_port
from c_http_verification import Client, document

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('c_full_runtime', ROOT / 'tools/c-package/run-full.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def parsed(client, identifier, ready=True):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        doc = client.get('/api/knowledge/documents/' + identifier)
        if doc['processing'] not in ('QUEUED', 'PROCESSING'):
            require(doc['processing'] == 'PARSED', 'Synthetic document parsing failed: ' + doc['processing'])
            if ready:
                require(doc['indexing'] == 'READY', 'Real embedding indexing did not complete: ' + doc['indexing'])
            return doc
        time.sleep(.5)
    raise RuntimeError('Document processing timed out')


def source_path(citation):
    return '/api/knowledge/documents/{}/sources/{}?version={}'.format(
        citation['documentId'], citation['chunk']['sequence'], citation['version'])


def hit(client, doc, query, mode='VECTOR'):
    hits = client.get('/api/knowledge/search?' + urllib.parse.urlencode({'query': query, 'mode': mode, 'limit': 50}))
    found = next((h for h in hits if h['citation']['documentId'] == doc['id']), None)
    require(found is not None, mode + ' search did not return the uploaded document')
    citation = found['citation']
    require(client.get(source_path(citation)) == citation, 'Current citation did not match the stored source')
    return citation


def verify(services, database, fixtures, proof, record):
    config = services.config
    password = config.env['ADMIN_INITIAL_PASSWORD']
    admin = Client(config.origin)
    admin.login(password)
    require(database.sql('SELECT COUNT(*) FROM c_verify.sys_user') == '1', 'Non-demo startup created extra accounts')
    versions = database.sql('SELECT version FROM c_verify.flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank').splitlines()
    require(versions == ['1', '2', '3', '4', '4.1', '5', '6'], 'Unexpected migration history')
    proof.update(database='MySQL ' + database.sql('SELECT VERSION()'), schemaVersions=versions,
                 inference='REAL_LOCAL_MODELS_NOT_PROTOCOL_FIXTURES', nonDemoBootstrap=True)
    record('schema-and-non-demo-bootstrap')
    companies = []
    users = []
    for number in (1, 2):
        name = 'C合成验收公司' + str(number)
        admin.write('/api/organizations/companies', {'name': name, 'enabled': True})
        company = next(c['id'] for c in admin.get('/api/organizations/companies')['items'] if c['name'] == name)
        companies.append(company)
        user = admin.write('/api/employees', {'username': 'cverify' + str(number), 'displayName': '合成员工' + str(number),
                           'employeeNo': 'CVERIFY' + str(number), 'role': 'EMPLOYEE', 'companyId': company,
                           'departmentId': None, 'enabled': True, 'password': password, 'workplaceIds': []})['data']
        users.append(user)
    employee = Client(config.origin)
    employee.login(password, 'cverify1')
    outsider = Client(config.origin)
    outsider.login(password, 'cverify2')
    content = document('年假规定：每年五天。员工须先提交申请，再由人事审批。')
    doc = employee.upload(companies[0], content, '合成年假制度.docx', 'PRIVATE')
    doc = parsed(employee, doc['id'])
    citation = hit(employee, doc, '年假规定')
    hit(employee, doc, '年假', 'TEXT')
    require(employee.request('/api/knowledge/documents/' + doc['id'] + '/file') == content, 'Original document changed')
    answer = employee.write('/api/assistant/ask', {'question': '年假规定：每年几天？'})['data']
    proof['answer'] = answer
    record('real-answer-observed')
    require(answer['state'] == 'ANSWERED' and ('五' in answer['text'] or '5' in answer['text']), 'Real model did not answer the synthetic policy correctly')
    require(answer['references'] and all(r['source']['documentId'] == doc['id'] and r['quote'] in r['source']['chunk']['content'] for r in answer['references']), 'Answer did not cite the authorized uploaded document')
    proof['document'] = {'id': doc['id'], 'sha256': doc['sha256'], 'source': source_path(citation)}
    record('real-chat-embedding-and-citations')
    for path in ('/api/knowledge/documents/' + doc['id'], source_path(citation)):
        outsider.request(path, expected=404)
    require(not outsider.get('/api/knowledge/search?query=' + urllib.parse.quote('年假')), 'Cross-company document search leaked a source')
    require(outsider.write('/api/assistant/ask', {'question': '年假规定：每年几天？'})['data']['state'] == 'NO_EVIDENCE', 'Cross-company AI answered from private evidence')
    employee.request('/api/assistant/ask', 'POST', b'{}', {'Content-Type': 'application/json'}, expected=403)
    employee.write('/api/assistant/ask', {'question': '问' * 501}, expected=400)
    scope_query = '?from=2026-11-01&to=2026-11-01&userId=' + str(users[1]['userId'])
    employee.request('/api/assistant/attendance' + scope_query, expected=403)
    record('tenant-isolation-and-csrf')
    proof['media'] = []
    if fixtures:
        Client(config.media_origin).request('/ocr', 'POST', b'{}', {'Content-Type': 'application/json'}, expected=401)
        for name, query, kind in [('real-ocr-fixture.png', '年假', 'PAGE'), ('real-ocr-fixture.pdf', '年假', 'PAGE'), ('real-asr-fixture.wav', '审批', 'TIME_RANGE')]:
            media = employee.upload(companies[0], (fixtures / name).read_bytes(), name, 'PRIVATE')
            media = parsed(employee, media['id'])
            reference = hit(employee, media, query)
            require(reference['chunk']['source']['kind'] == kind, 'OCR/transcription source location was invalid')
            proof['media'].append({'name': name, 'id': media['id'], 'sourceKind': kind, 'parsed': True, 'vectorReady': True})
            record('real-media-' + name)
    # An actual scheduled shift and approved leave prove shared business projections without fake punch records.
    admin.write('/api/workplaces', {'companyId': companies[0], 'name': '合成验收场所', 'latitude': 39.9,
                'longitude': 116.4, 'coordinateSystem': 'WGS84', 'radiusMeters': 100, 'enabled': True})
    place = admin.get('/api/workplaces?companyId=' + str(companies[0]))['items'][0]['id']
    user_input = {'username': 'cverify1', 'displayName': '合成员工1', 'employeeNo': 'CVERIFY1', 'role': 'EMPLOYEE',
                  'companyId': companies[0], 'departmentId': None, 'enabled': True, 'password': None, 'workplaceIds': [place]}
    admin.write('/api/employees/' + str(users[0]['userId']), user_input, method='PUT')
    employee.request('/api/employees/me', expected=401)
    employee = Client(config.origin)
    employee.login(password, 'cverify1')
    day = (datetime.now(timezone(timedelta(hours=8))) + timedelta(days=1)).date().isoformat()
    rule = {'start': '09:00', 'end': '18:00', 'breakStart': '12:00', 'breakEnd': '13:00', 'graceMinutes': 0,
            'workdays': ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']}
    admin.write('/api/attendance/schedules', {'userId': users[0]['userId'], 'businessDate': day, 'workplaceId': place, 'rule': rule, 'holiday': False})
    draft = {'type': 'LEAVE', 'startAt': day + 'T09:00:00', 'endAt': day + 'T18:00:00', 'punchType': None, 'workplaceId': None, 'reason': '合成验收'}
    intent = str(uuid.uuid4())
    before = employee.get('/api/assistant/approvals')['total']
    csrf = employee.get('/api/auth/csrf')
    date_text = datetime.fromisoformat(day).strftime('%Y年%m月%d日')
    payload = json.dumps({'instruction': '申请' + date_text + '09:00到' + date_text + '18:00请假，原因是个人事务。只生成草稿。'}).encode()
    suggestion_request = urllib.request.Request(config.origin + '/api/assistant/approval-suggestions', data=payload,
                         headers={'Content-Type': 'application/json', csrf['headerName']: csrf['token']}, method='POST')
    try:
        with employee.opener.open(suggestion_request, timeout=70) as response:
            suggestion_status, suggestion = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        suggestion_status, suggestion = error.code, json.loads(error.read(64 * 1024))
    proof['suggestion'] = {'httpStatus': suggestion_status, 'response': suggestion}
    record('real-suggestion-observed')
    require(suggestion_status in (200, 400, 502, 503), 'Unexpected suggestion failure')
    require(employee.get('/api/assistant/approvals')['total'] == before, 'Model suggestion created an application')
    if suggestion_status == 200 and suggestion['data']['ready']:
        require(not suggestion['data']['preview']['stored'], 'Model suggestion claimed to have stored an application')
        record('real-suggestion-ready-without-write')
    else:
        # A safe rejection is not counted as generation-quality success. Continue testing manual confirmation.
        proof.setdefault('limitations', []).append('Real small-model suggestion did not produce a usable draft; manual confirmation was tested separately')
        record('real-suggestion-rejected-without-write')
    confirmation = employee.write('/api/assistant/approval-drafts', {'intentKey': intent, 'draft': draft})['data']
    require(employee.get('/api/assistant/approvals')['total'] == before, 'Preparing a draft created an application')
    employee.write('/api/assistant/approval-drafts/confirm', {'confirmationId': confirmation['confirmationId'], 'confirmed': False}, expected=400)
    body = {'confirmationId': confirmation['confirmationId'], 'confirmed': True}
    entry = employee.write('/api/assistant/approval-drafts/confirm', body)['data']
    replay = employee.write('/api/assistant/approval-drafts/confirm', body)['data']
    require(entry == replay and employee.get('/api/assistant/approvals')['total'] == before + 1, 'Confirmation was not idempotent')
    request = entry['request']
    admin.write('/api/approvals/' + str(request['id']) + '/actions', {'action': 'APPROVE', 'expectedVersion': request['version'], 'note': '合成验收审批'})
    query = '?from=' + day + '&to=' + day
    business = employee.get('/api/attendance/me/days' + query)
    assistant = employee.get('/api/assistant/attendance' + query)
    require(business == assistant and len(assistant) == 1, 'Attendance and assistant projections differ')
    report = admin.get('/api/reports/attendance' + query + '&companyId=' + str(companies[0]))
    summary = admin.get('/api/assistant/attendance/summary' + query + '&companyId=' + str(companies[0]))
    require(report['totals']['leaveMinutes'] == summary['leaveMinutes'] == assistant[0]['leaveMinutes'] and assistant[0]['leaveMinutes'] > 0, 'Approval, report and assistant totals differ')
    require(report['records']['items'][0]['approvalIds'] == assistant[0]['approvalIds'] and request['id'] in assistant[0]['approvalIds'], 'Approval provenance was lost')
    proof['business'] = {'applicationId': request['id'], 'intentKey': intent, 'leaveMinutes': assistant[0]['leaveMinutes'], 'approvalIds': assistant[0]['approvalIds'], 'confirmedOnce': True, 'projectionsEqual': True}
    record('confirmed-idempotent-approval-and-business-consistency')
    # A real stopped provider must fail closed while document reading remains available.
    services.stop('ollama')
    failed = employee.write('/api/assistant/ask', {'question': '年假规定：每年几天？'})['data']
    require(failed['state'] == 'UNAVAILABLE' and not failed['text'] and not failed['references'], 'Stopped model returned a fabricated answer')
    require(employee.get(source_path(citation)) == citation, 'Provider outage prevented source reading')
    services.start_model()
    hit(employee, doc, '年假')
    record('real-provider-outage-and-recovery')
    services.stop('application')
    fingerprint = database.sql('SELECT id,version,sha256,processing,indexing FROM c_verify.knowledge_document ORDER BY id')
    database.stop()
    database.start()
    require(database.sql('SELECT id,version,sha256,processing,indexing FROM c_verify.knowledge_document ORDER BY id') == fingerprint, 'MySQL restart changed document state')
    services.start_app()
    employee.request('/api/assistant/approvals', expected=401)
    employee.login(password, 'cverify1')
    admin.login(password)
    require(employee.get(source_path(citation)) == citation, 'Restart lost source metadata')
    require(employee.request('/api/knowledge/documents/' + doc['id'] + '/file') == content, 'Restart lost original bytes')
    require(employee.get('/api/assistant/approvals')['total'] == before + 1, 'Restart lost the application')
    replay = employee.write('/api/approvals', {'requestKey': intent, 'draft': draft})['data']
    require(replay['request']['id'] == request['id'] and employee.get('/api/assistant/approvals')['total'] == before + 1, 'Persistent request replay duplicated the application')
    employee.write('/api/assistant/approval-drafts/confirm', body, expected=404)
    proof['restart'] = {'databaseAndApplication': True, 'fingerprint': hashlib.sha256(fingerprint.encode()).hexdigest(),
                        'originalAndCitationRetained': True, 'applicationRetained': True, 'databaseIdempotencyRetained': True,
                        'sessionsAndPendingConfirmationsInvalidated': True}
    record('database-application-restart-and-idempotency')
    # Simulate a worker crash only in this owned synthetic DB; the live scheduler expires its lease.
    services.stop('application')
    config.env['LIANZHI_KNOWLEDGE_WORKER_ENABLED'] = 'false'
    services.start_app()
    employee = Client(config.origin)
    employee.login(password, 'cverify1')
    interrupted = employee.upload(companies[0], content, '合成中断任务.docx', 'PRIVATE')
    database.sql("UPDATE c_verify.knowledge_document SET processing='PROCESSING',lease_token='" + str(uuid.uuid4()) +
                 "',lease_until=1 WHERE id='" + interrupted['id'] + "'")
    services.stop('application')
    config.env['LIANZHI_KNOWLEDGE_WORKER_ENABLED'] = 'true'
    services.start_app()
    employee = Client(config.origin)
    employee.login(password, 'cverify1')
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        recovered = employee.get('/api/knowledge/documents/' + interrupted['id'])
        if recovered['processing'] == 'FAILED':
            break
        time.sleep(.5)
    require(recovered['processing'] == 'FAILED', 'Expired worker lease was not marked as interrupted')
    employee.write('/api/knowledge/documents/' + interrupted['id'] + '/retry?expectedVersion=' + str(recovered['version']))
    retried = parsed(employee, interrupted['id'])
    require(retried['version'] == recovered['version'] + 1, 'Explicit retry did not create a new document version')
    hit(employee, retried, '年假')
    record('expired-worker-lease-and-explicit-retry')
    admin = Client(config.origin)
    admin.login(password)
    # Real company transfer revokes the old session and removes private sources from the new scope.
    user_input.update(companyId=companies[1], workplaceIds=[])
    admin.write('/api/employees/' + str(users[0]['userId']), user_input, method='PUT')
    employee.request('/api/knowledge/documents/' + doc['id'], expected=401)
    employee.login(password, 'cverify1')
    employee.request(source_path(citation), expected=404)
    record('permission-change-revokes-session-and-source')
    admin.write('/api/knowledge/documents/' + doc['id'] + '?expectedVersion=' + str(doc['version']), method='DELETE')
    admin.request(source_path(citation), expected=404)
    require(not any(h['citation']['documentId'] == doc['id'] for h in admin.get('/api/knowledge/search?query=' + urllib.parse.quote('年假'))), 'Deleted source remained searchable')
    record('deletion-invalidates-source-and-search')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mysql-bin', type=Path, required=True)
    p.add_argument('--runtime-directory', type=Path, required=True)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--fixtures', type=Path, help='Synthetic real-ocr-fixture.png/pdf and real-asr-fixture.wav; omit for text-only')
    args = p.parse_args()
    if args.fixtures:
        require(all((args.fixtures / n).is_file() for n in ('real-ocr-fixture.png', 'real-ocr-fixture.pdf', 'real-asr-fixture.wav')), 'Synthetic fixture files are missing')
    folder = ROOT / 'artifacts' / ('c-mysql-ai-' + uuid.uuid4().hex[:12])
    folder.mkdir(parents=True)
    jar = folder / 'application.jar'
    shutil.copy2(args.jar, jar)  # Keep this run independent from later Maven builds/file locks.
    proof = {'passed': False, 'checks': [], 'environment': os.name, 'existingServiceUntouched': True}
    def record(name):
        proof['checks'].append(name)
        (folder / 'result.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
        print('PASS ' + name, flush=True)
    env_file = folder / '.env'
    print('Isolated real-service verification: ' + str(folder), flush=True)
    try:
        with DisposableMysql(args.mysql_bin, folder / 'mysql') as database:
            env_file.write_text('DB_URL=jdbc:mysql://127.0.0.1:' + str(database.port) + '/c_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai\n'
                                'DB_USERNAME=c_verify\nDB_PASSWORD=' + database.password + '\nADMIN_INITIAL_PASSWORD=' + secrets.token_hex(20) +
                                '\nLIANZHI_FACE_ENABLED=false\nLIANZHI_ARCSOFT_ENABLED=false\nLIANZHI_KNOWLEDGE_WORKER_ENABLED=true\n', encoding='utf-8')
            env_file.chmod(0o600)
            ports = set()
            while len(ports) < 3:
                ports.add(free_port())
            app_port, model_port, media_port = list(ports)
            argv = ['--mysql', '--no-browser', '--env-file', str(env_file), '--runtime-directory', str(args.runtime_directory),
                    '--data-directory', str(folder / 'application'), '--jar', str(jar), '--port', str(app_port),
                    '--ollama-port', str(model_port), '--media-port', str(media_port)]
            if not args.fixtures:
                argv.append('--text-only')
            config = runtime.Configuration(runtime.parser().parse_args(argv))
            with runtime.Services(config) as services:
                verify(services, database, args.fixtures, proof, record)
        proof['passed'] = True
    except Exception as error:
        proof['failure'] = type(error).__name__ + ': ' + str(error)
        raise
    finally:
        env_file.unlink(missing_ok=True)
        (folder / 'result.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Result: ' + str(folder / 'result.json'), flush=True)


if __name__ == '__main__':
    main()
