#!/usr/bin/env python3
"""Bearer-authenticated, loopback-only JSON OCR/ASR bridge with bounded isolated inference."""
import argparse, base64, hmac, json, os, shutil, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import psutil
ROOT=Path(__file__).resolve().parents[2]
LOCK=threading.Semaphore(1)

def infer(kind,name,content,model):
    suffix=Path(name).suffix.lower()
    allowed={'.png','.jpg','.jpeg','.pdf'} if kind=='ocr' else {'.wav','.mp3','.mp4','.m4a','.webm'}
    if suffix not in allowed:raise ValueError('unsupported type')
    with tempfile.TemporaryDirectory(prefix='c-media-') as folder:
        folder=Path(folder);source=folder/('input'+suffix);output=folder/'result.json';source.write_bytes(content)
        args=[sys.executable,str(Path(__file__).with_name('worker.py')),'--mode',kind,'--input',str(source),'--output',str(output)]
        if kind=='transcribe':args+=['--model',str(model)]
        env=os.environ.copy();env.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',OMP_NUM_THREADS='2')
        flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
        process=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=env,creationflags=flags)
        deadline=time.monotonic()+55
        try:
            while process.poll() is None:
                if time.monotonic()>deadline:raise ValueError('inference timeout')
                try:
                    own=psutil.Process(process.pid);rss=own.memory_info().rss+sum(p.memory_info().rss for p in own.children(recursive=True))
                    if rss>768*1024*1024:raise ValueError('inference memory limit')
                except psutil.NoSuchProcess:pass
                time.sleep(.1)
            if process.returncode!=0 or not output.is_file() or output.stat().st_size>2*1024*1024:raise ValueError('inference failed')
            return json.loads(output.read_text('utf-8'))
        finally:
            if process.poll() is None:
                try:
                    for child in psutil.Process(process.pid).children(recursive=True):child.kill()
                except psutil.NoSuchProcess:pass
                process.kill();process.wait(timeout=5)

def main():
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=11440);p.add_argument('--model',type=Path,default=ROOT/'artifacts/local-ai/whisper-tiny');args=p.parse_args()
    token=os.environ.get('LIANZHI_MEDIA_TOKEN','')
    if len(token)<24:raise RuntimeError('Set a private LIANZHI_MEDIA_TOKEN of at least 24 characters')
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def send(self,status,body):
            data=json.dumps(body,ensure_ascii=False).encode();self.send_response(status);self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
        def do_GET(self):
            if self.path!='/health':self.send(404,{'message':'Not found'});return
            self.send(200,{'ocrConfigured':True,'transcriptionConfigured':args.model.is_dir() and bool(shutil.which('ffmpeg')),'bind':'loopback','inference':'REAL_LOCAL_MODELS'})
        def do_POST(self):
            if self.path not in ['/ocr','/transcribe']:self.send(404,{'message':'Not found'});return
            if not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token):self.send(401,{'message':'Authentication required'});return
            if not LOCK.acquire(blocking=False):self.send(503,{'message':'Media inference is busy'});return
            try:
                length=int(self.headers.get('Content-Length','0'))
                if length<1 or length>15*1024*1024:raise ValueError('request size')
                body=json.loads(self.rfile.read(length));name=body['fileName']
                if not isinstance(name,str) or not name or len(name)>200 or any(ord(c)<32 or c in '/\\' for c in name):raise ValueError('file name')
                content=base64.b64decode(body['base64'],validate=True)
                if not content or len(content)>10*1024*1024:raise ValueError('input size')
                result=infer(self.path[1:],name,content,args.model);self.send(200,result)
            except Exception:self.send(422,{'message':'Media inference failed, was unsupported or exceeded its resource/time limit'})
            finally:LOCK.release()
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler);server.daemon_threads=True
    print('Local real-model media service ready on loopback port',args.port,flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()
if __name__=='__main__':main()
