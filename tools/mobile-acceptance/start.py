#!/usr/bin/env python3
"""Start an isolated HTTPS acceptance lab. No production deployment or system CA installation."""
import argparse
import http.cookiejar
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ip', default='127.0.0.1', help='Local IPv4 to bind; pass your LAN IP for phones')
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--backend-port', type=int, default=18080)
    parser.add_argument('--caddy', default=shutil.which('caddy'))
    parser.add_argument('--run-dir', type=Path, default=ROOT/'artifacts/mobile-https')
    parser.add_argument('--check', action='store_true', help='Verify trusted TLS, secure login, CSRF and rules; then stop')
    args = parser.parse_args()
    address = ipaddress.IPv4Address(args.ip)
    if address.is_unspecified or address.is_multicast:
        raise RuntimeError('Use a specific local IPv4, not a wildcard/multicast address')
    if not (1024 <= args.port <= 65535 and 1024 <= args.backend_port <= 65535) or args.port == args.backend_port:
        raise RuntimeError('Use different ports between 1024 and 65535')
    caddy = Path(args.caddy).resolve() if args.caddy else None
    if caddy is None or not caddy.is_file():
        raise RuntimeError('Install official Caddy, or provide --caddy PATH. See README.md.')
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home)/'bin'/('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
    if not java:
        raise RuntimeError('Java 17+ is required')
    jar = ROOT/'backend/target/lianzhi-backend-0.1.0.jar'
    dist = ROOT/'frontend/dist'
    if not jar.is_file() or not (dist/'index.html').is_file():
        raise RuntimeError('Build backend and frontend first; see README.md')
    for bind_ip, port in [(args.ip, args.port), ('127.0.0.1', args.backend_port)]:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind((bind_ip, port))
            except OSError as error:
                raise RuntimeError('Requested lab address/port is unavailable; existing services were not changed') from error
    run = args.run_dir.resolve()
    run.mkdir(parents=True, exist_ok=True)
    # All runtime files stay under the chosen lab directory, never backend/data.
    data = run/'caddy-data'; config = run/'caddy-config'
    database = run/'database/lianzhi'
    database.parent.mkdir(parents=True, exist_ok=True)
    if ';' in str(database):
        raise RuntimeError('Run directory cannot contain a semicolon')
    caddyfile = run/'Caddyfile'
    caddyfile.write_text('''{
  admin off
  skip_install_trust
  persist_config off
  storage file_system {
    root %s
  }
  auto_https disable_redirects
}
https://%s:%s {
  bind %s
  tls internal
  header Permissions-Policy "camera=(self), geolocation=(self), microphone=()"
  handle /api/* {
    reverse_proxy 127.0.0.1:%s
  }
  handle {
    root * %s
    try_files {path} /index.html
    file_server
  }
}
''' % (json.dumps(data.as_posix()), args.ip, args.port, args.ip, args.backend_port, json.dumps(dist.as_posix())))
    env = os.environ.copy()
    env.update(XDG_DATA_HOME=str(data), XDG_CONFIG_HOME=str(config))
    subprocess.run([str(caddy),'validate','--config',str(caddyfile),'--adapter','caddyfile'],env=env,check=True)
    processes = []
    handles = []
    url = 'https://' + args.ip + ':' + str(args.port)
    try:
        for filename, argv, directory, process_env in [
            ('backend.log',[java,'-jar',str(jar),'--spring.profiles.active=demo','--lianzhi.demo=true',
             '--spring.datasource.url=jdbc:h2:file:'+database.as_posix()+';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;OPTIMIZE_REUSE_RESULTS=FALSE',
             '--spring.datasource.username=sa','--spring.datasource.password=',
             '--server.address=127.0.0.1','--server.port='+str(args.backend_port),'--server.servlet.session.cookie.secure=true',
             '--lianzhi.avatar-directory='+(run/'avatars').as_posix()],ROOT/'backend',os.environ.copy()),
            ('https.log',[str(caddy),'run','--config',str(caddyfile),'--adapter','caddyfile'],run,env),
        ]:
            handle=(run/filename).open('a');handles.append(handle)
            processes.append(subprocess.Popen(argv,cwd=directory,env=process_env,stdout=handle,stderr=subprocess.STDOUT))
        ca = data/'pki/authorities/local/root.crt'
        deadline = time.monotonic()+60
        opener = None
        while time.monotonic() < deadline:
            if any(p.poll() is not None for p in processes):
                raise RuntimeError('Lab service exited; inspect backend.log and https.log in '+str(run))
            if ca.is_file():
                context = ssl.create_default_context(cafile=str(ca))
                # Local acceptance traffic goes directly to the lab; keep hostname and CA verification enabled.
                jar_cookies=http.cookiejar.CookieJar()
                opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context),urllib.request.HTTPCookieProcessor(jar_cookies))
                try:
                    with opener.open(url+'/api/system/info',timeout=2) as response:
                        info=json.load(response)
                    if info['data']['demo'] is True:
                        break
                except (OSError, urllib.error.URLError):
                    pass
            time.sleep(0.5)
        else:
            raise RuntimeError('Trusted HTTPS readiness timed out; inspect lab logs')
        def request(path, payload=None, headers=None):
            with opener.open(urllib.request.Request(url+path,data=payload,headers=headers or {}),timeout=15) as response:
                return response.read()
        html=request('/attendance/device').decode()
        if '<div id="app">' not in html:
            raise RuntimeError('SPA device-check route did not return the app')
        csrf=json.loads(request('/api/auth/csrf'))['data']
        login=json.loads(request('/api/auth/login',urllib.parse.urlencode({'username':'employee','password':'Lianzhi@2026'}).encode(),
            {'Content-Type':'application/x-www-form-urlencoded',csrf['headerName']:csrf['token']}))
        if login['data']['username'] != 'employee' or not any(c.name=='JSESSIONID' and c.secure for c in jar_cookies):
            raise RuntimeError('Secure-cookie demo login did not succeed')
        csrf=json.loads(request('/api/auth/csrf'))['data']
        preview={'date':'2026-10-26','rule':{'start':'09:00','end':'18:00','breakStart':'12:00','breakEnd':'13:00','graceMinutes':5,'workdays':['MONDAY']},
                 'checkIn':'2026-10-26T09:12:00','checkOut':'2026-10-26T18:00:00','holiday':False,'finalized':True}
        result=json.loads(request('/api/attendance/rules/preview',json.dumps(preview).encode(),{'Content-Type':'application/json',csrf['headerName']:csrf['token']}))
        if result['data']['workMinutes'] != 468:
            raise RuntimeError('HTTPS rule functional check failed')
        receipt=json.loads(request('/api/attendance/me/punches/requests/'+str(uuid.uuid4())))
        if receipt['data'] != {'found':False,'event':None}:
            raise RuntimeError('HTTPS authenticated read-only punch receipt check failed')
        face_status=json.loads(request('/api/face/status'))['data']
        if face_status.get('storageAvailable') is not True:
            raise RuntimeError('Published face storage migration is not ready; inspect Flyway history')
        report={'trusted_tls':True,'hostname_verified':True,'secure_cookie_login':True,'csrf_rule_preview':True,
                'punch_receipt_query':True,'face_storage_ready':True,'demo':True,'device_route':True,'ca_certificate':str(ca)}
        (run/'readiness.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
        if args.check:
            return
        print('Acceptance lab is running at '+url+'/attendance/device',flush=True)
        print('Use your own machine LAN IP and trust only the public root.crt on the test phone. Ctrl+C stops these processes.',flush=True)
        while all(p.poll() is None for p in processes):
            time.sleep(1)
        raise RuntimeError('A lab service exited; inspect logs')
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill();process.wait()
        for handle in handles: handle.close()

if __name__=='__main__':
    try: main()
    except KeyboardInterrupt: pass
    except Exception as error:
        print(str(error),file=sys.stderr);sys.exit(1)
