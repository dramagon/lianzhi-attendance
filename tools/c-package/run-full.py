#!/usr/bin/env python3
"""Launch the locally installed real services and verified C package; own processes only."""
import argparse, json, os, secrets, shutil, socket, subprocess, time, urllib.request, webbrowser
from pathlib import Path
import psutil
ROOT=Path(__file__).resolve().parents[2]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--port',type=int,default=8088);p.add_argument('--no-browser',action='store_true');args=p.parse_args()
    if not 1<=args.port<=65535:raise RuntimeError('Invalid application port')
    installed=ROOT/'artifacts';latest=installed/'c-package-latest.json'
    if not latest.is_file():raise RuntimeError('Build the verified package first: python scripts/build-c-package.py')
    jar=Path(json.loads(latest.read_text('utf-8'))['distribution'])/'lianzhi-c.jar'
    if not jar.resolve().is_relative_to(installed.resolve()) or not jar.is_file():raise RuntimeError('Invalid package path')
    ollama=installed/'ollama-cpu/ollama.exe';python=installed/'c-media-venv/Scripts/python.exe';whisper=installed/'local-ai/whisper-tiny'
    if not all([ollama.is_file(),python.is_file(),(whisper/'model.bin').is_file(),shutil.which('ffmpeg')]):raise RuntimeError('Missing local models/runtime or FFmpeg; see c-media-services.md')
    candidates=[Path(os.environ.get('JAVA_HOME',''))/'bin/java.exe']
    if shutil.which('java'):candidates.append(Path(shutil.which('java')))
    candidates+=list((Path.home()/'.jdks').glob('*/bin/java.exe'));java=None
    import re
    for candidate in candidates:
        if candidate.is_file():
            version=subprocess.run([str(candidate),'--version'],capture_output=True,text=True)
            match=re.search(r'\b(\d+)[. ]',version.stdout)
            if match and int(match[1])>=17:java=candidate;break
    if java is None:raise RuntimeError('Java 17 or newer is required')
    # Fail on a collision; never attach to or stop somebody else's listener.
    for port in [11439,11440,args.port]:
        with socket.socket() as probe:probe.bind(('127.0.0.1',port))
    folder=installed/'c-full-data';folder.mkdir(exist_ok=True)
    logs=folder/('run-'+time.strftime('%Y%m%d-%H%M%S'));logs.mkdir()
    env=os.environ.copy();token=secrets.token_hex(32)
    env.update(OLLAMA_HOST='127.0.0.1:11439',OLLAMA_MODELS=str(installed/'local-ai/ollama-models'),OLLAMA_NUM_PARALLEL='1',OLLAMA_MAX_LOADED_MODELS='2',OLLAMA_CONTEXT_LENGTH='8192',LIANZHI_MEDIA_TOKEN=token,
      LIANZHI_AI_BASE_URL='http://127.0.0.1:11439',LIANZHI_AI_MODEL='qwen2.5:1.5b',LIANZHI_AI_TIMEOUT_SECONDS='60',LIANZHI_EMBEDDING_BASE_URL='http://127.0.0.1:11439',LIANZHI_EMBEDDING_MODEL='qwen3-embedding:0.6b',LIANZHI_EMBEDDING_REVISION='1',LIANZHI_OCR_URL='http://127.0.0.1:11440/ocr',LIANZHI_OCR_TOKEN=token,LIANZHI_TRANSCRIPTION_URL='http://127.0.0.1:11440/transcribe',LIANZHI_TRANSCRIPTION_TOKEN=token)
    children=[];handles=[]
    def start(argv,name):
        handle=(logs/(name+'.log')).open('w',encoding='utf-8');handles.append(handle)
        process=subprocess.Popen([str(a) for a in argv],cwd=folder,env=env,stdout=handle,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0);children.append(process);return process
    def wait(url,process):
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            if process.poll() is not None:raise RuntimeError('A service exited; see '+str(logs))
            try:
                with urllib.request.urlopen(url,timeout=2) as response:
                    if response.status==200:return
            except Exception:time.sleep(.5)
        raise RuntimeError('Startup timed out; see '+str(logs))
    try:
        model=start([ollama,'serve'],'ollama');wait('http://127.0.0.1:11439/api/tags',model)
        with urllib.request.urlopen('http://127.0.0.1:11439/api/tags') as response:models={m['name'] for m in json.load(response)['models']}
        if not {'qwen2.5:1.5b','qwen3-embedding:0.6b'}<=models:raise RuntimeError('Required Ollama models have not been downloaded')
        media=start([python,ROOT/'tools/c-media/server.py','--model',whisper],'media');wait('http://127.0.0.1:11440/health',media)
        app=start([java,'-jar',jar,'--spring.profiles.active=demo,standalone','--server.address=127.0.0.1','--server.port='+str(args.port)],'application')
        origin='http://127.0.0.1:'+str(args.port);wait(origin+'/api/auth/csrf',app)
        print('READY '+origin+' | Real local model / embeddings / OCR / transcription. Ctrl+C stops this run.',flush=True)
        print('Demo accounts: admin / hr / employee; password: Lianzhi@2026. Local synthetic data only.',flush=True)
        if not args.no_browser:webbrowser.open(origin)
        while all(c.poll() is None for c in children):time.sleep(.5)
        raise RuntimeError('A service stopped; see '+str(logs))
    except KeyboardInterrupt:print('Stopping this launch; local data is retained.',flush=True)
    finally:
        for child in reversed(children):
            if child.poll() is None:
                try:
                    process=psutil.Process(child.pid)
                    for descendant in process.children(recursive=True):descendant.kill()
                    process.kill();child.wait(timeout=10)
                except psutil.NoSuchProcess:pass
        for handle in handles:handle.close()
if __name__=='__main__':main()
