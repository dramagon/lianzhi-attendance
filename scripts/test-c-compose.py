#!/usr/bin/env python3
"""Build and exercise an isolated Compose project with generated test-only credentials."""
import json, os, re, secrets, shutil, socket, subprocess, time, uuid
from pathlib import Path
from c_http_verification import Client, verify, after_restart
ROOT=Path(__file__).resolve().parents[1]
def main():
    if not shutil.which('docker'):raise RuntimeError('Docker is required; no project was created.')
    project='lianzhi-c-verify-'+uuid.uuid4().hex[:10];folder=ROOT/'artifacts'/project;folder.mkdir(parents=True)
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    password=secrets.token_hex(24);config=folder/'test.env';config.write_text('DB_PASSWORD='+secrets.token_hex(24)+'\nMYSQL_ROOT_PASSWORD='+secrets.token_hex(24)+'\nADMIN_INITIAL_PASSWORD='+password+'\nWEB_PORT='+str(port)+'\nWEB_BIND_ADDRESS=127.0.0.1\nSESSION_COOKIE_SECURE=false\n');config.chmod(0o600)
    command=['docker','compose','--env-file',str(config),'-f',str(ROOT/'deploy/compose.yaml'),'-p',project]
    def run(args,label,timeout=900):
        with (folder/(label+'.log')).open('w',encoding='utf-8') as log:subprocess.run(command+args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=timeout)
    origin='http://127.0.0.1:'+str(port)
    def ready():
        end=time.monotonic()+150
        while time.monotonic()<end:
            try:Client(origin).get('/api/system/info');return
            except Exception:time.sleep(1)
        raise RuntimeError('Isolated Compose API readiness timed out.')
    try:
        print('Building isolated Compose project:',project,flush=True);run(['config','--quiet'],'config');run(['up','-d','--build'],'build');ready()
        client=Client(origin);proof=verify(client,password);run(['restart','backend'],'restart');ready();after_restart(Client(origin),password,proof)
        result={'composeConfig':True,'backendImageBuilt':True,'webImageBuilt':True,'sameOriginSpa':True,'csrfAndSession':True,'mysqlV6':True,'isolatedParserInContainer':True,'uploadTextCitationDownload':True,'reportCsv':True,'restartPreserved':True,'deleteInvalidatesSource':True,'aiUnconfiguredReturns503':True,'secretsPrinted':False}
        (folder/'result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result),flush=True)
    finally:
        try:run(['logs','--no-color'],'services',timeout=30)
        finally:
            # Only the unique project created above; never uses a user's existing Compose project name.
            assert re.fullmatch(r'lianzhi-c-verify-[0-9a-f]{10}',project)
            try:run(['down','--volumes','--remove-orphans'],'cleanup',timeout=90)
            finally:config.unlink(missing_ok=True)
if __name__=='__main__':main()
