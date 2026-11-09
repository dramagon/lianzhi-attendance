#!/usr/bin/env python3
"""Use C's owned MySQL/Redis fixtures and two JVMs to verify B's actual session client."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import uuid

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--c-checkout',type=Path,required=True)
    p.add_argument('--c-jar',type=Path,required=True)
    p.add_argument('--java',type=Path,required=True)
    p.add_argument('--mysql-image',required=True)
    p.add_argument('--redis-image',required=True)
    args=p.parse_args()
    if not all('@sha256:' in image for image in (args.mysql_image,args.redis_image)):raise RuntimeError('Use exact image digests')
    source=args.c_checkout.resolve()
    for path in [args.c_jar,args.java,source/'scripts/verify-c-redis.py']:
        if not path.is_file():raise RuntimeError('Build C and provide existing tool paths first')
    folder=ROOT/'artifacts'/('b-c-session-'+uuid.uuid4().hex[:12]);folder.mkdir(parents=True)
    snapshot=folder/'c-fixtures';(snapshot/'scripts').mkdir(parents=True);(snapshot/'deploy').mkdir()
    for name in ['verify-c-redis.py','c_http_verification.py','c_disposable_mysql.py','c_disposable_mysql_container.py','c_disposable_redis.py']:
        shutil.copy2(source/'scripts'/name,snapshot/'scripts'/name)
    shutil.copy2(source/'deploy/redis-start.sh',snapshot/'deploy/redis-start.sh')
    sys.path.insert(0,str(snapshot/'scripts'))
    spec=importlib.util.spec_from_file_location('c_redis_fixtures',snapshot/'scripts/verify-c-redis.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    proof={'passed':False,'cCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=source,text=True).strip(),'clientSha256':hashlib.sha256((ROOT/'mobile/src/lib/client.js').read_bytes()).hexdigest(),'mysqlImage':args.mysql_image,'redisImage':args.redis_image,'existingServicesUntouched':True,'actualPhone':False,'actualWindowsSdk':False}
    print('Owned test evidence: '+str(folder),flush=True)
    try:
        with module.DisposableMysqlContainer(args.mysql_image,folder/'mysql') as database, module.DisposableRedis(None,folder/'redis',image=args.redis_image) as redis:
            env=os.environ.copy()
            for key in list(env):
                if key.startswith(('SPRING_DATASOURCE_','LIANZHI_ARCSOFT_','LIANZHI_FACE_','LIANZHI_AI_','LIANZHI_EMBEDDING_','LIANZHI_OCR_','LIANZHI_TRANSCRIPTION_')):env.pop(key)
            namespace='lianzhi:bcompat:'+uuid.uuid4().hex
            env.update(DB_URL=f'jdbc:mysql://127.0.0.1:{database.port}/c_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai',DB_USERNAME='c_verify',DB_PASSWORD=database.password,ADMIN_INITIAL_PASSWORD=secrets.token_hex(20),LIANZHI_REDIS_ENABLED='true',LIANZHI_REDIS_HOST='127.0.0.1',LIANZHI_REDIS_PORT=str(redis.port),LIANZHI_REDIS_PASSWORD=redis.password,LIANZHI_REDIS_USERNAME='',LIANZHI_REDIS_TLS='false',LIANZHI_REDIS_NAMESPACE=namespace,LIANZHI_SESSION_TIMEOUT='30m',LIANZHI_FACE_ENABLED='false',LIANZHI_ARCSOFT_ENABLED='false',LIANZHI_KNOWLEDGE_WORKER_ENABLED='false',LIANZHI_AI_BASE_URL='',LIANZHI_AI_MODEL='',LIANZHI_EMBEDDING_BASE_URL='',LIANZHI_EMBEDDING_MODEL='',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='1',SERVER_SERVLET_SESSION_COOKIE_SECURE='false')
            apps=module.Applications(args.java.resolve(),args.c_jar.resolve(),folder,env)
            try:
                first=apps.start('first',profiles='demo,mysql');second=apps.start('second',profiles='demo,mysql')
                child=os.environ.copy();child.update(B_ISOLATED_SESSION_TEST='1',B_SESSION_ORIGINS=json.dumps([first,second]))
                with (folder/'client.log').open('w') as output:
                    result=subprocess.run(['node',str(ROOT/'mobile/scripts/verify-shared-session.mjs')],cwd=ROOT,env=child,stdout=output,stderr=subprocess.STDOUT,timeout=180)
                if result.returncode:raise RuntimeError('B client failed; inspect isolated client.log')
                evidence=json.loads((folder/'client.log').read_text().splitlines()[-1]);proof['client']=evidence
                proof['mysqlVersion']=database.sql('SELECT VERSION()');proof['eventCount']=int(database.sql('SELECT COUNT(*) FROM c_verify.attendance_event'))
                proof['redisSessionKeys']=len(redis.client.keys(namespace+':sessions:*'))
                if proof['eventCount']!=1 or proof['redisSessionKeys']<1:raise RuntimeError('Real persistence evidence missing')
            finally:apps.close()
        proof['passed']=True
    except Exception as error:
        proof['failure']=type(error).__name__+': '+str(error);raise
    finally:
        (folder/'result.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'passed':proof['passed'],'report':str(folder/'result.json')}),flush=True)

if __name__=='__main__':main()
