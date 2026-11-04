#!/usr/bin/env python3
"""Build a verified same-origin C distribution; original databases are never included."""
import argparse, json, os, shutil, subprocess, uuid, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--reuse-dependencies',action='store_true');args=parser.parse_args()
    run=(ROOT/'artifacts'/('c-package-'+uuid.uuid4().hex[:12])).resolve();run.mkdir(parents=True)
    npm=['cmd','/c','npm.cmd'] if os.name=='nt' else ['npm'];maven=['cmd','/c','mvnw.cmd'] if os.name=='nt' else ['sh','./mvnw']
    def command(argv,cwd,log):
        with (run/log).open('w',encoding='utf-8') as out:subprocess.run(argv,cwd=cwd,stdout=out,stderr=subprocess.STDOUT,check=True)
    if not args.reuse_dependencies:command(npm+['ci'],ROOT/'frontend','npm-ci.log')
    command(npm+['run','build'],ROOT/'frontend','frontend-build.log')
    target=ROOT/'backend/target/c-web';target.mkdir(parents=True,exist_ok=True)
    # Remove only generated files inside the verified C static-resource directory.
    assert target.resolve().is_relative_to((ROOT/'backend/target').resolve())
    shutil.rmtree(target);shutil.copytree(ROOT/'frontend/dist',target)
    command(maven+['-B','-ntp','-Pstandalone-package','verify'],ROOT/'backend','backend-verify.log')
    jar=ROOT/'backend/target/lianzhi-backend-0.1.0.jar'
    with zipfile.ZipFile(jar) as package:
        names=package.namelist();assert 'BOOT-INF/classes/static/index.html' in names;assert 'BOOT-INF/classes/db/migration/V6__knowledge.sql' in names;assert not any('Simulator' in n or 'Test.class' in n for n in names)
    destination=run/'distribution';destination.mkdir();shutil.copy2(jar,destination/'lianzhi-c.jar')
    for name in ['start-c.ps1','start-c.cmd','start-c.sh']:shutil.copy2(ROOT/'tools/c-package'/name,destination/name)
    # cmd launchers need real CRLF on Windows; never encode literal slash-r/slash-n.
    for name in ['start-c.ps1','start-c.cmd']:
        p=destination/name;p.write_bytes(p.read_text('utf-8').replace('\r\n','\n').replace('\n','\r\n').encode('utf-8'))
    shutil.copy2(ROOT/'deploy/.env.example',destination/'.env.example');shutil.copy2(ROOT/'tools/c-package/README.md',destination/'README.md')
    for source in list((ROOT/'docs/modules').glob('c-*.md'))+[ROOT/'docs/verification/member-c-completion.md']:
        if source.is_file():
            target=destination/source.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    shutil.make_archive(str(run/'lianzhi-c'), 'zip', destination)
    result={'distribution':str(destination),'zip':str(run/'lianzhi-c.zip'),'backendVerified':True,'frontendBuilt':True,'originalDatabaseIncluded':False}
    (run/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result,ensure_ascii=False))
    (ROOT/'artifacts/c-package-latest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':main()
