#!/usr/bin/env python3
"""Exercise opt-in shared HTTP sessions on real Redis, real MySQL and separate JVMs."""
import argparse
import concurrent.futures
import copy
from datetime import datetime, timedelta, timezone
import http.cookiejar
import json
import math
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from c_disposable_mysql import DisposableMysql, free_port
from c_disposable_mysql_container import DisposableMysqlContainer
from c_disposable_redis import DisposableRedis, RedisClient
from c_http_verification import Client

ROOT = Path(__file__).resolve().parents[1]
PENDING = 'sessionAttr:cn.lianzhi.attendance.assistant.AssistantActions.pending'


def require(value, message):
    if not value: raise RuntimeError(message)


class SessionClient(Client):
    def __init__(self, origin, cookies=None):
        super().__init__(origin)
        self.cookies = http.cookiejar.CookieJar()
        for cookie in cookies or []: self.cookies.set_cookie(copy.copy(cookie))
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.last_headers = None

    def request(self, path, method='GET', data=None, headers=None, expected=200):
        request = urllib.request.Request(self.origin + path, data=data, headers=headers or {}, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                status, body, self.last_headers = response.status, response.read(12 * 1024 * 1024), response.headers
        except urllib.error.HTTPError as error:
            status, body, self.last_headers = error.code, error.read(64 * 1024), error.headers
        if status != expected:
            raise RuntimeError(f'{method} {path.split("?")[0]} returned {status}, expected {expected}')
        return body

    def switch(self, origin):
        self.origin = origin
        return self

    def fork(self, origin=None):
        return SessionClient(origin or self.origin, list(self.cookies))

    def session_id(self):
        return next(cookie.value for cookie in self.cookies if cookie.name == 'JSESSIONID')


class Applications:
    def __init__(self, java, jar, folder, env):
        self.java, self.jar, self.folder, self.env = java, jar, folder, env
        self.processes, self.logs, self.origins = {}, [], {}

    def start(self, name, overrides=None, expect_failure=False, profiles='mysql,standalone'):
        directory = self.folder / name
        directory.mkdir(parents=True, exist_ok=True)
        port = int(self.origins[name].rsplit(':',1)[1]) if name in self.origins else free_port()
        self.origins[name] = 'http://127.0.0.1:' + str(port)
        env = self.env.copy(); env.update(overrides or {})
        log = (self.folder / (name + '.log')).open('a', encoding='utf-8'); self.logs.append(log)
        process = subprocess.Popen([str(self.java),'-Xmx256m','-jar',str(self.jar),'--spring.profiles.active='+profiles,
                                    '--lianzhi.demo='+str('demo' in profiles.split(',')).lower(),'--server.address=127.0.0.1','--server.port='+str(port)],
                                   cwd=directory,env=env,stdout=log,stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
        self.processes[name] = process
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if process.poll() is not None:
                if expect_failure: return self.origins[name]
                raise RuntimeError('Application '+name+' exited; inspect its isolated log')
            if expect_failure:
                time.sleep(.5); continue
            try:
                SessionClient(self.origins[name]).get('/api/auth/csrf')
                return self.origins[name]
            except Exception: time.sleep(.5)
        raise RuntimeError('Application startup did not reach its expected result: '+name)

    def stop(self, name):
        process = self.processes.get(name)
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)

    def close(self):
        for name in reversed(self.processes): self.stop(name)
        for log in self.logs: log.close()


def verify_browser(apps, chromium, proof, record):
    for name in list(apps.processes): apps.stop(name)
    origin=apps.start('browser',{'LIANZHI_REDIS_NAMESPACE':apps.env['LIANZHI_REDIS_NAMESPACE']+':browser',
                                'LIANZHI_KNOWLEDGE_WORKER_ENABLED':'true'},profiles='demo')
    port=free_port(); frontend=ROOT/'frontend'; folder=apps.folder
    vite=folder/'browser-vite.mjs'; config=folder/'browser-playwright.mjs'
    vite.write_text('import { mergeConfig } from '+json.dumps((frontend/'node_modules/vite/dist/node/index.js').as_uri())+';\n'
                    'import base from '+json.dumps((frontend/'vite.config.js').as_uri())+';\n'
                    'export default mergeConfig(base,'+json.dumps({'root':frontend.as_posix(),'server':{'port':port,
                    'strictPort':True,'proxy':{'/api':{'target':origin,'changeOrigin':False}}}})+');\n',encoding='utf-8')
    use={'baseURL':'http://127.0.0.1:'+str(port),'trace':'retain-on-failure'}
    if chromium: use['launchOptions']={'executablePath':str(chromium.resolve())}
    settings={'testDir':(frontend/'e2e').as_posix(),'fullyParallel':False,'workers':1,'use':use,'reporter':'list',
              'outputDir':(folder/'browser-results').as_posix(),'webServer':{
                  'command':'node node_modules/vite/bin/vite.js --config "'+vite.as_posix()+'" --host 127.0.0.1',
                  'cwd':frontend.as_posix(),'url':'http://127.0.0.1:'+str(port),'reuseExistingServer':False}}
    config.write_text('import { defineConfig } from '+json.dumps((frontend/'node_modules/@playwright/test/index.mjs').as_uri())+';\n'
                      'export default defineConfig('+json.dumps(settings)+');\n',encoding='utf-8')
    argv=(['cmd','/c','npm.cmd'] if os.name=='nt' else ['npm'])+['run','test:e2e','--','--config',str(config)]
    print('Running existing browser regressions against real Redis sessions',flush=True)
    with (folder/'browser.log').open('w',encoding='utf-8') as log:
        result=subprocess.run(argv,cwd=frontend,stdout=log,stderr=subprocess.STDOUT,timeout=600)
    require(result.returncode==0,'Redis-enabled browser regressions failed; inspect the isolated browser log')
    output=(folder/'browser.log').read_text('utf-8',errors='replace')
    counts=re.findall(r'(\d+) passed',output)
    require(bool(counts),'Browser regression summary was not found')
    proof['browser']={'passed':True,'tests':int(counts[-1]),'redisEnabled':True,'syntheticDemoDatabase':True}
    record('existing-browser-regressions-with-redis-enabled')


def verify(apps, database, redis, proof, record):
    first, second = apps.start('first'), apps.start('second')
    password = apps.env['ADMIN_INITIAL_PASSWORD']
    admin = SessionClient(first)
    admin.get('/api/auth/csrf')
    old_anonymous = admin.fork(second)
    anonymous_id = admin.session_id()
    admin.login(password)
    cookie = '\n'.join(admin.last_headers.get_all('Set-Cookie') or [])
    require('JSESSIONID=' in cookie and 'HttpOnly' in cookie and 'SameSite=Lax' in cookie and 'Path=/' in cookie, 'Shared session cookie lost its existing security attributes')
    require(admin.session_id()!=anonymous_id,'Login did not rotate the session ID')
    old_anonymous.request('/api/auth/session',expected=401)
    require(admin.switch(second).get('/api/auth/session')['role']=='ADMIN','Login did not survive a switch to the second JVM')
    require(admin.get('/api/auth/session')['role']=='ADMIN','Session refresh failed')
    info=redis.client.command('INFO','server').decode()
    proof['redisVersion']=next(line.split(':',1)[1].strip() for line in info.splitlines() if line.startswith('redis_version:'))
    proof['mysqlVersion']=database.sql('SELECT VERSION()')
    record('real-redis-two-jvms-login-refresh-cookie-and-fixation')
    company_ids=[]
    for n in (1,2):
        name='Redis合成公司'+str(n)
        admin.write('/api/organizations/companies',{'name':name,'enabled':True})
        company_ids.append(next(c['id'] for c in admin.get('/api/organizations/companies')['items'] if c['name']==name))
    employee_input={'username':'redisemployee','displayName':'Redis合成员工','employeeNo':'REDIS001','role':'EMPLOYEE',
                    'companyId':company_ids[0],'departmentId':None,'enabled':True,'password':password,'workplaceIds':[]}
    user=admin.write('/api/employees',employee_input)['data']; employee_input['password']=None
    employee=SessionClient(first); employee.login(password,'redisemployee')
    initial_cookies=list(employee.cookies)
    csrf=employee.get('/api/auth/csrf')
    day=(datetime.now(timezone(timedelta(hours=8)))+timedelta(days=2)).date().isoformat()
    draft={'type':'LEAVE','startAt':day+'T09:00:00','endAt':day+'T18:00:00','punchType':None,'workplaceId':None,'reason':'Redis合成验收'}
    intent=str(uuid.uuid4())
    before=employee.get('/api/assistant/approvals')['total']
    employee.switch(second)
    prepared=json.loads(employee.request('/api/assistant/approval-drafts','POST',json.dumps({'intentKey':intent,'draft':draft}).encode(),
                        {'Content-Type':'application/json',csrf['headerName']:csrf['token']}))['data']
    employee.request('/api/assistant/approval-drafts/confirm','POST',b'{}',{'Content-Type':'application/json'},expected=403)
    employee.write('/api/assistant/approval-drafts/confirm',{'confirmationId':prepared['confirmationId'],'confirmed':False},expected=400)
    require(employee.get('/api/assistant/approvals')['total']==before,'Preparing or rejecting confirmation wrote an application')
    apps.stop('second')
    body={'confirmationId':prepared['confirmationId'],'confirmed':True}
    entry=employee.switch(first).write('/api/assistant/approval-drafts/confirm',body)['data']
    apps.start('second')
    require(employee.switch(second).get('/api/auth/session')['id']==user['userId'],'Session did not survive JVM restart')
    require(employee.write('/api/assistant/approval-drafts/confirm',body)['data']['request']['id']==entry['request']['id'],'Confirmation replay lost database idempotency')
    require(employee.get('/api/assistant/approvals')['total']==before+1,'Confirmation created duplicate applications')
    record('cross-instance-csrf-draft-serialization-restart-confirm-and-idempotency')
    # Confirm that the real login pipeline erased credentials before Redis serialization.
    namespace=apps.env['LIANZHI_REDIS_NAMESPACE']
    password_hash=database.sql("SELECT password_hash FROM c_verify.sys_user WHERE username='redisemployee'").encode()
    for key in redis.client.keys(namespace+':sessions:*'):
        pairs=redis.client.command('HGETALL',key)
        for value in pairs[1::2]:
            require(password.encode() not in value and password_hash not in value and b'cn.lianzhi.attendance.identity.UserAccount' not in value,
                    'A credential or persistence object was serialized into Redis')
    proof['credentialOrPersistenceObjectCached']=False
    record('redis-payload-has-no-password-hash-or-persistence-object')
    # Preserve an old session attribute, then prove a later cancellation cannot be undone by that stale copy.
    cancellation=employee.switch(first).write('/api/assistant/approval-drafts',{'intentKey':str(uuid.uuid4()),'draft':draft})['data']
    key=namespace+':sessions:'+employee.session_id()
    old_state=redis.client.command('HGET',key,PENDING)
    require(old_state is not None,'Assistant confirmation state was not persisted')
    employee.switch(second).write('/api/assistant/approval-drafts/'+cancellation['confirmationId']+'/cancel')
    redis.client.command('HSET',key,PENDING,old_state)
    employee.switch(first).write('/api/assistant/approval-drafts/confirm',{'confirmationId':cancellation['confirmationId'],'confirmed':True},expected=409)
    require(employee.get('/api/assistant/approvals')['total']==before+1,'A stale session undid cancellation')
    record('stale-session-copy-cannot-undo-cancellation')
    race=employee.write('/api/assistant/approval-drafts',{'intentKey':str(uuid.uuid4()),'draft':dict(draft,startAt=day+'T19:00:00',endAt=day+'T20:00:00')})['data']
    one,two=employee.fork(first),employee.fork(second)
    def racing(client,path,payload=None):
        try: client.write(path,payload); return 200
        except RuntimeError as error:
            if 'returned 409, expected 200' in str(error): return 409
            raise
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(racing,one,'/api/assistant/approval-drafts/confirm',{'confirmationId':race['confirmationId'],'confirmed':True})
        b=pool.submit(racing,two,'/api/assistant/approval-drafts/'+race['confirmationId']+'/cancel')
        confirm_status,cancel_status=a.result(timeout=20),b.result(timeout=20)
    require(sorted([confirm_status,cancel_status])==[200,409],'Concurrent cancellation and submission both claimed success')
    current=employee.get('/api/assistant/approvals')['total']
    require(current==before+1+(confirm_status==200),'Concurrent cancellation result disagrees with MySQL')
    record('atomic-cross-instance-cancel-versus-confirm')
    # Real outage and Redis AOF restart: no process-local fallback and no credential-bearing response.
    redis.stop()
    outage=json.loads(employee.switch(second).request('/api/auth/session',expected=503))
    require(outage['code']=='SESSION_STORE_UNAVAILABLE' and outage['data'] is None,'Redis outage weakened authentication')
    redis.start()
    deadline=time.monotonic()+15
    while True:
        try: employee.get('/api/auth/session'); break
        except RuntimeError:
            if time.monotonic()>deadline: raise
            time.sleep(.5)
    require(employee.get('/api/assistant/approvals')['total']==current,'Redis recovery lost the authenticated business view')
    record('redis-outage-fails-closed-and-aof-restart-restores-session')
    # A separate namespace and a short idle timeout validate scope isolation and actual expiration.
    third=apps.start('expiry',{'LIANZHI_REDIS_NAMESPACE':namespace+':isolated','LIANZHI_SESSION_TIMEOUT':'3s'})
    employee.fork(third).request('/api/auth/session',expected=401)
    expiring=SessionClient(third); expiring.login(password,'redisemployee')
    expiring_id=expiring.session_id()
    ttl=redis.client.command('TTL',namespace+':isolated:sessions:'+expiring_id)
    interval=redis.client.command('HGET',namespace+':isolated:sessions:'+expiring_id,'maxInactiveInterval')
    require(interval is not None and b'java.lang.Integer' in interval and int.from_bytes(interval[-4:],'big')==3,'Stored session idle interval differs from 3s')
    server_time=redis.client.command('TIME')
    skew=float(server_time[0])+float(server_time[1])/1000000-time.time()
    require(abs(skew)<5,'Redis/JVM host clocks differ by at least five seconds; synchronize the test environment clocks')
    require(0<ttl<=3+math.ceil(max(0,-skew))+1,'Redis session key did not receive a bounded TTL: '+str(ttl))
    proof['expiry']={'configuredSeconds':3,'storedIntervalSeconds':3,'observedTtl':ttl,'redisClockSkewSeconds':round(skew,3)}
    time.sleep(max(4,ttl+1))
    require(redis.client.command('EXISTS',namespace+':isolated:sessions:'+expiring_id)==0,'Redis did not expire the idle session key')
    expiring.request('/api/auth/session',expected=401)
    record('namespace-isolation-and-real-idle-expiration')
    apps.stop('expiry')
    # DB identity changes are checked on every instance, including previously deserialized principals.
    admin.switch(first)
    employee_input['role']='HR'
    admin.write('/api/employees/'+str(user['userId']),employee_input,method='PUT')
    employee.switch(second).request('/api/auth/session',expected=401)
    SessionClient(first,initial_cookies).request('/api/auth/session',expected=401)
    employee=SessionClient(second); employee.login(password,'redisemployee')
    require(employee.get('/api/auth/session')['role']=='HR','Updated role was not loaded after login')
    employee.get('/api/organizations/companies')
    employee_input['companyId']=company_ids[1]
    admin.write('/api/employees/'+str(user['userId']),employee_input,method='PUT')
    employee.switch(first).request('/api/auth/session',expected=401)
    employee=SessionClient(second); employee.login(password,'redisemployee')
    require(employee.get('/api/employees/me')['companyId']==company_ids[1],'Live company change did not revoke the old scope')
    employee_input['enabled']=False
    admin.write('/api/employees/'+str(user['userId']),employee_input,method='PUT')
    employee.switch(first).request('/api/auth/session',expected=401)
    employee_input.update(enabled=True,companyId=company_ids[1],role='EMPLOYEE')
    admin.write('/api/employees/'+str(user['userId']),employee_input,method='PUT')
    employee=SessionClient(second); employee.login(password,'redisemployee')
    require(employee.get('/api/employees/me')['companyId']==company_ids[1],'Company scope did not change after login')
    employee.request('/api/organizations/companies',expected=403)
    old_password_session=employee.fork(first)
    next_password=secrets.token_hex(20)
    employee.write('/api/profile/password',{'currentPassword':password,'newPassword':next_password,'confirmPassword':next_password})
    old_password_session.request('/api/auth/session',expected=401)
    record('role-disable-company-and-password-changes-revoke-shared-sessions')
    employee=SessionClient(first); employee.login(next_password,'redisemployee')
    before_logout=employee.fork(second)
    employee.switch(second).write('/api/auth/logout')
    before_logout.switch(first).request('/api/auth/session',expected=401)
    record('logout-invalidates-all-instances')
    bad=apps.start('bad-credentials',{'LIANZHI_REDIS_PASSWORD':secrets.token_hex(24)},expect_failure=True)
    require(apps.processes['bad-credentials'].returncode!=0,'Wrong Redis credentials did not stop startup')
    record('wrong-redis-credentials-abort-startup')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mysql-bin',type=Path)
    p.add_argument('--mysql-image',help='An isolated MySQL Docker image instead of installed server binaries')
    p.add_argument('--redis-server')
    p.add_argument('--redis-image',help='A pinned official image; uses Docker and the deployment startup script')
    p.add_argument('--wsl-distro')
    p.add_argument('--redis-library-directory')
    p.add_argument('--java',type=Path,default=Path(shutil.which('java') or 'java'))
    p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--browser',action='store_true',help='Also run existing frontend e2e against an isolated Redis-enabled demo instance')
    p.add_argument('--browser-only',action='store_true',help='Run only the Redis-enabled browser regression scope')
    p.add_argument('--chromium',type=Path,help='Optional installed Chromium executable for the existing Playwright tests')
    args=p.parse_args()
    require(bool(args.mysql_bin)!=bool(args.mysql_image),'Provide exactly one of --mysql-bin or --mysql-image')
    require(bool(args.redis_server)!=bool(args.redis_image),'Provide exactly one of --redis-server or --redis-image')
    require(not args.redis_image or not args.wsl_distro,'Docker image mode cannot also use WSL mode')
    folder=ROOT/'artifacts'/('c-redis-'+uuid.uuid4().hex[:12]); folder.mkdir(parents=True)
    jar=folder/'application.jar'; shutil.copy2(args.jar,jar)
    proof={'passed':False,'checks':[],'existingServicesUntouched':True,'fixtures':'SYNTHETIC_ONLY',
           'scope':'REDIS_BROWSER_ONLY' if args.browser_only else 'REAL_REDIS_MYSQL_SHARED_SESSIONS'}
    def record(name):
        proof['checks'].append(name)
        (folder/'result.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
        print('PASS '+name,flush=True)
    apps=None
    print('Isolated Redis/MySQL verification: '+str(folder),flush=True)
    try:
        mysql = (DisposableMysqlContainer(args.mysql_image,folder/'mysql') if args.mysql_image else
                 DisposableMysql(args.mysql_bin,folder/'mysql'))
        with mysql as database, DisposableRedis(args.redis_server,folder/'redis',args.wsl_distro,args.redis_library_directory,args.redis_image) as redis:
            env=os.environ.copy()
            env.update(DB_URL='jdbc:mysql://127.0.0.1:'+str(database.port)+'/c_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai',
                       DB_USERNAME='c_verify',DB_PASSWORD=database.password,ADMIN_INITIAL_PASSWORD=secrets.token_hex(20),
                       LIANZHI_REDIS_ENABLED='true',LIANZHI_REDIS_HOST='127.0.0.1',LIANZHI_REDIS_PORT=str(redis.port),
                       LIANZHI_REDIS_PASSWORD=redis.password,LIANZHI_REDIS_USERNAME='',LIANZHI_REDIS_TLS='false',
                       LIANZHI_REDIS_NAMESPACE='lianzhi:test:'+uuid.uuid4().hex,LIANZHI_SESSION_TIMEOUT='30m',
                       LIANZHI_FACE_ENABLED='false',LIANZHI_ARCSOFT_ENABLED='false',LIANZHI_KNOWLEDGE_WORKER_ENABLED='false',
                       LIANZHI_AI_BASE_URL='',LIANZHI_AI_MODEL='',LIANZHI_EMBEDDING_BASE_URL='',LIANZHI_EMBEDDING_MODEL='',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1')
            # Explicitly replace inherited datasource overrides in this isolated test process.
            for key in list(env):
                if key.startswith('SPRING_DATASOURCE_') and not key.startswith('SPRING_DATASOURCE_HIKARI_'): env.pop(key)
            apps=Applications(args.java.resolve(),jar.resolve(),folder,env)
            try:
                if not args.browser_only: verify(apps,database,redis,proof,record)
                if args.browser or args.browser_only: verify_browser(apps,args.chromium,proof,record)
            finally: apps.close()
        proof['passed']=True
    except Exception as error:
        proof['failure']=type(error).__name__+': '+str(error)
        raise
    finally:
        (folder/'result.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
        print('Result: '+str(folder/'result.json'),flush=True)


if __name__=='__main__': main()
