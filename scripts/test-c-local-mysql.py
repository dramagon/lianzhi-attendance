#!/usr/bin/env python3
"""Use a disposable local mysqld instance. Never connects to the user's existing service."""
import argparse, hashlib, json, os, secrets, shutil, socket, subprocess, time, uuid
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin',type=Path,default=Path(shutil.which('mysql') or 'mysql').resolve().parent)
    args=parser.parse_args();suffix='.exe' if os.name=='nt' else ''
    mysql=args.mysql_bin/('mysql'+suffix);mysqld=args.mysql_bin/('mysqld'+suffix);admin=args.mysql_bin/('mysqladmin'+suffix)
    if not all(p.is_file() for p in [mysql,mysqld,admin]):raise RuntimeError('Local MySQL server/client binaries are required; no database was touched.')
    folder=(ROOT/'artifacts'/('mysql-c-'+uuid.uuid4().hex[:12])).resolve();folder.mkdir(parents=True);data=folder/'data';data.mkdir()
    assert folder.is_relative_to((ROOT/'artifacts').resolve()) and data.resolve().is_relative_to(folder)
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    password=secrets.token_hex(24);root_password=secrets.token_hex(24);client=folder/'client.cnf';root_config=folder/'root.cnf'
    process=None
    def command(argv,**kwargs):return subprocess.check_output([str(a) for a in argv],stderr=subprocess.STDOUT,**kwargs)
    def sql(query,initial=False):
        argv=[mysql,'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port='+str(port),'--user=root','--batch','--skip-column-names'] if initial else [mysql,'--defaults-extra-file='+str(root_config),'--batch','--skip-column-names']
        return command(argv,input=query.encode()).decode().strip()
    def ready(initial=False):
        end=time.monotonic()+90
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('Disposable mysqld exited; inspect the isolated mysql log.')
            try:
                if sql('SELECT 1',initial)=='1':return
            except subprocess.CalledProcessError:pass
            time.sleep(.5)
        raise RuntimeError('Disposable MySQL readiness timed out.')
    server_args=[mysqld,'--no-defaults','--basedir='+str(args.mysql_bin.parent),'--datadir='+str(data),'--bind-address=127.0.0.1','--port='+str(port),'--mysqlx=OFF','--skip-log-bin','--innodb-buffer-pool-size=64M','--max-connections=30','--console']
    env=os.environ.copy();java_home=env.get('JAVA_HOME','');env['PATH']=str(Path(java_home)/'bin')+os.pathsep+env.get('PATH','')
    url=f'jdbc:mysql://127.0.0.1:{port}/c_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
    env.update(SPRING_DATASOURCE_URL=url,SPRING_DATASOURCE_USERNAME='c_verify',SPRING_DATASOURCE_PASSWORD=password,LIANZHI_C_TEST_URL=url,LIANZHI_C_PROVIDER_TEST_URL=url,LIANZHI_C_REPORT_TEST_URL=url,LIANZHI_KNOWLEDGE_WORKER_ENABLED='false')
    maven=['cmd','/c','mvnw.cmd'] if os.name=='nt' else ['sh','./mvnw']
    def tests(classes,label):
        with (folder/(label+'.log')).open('w',encoding='utf-8') as out:
            result=subprocess.run(maven+['-B','-ntp','-Dtest='+','.join(classes),'test'],cwd=ROOT/'backend',env=env,stdout=out,stderr=subprocess.STDOUT)
        if result.returncode:raise RuntimeError(label+' failed; inspect the isolated test log.')
        count=0
        for name in classes:
            files=list((ROOT/'backend/target/surefire-reports').glob('TEST-*.'+name+'.xml'));assert len(files)==1
            report=ET.parse(files[0]).getroot();assert int(report.attrib['failures'])==int(report.attrib['errors'])==int(report.attrib['skipped'])==0;count+=int(report.attrib['tests'])
        return count
    try:
        print('Initializing disposable MySQL in',folder,flush=True)
        with (folder/'initialize.log').open('wb') as out:subprocess.run([str(mysqld),'--no-defaults','--initialize-insecure','--basedir='+str(args.mysql_bin.parent),'--datadir='+str(data),'--console'],stdout=out,stderr=subprocess.STDOUT,check=True)
        log=(folder/'mysql.log').open('ab');process=subprocess.Popen([str(a) for a in server_args],stdout=log,stderr=subprocess.STDOUT);ready(True)
        sql("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+root_password+"'; CREATE DATABASE c_verify CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'c_verify'@'localhost' IDENTIFIED BY '"+password+"'; GRANT ALL PRIVILEGES ON c_verify.* TO 'c_verify'@'localhost';",True)
        root_config.write_text('[client]\nuser=root\npassword='+root_password+'\nhost=127.0.0.1\nport='+str(port)+'\nprotocol=TCP\n');root_config.chmod(0o600)
        print('Running C knowledge, provider protocol and report tests on real MySQL',flush=True)
        count=tests(['KnowledgePersistenceTest','KnowledgeProviderFlowTest','AttendanceReportsTest'],'c-mysql')
        env['LIANZHI_C_RESTART_PHASE']='seed';tests(['KnowledgeMysqlRestartIT'],'before-restart')
        before=sql('SELECT id,version,sha256,processing,chunk_count FROM c_verify.knowledge_document ORDER BY id')
        command([admin,'--defaults-extra-file='+str(root_config),'shutdown']);process.wait(timeout=30)
        process=subprocess.Popen([str(a) for a in server_args],stdout=log,stderr=subprocess.STDOUT);ready()
        after=sql('SELECT id,version,sha256,processing,chunk_count FROM c_verify.knowledge_document ORDER BY id');assert before==after
        env['LIANZHI_C_RESTART_PHASE']='verify';restart=tests(['KnowledgeMysqlRestartIT'],'after-restart')
        result={'database':'MySQL','version':sql('SELECT VERSION()'),'tests':count,'restartTests':restart,'schemaVersions':['1','2','3','4','4.1','5','6'],'documentFingerprint':hashlib.sha256(after.encode()).hexdigest(),'restartPreserved':True,'providerTests':'LOOPBACK_PROTOCOL_FIXTURES_ONLY','existingServiceUntouched':True}
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result,ensure_ascii=False),flush=True)
    finally:
        if process is not None and process.poll() is None:
            try:command([admin,'--defaults-extra-file='+str(root_config),'shutdown']);process.wait(timeout=30)
            except Exception:process.terminate();process.wait(timeout=15)
        for p in [client,root_config]:p.unlink(missing_ok=True)
        # Delete only this script's verified generated data directory after its own process exited.
        assert data.resolve().is_relative_to(folder) and folder.is_relative_to((ROOT/'artifacts').resolve())
        if data.exists():shutil.rmtree(data)
if __name__=='__main__':main()
