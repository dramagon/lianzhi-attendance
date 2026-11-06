#!/usr/bin/env python3
"""Extract only official Ollama Windows CPU files through verified HTTP byte ranges.

No installer/system service. HTTPS transport and each ZIP entry CRC are checked;
this is not a claim that the hash of the entire un-downloaded GPU archive was verified.
"""
import collections, io, json, shutil, urllib.request, zipfile, zlib
from pathlib import Path, PurePosixPath
ROOT=Path(__file__).resolve().parents[2]
URL='https://github.com/ollama/ollama/releases/download/v0.40.14/ollama-windows-amd64.zip'
SIZE=1601954968
class Ranges(io.RawIOBase):
    def __init__(self):self.pos=0;self.cache=collections.OrderedDict();self.downloaded=0
    def seekable(self):return True
    def readable(self):return True
    def tell(self):return self.pos
    def seek(self,offset,whence=0):
        target=offset if whence==0 else self.pos+offset if whence==1 else SIZE+offset
        if target<0 or target>SIZE:raise ValueError('Invalid archive position')
        self.pos=target;return target
    def read(self,size=-1):
        remaining=min(SIZE-self.pos,size if size>=0 else SIZE-self.pos);out=bytearray();block_size=4*1024*1024
        while remaining>0:
            block=self.pos//block_size
            if block not in self.cache:
                start=block*block_size;end=min(SIZE,start+block_size)-1
                request=urllib.request.Request(URL,headers={'Range':f'bytes={start}-{end}','User-Agent':'lianzhi-c-local-setup'})
                with urllib.request.urlopen(request,timeout=60) as response:
                    if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {start}-{end}/{SIZE}':raise RuntimeError('Server did not honor the exact bounded range')
                    data=response.read(end-start+2)
                if len(data)!=end-start+1:raise RuntimeError('Truncated archive range')
                self.cache[block]=data;self.downloaded+=len(data);print('CPU archive data received MiB:',round(self.downloaded/1024**2,1),flush=True)
                if len(self.cache)>4:self.cache.popitem(last=False)
            self.cache.move_to_end(block);data=self.cache[block];offset=self.pos-block*block_size;count=min(remaining,len(data)-offset);out.extend(data[offset:offset+count]);self.pos+=count;remaining-=count
        return bytes(out)
def main():
    folder=(ROOT/'artifacts/ollama-cpu').resolve();folder.mkdir(parents=True,exist_ok=True);marker=folder/'installation.json'
    if any(folder.iterdir()) and not marker.exists():raise RuntimeError('Refusing to overwrite an unknown installation directory')
    marker.write_text(json.dumps({'tag':'v0.40.14','complete':False}),encoding='utf-8')
    remote=Ranges();files=[]
    with zipfile.ZipFile(remote) as archive:
        for entry in archive.infolist():
            p=PurePosixPath(entry.filename)
            if not (entry.filename=='ollama.exe' or str(p.parent)=='lib/ollama') or entry.is_dir():continue
            target=(folder/entry.filename).resolve();assert target.is_relative_to(folder)
            if target.exists() and target.stat().st_size==entry.file_size:
                with target.open('rb') as source:
                    crc=0
                    while chunk:=source.read(1024*1024):crc=zlib.crc32(chunk,crc)
                if crc&0xffffffff==entry.CRC:files.append(entry.filename);continue
            target.parent.mkdir(parents=True,exist_ok=True)
            print('Extracting official CPU entry:',entry.filename,flush=True)
            with archive.open(entry) as source,target.open('wb') as output:shutil.copyfileobj(source,output,1024*1024)
            files.append(entry.filename)
    marker.write_text(json.dumps({'tag':'v0.40.14','complete':True,'source':URL,'cpuEntries':files,'downloadedBytes':remote.downloaded,'validation':'HTTPS_EXACT_RANGES_AND_ZIP_ENTRY_CRC','fullArchiveShaVerified':False},indent=2),encoding='utf-8')
    print('CPU runtime installed:',folder,flush=True)
if __name__=='__main__':main()
