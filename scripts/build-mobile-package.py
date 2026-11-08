#!/usr/bin/env python3
"""Package only compiled uni-app H5/Weixin assets and public instructions."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def main():
    mobile=ROOT/'mobile'
    outputs={'h5':mobile/'dist/build/h5','mp-weixin':mobile/'dist/build/mp-weixin'}
    for target,folder in outputs.items():
        sentinel=folder/('index.html' if target=='h5' else 'app.json')
        if not sentinel.is_file(): raise RuntimeError(f'Build {target} first')
        sources=[*(mobile/'src').rglob('*'),mobile/'package-lock.json',mobile/'vite.config.js']
        if any(p.is_file() and p.stat().st_mtime>sentinel.stat().st_mtime for p in sources): raise RuntimeError(f'{target} build is older than source; rebuild')
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip())
    artifact=ROOT/'artifacts'/('lianzhi-mobile-'+commit[:7]+'.zip')
    artifact.parent.mkdir(exist_ok=True)
    entries={}
    for target,folder in outputs.items():
        for path in folder.rglob('*'):
            relative=path.relative_to(folder)
            allowed=(relative.as_posix()=='index.html' or relative.parts[0] in ('assets','static')) if target=='h5' else (relative.as_posix() in ('app.js','app.json','app.wxss','project.config.json') or relative.parts[0] in ('common','pages','lib','static'))
            if allowed and path.is_file() and not path.name.startswith('.') and path.suffix in ('.js','.css','.html','.json','.wxml','.wxss','.wxs','.svg','.png','.jpg','.jpeg','.gif','.webp','.ico','.woff','.woff2','.ttf'):
                entries[f'{target}/{path.relative_to(folder).as_posix()}']=path.read_bytes()
    entries['README.md']=(mobile/'README.md').read_bytes()
    entries['Caddyfile.example']=(mobile/'Caddyfile.example').read_bytes()
    entries['acceptance.md']=(ROOT/'docs/team/member-b-mobile-checklist.md').read_bytes()
    entries['tools/check-face-environment.py']=(ROOT/'scripts/check-face-environment.py').read_bytes()
    hashes={name:hashlib.sha256(data).hexdigest() for name,data in entries.items()}
    manifest={'commit':commit,'working_tree_dirty':dirty,'targets':['h5','mp-weixin'],'actual_windows_sdk_run':False,'actual_phone_run':False,'actual_weixin_run':False,'fileHashes':hashes}
    entries['manifest.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode()
    with zipfile.ZipFile(artifact,'w',zipfile.ZIP_DEFLATED) as package:
        for name,data in entries.items(): package.writestr('lianzhi-mobile/'+name,data)
    digest=hashlib.sha256(artifact.read_bytes()).hexdigest()
    artifact.with_suffix('.zip.sha256').write_text(digest+'  '+artifact.name+'\n')
    print(json.dumps({'archive':str(artifact),'bytes':artifact.stat().st_size,'files':len(entries),'commit':commit,'dirty':dirty}))

if __name__=='__main__': main()
