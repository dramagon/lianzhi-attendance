"""Synthetic HTTP checks shared by isolated container/package verification, never a production client."""
import csv, hashlib, io, json, time, urllib.request, urllib.parse, urllib.error, http.cookiejar, uuid, zipfile
from datetime import datetime, timezone, timedelta

class Client:
    def __init__(self,origin):
        self.origin=origin.rstrip('/');self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(self,path,method='GET',data=None,headers=None,expected=200):
        req=urllib.request.Request(self.origin+path,data=data,headers=headers or {},method=method)
        try:
            with self.opener.open(req,timeout=70) as response:status=response.status;body=response.read(12*1024*1024)
        except urllib.error.HTTPError as error:status=error.code;body=error.read(64*1024)
        if status!=expected:raise RuntimeError(f'{method} {path.split("?")[0]} returned {status}, expected {expected}')
        return body
    def get(self,path):return json.loads(self.request(path))['data']
    def write(self,path,data=None,method='POST',expected=200):
        csrf=self.get('/api/auth/csrf');body=json.dumps(data).encode() if data is not None else None
        return json.loads(self.request(path,method,body,{'Content-Type':'application/json',csrf['headerName']:csrf['token']},expected))
    def login(self,password,username='admin'):
        csrf=self.get('/api/auth/csrf');self.request('/api/auth/login','POST',urllib.parse.urlencode({'username':username,'password':password}).encode(),{'Content-Type':'application/x-www-form-urlencoded',csrf['headerName']:csrf['token']})
    def upload(self,company,content,name='c-policy.docx',visibility='COMPANY'):
        boundary='cverify'+uuid.uuid4().hex;parts=[]
        for key,value in {'companyId':str(company),'visibility':visibility}.items():parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()+content+b'\r\n');parts.append(f'--{boundary}--\r\n'.encode());csrf=self.get('/api/auth/csrf')
        return json.loads(self.request('/api/knowledge/documents','POST',b''.join(parts),{'Content-Type':'multipart/form-data; boundary='+boundary,csrf['headerName']:csrf['token']}))['data']

def document(text):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>'+text+'</w:t></w:r></w:p></w:body></w:document>')
    return out.getvalue()

def verify(client,password):
    for attempt in range(20):
        try:client.login(password);break
        except RuntimeError:
            if attempt==19:raise
            time.sleep(.5)
    assert client.get('/api/knowledge/status')['available']
    company_name='C合成验收-'+uuid.uuid4().hex[:8];client.write('/api/organizations/companies',{'name':company_name,'enabled':True});company=next(c['id'] for c in client.get('/api/organizations/companies?size=100')['items'] if c['name']==company_name)
    content=document('CI合成制度：年假为五天，引用需要核对当前版本。');doc=client.upload(company,content);identifier=doc['id'];deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        doc=client.get('/api/knowledge/documents/'+identifier)
        if doc['processing'] not in ['QUEUED','PROCESSING']:break
        time.sleep(1)
    assert doc['processing']=='PARSED',doc['message'];assert doc['chunkCount']>0
    hits=client.get('/api/knowledge/search?'+urllib.parse.urlencode({'query':'年假','mode':'TEXT'}));hit=next(h for h in hits if h['citation']['documentId']==identifier);citation=hit['citation'];assert citation['chunk']['content'].find('年假')>=0
    assert client.request('/api/knowledge/documents/'+identifier+'/file')==content
    source=f'/api/knowledge/documents/{identifier}/sources/{citation["chunk"]["sequence"]}?version={citation["version"]}';assert client.get(source)==citation
    today=datetime.now(timezone(timedelta(hours=8))).date().isoformat();query=f'?from={today}&to={today}&companyId={company}'
    report=client.get('/api/reports/attendance'+query);assert report['totals']['attendanceRate'] is None;assert report['totals']['scheduledDays']==0
    rows=list(csv.reader(io.StringIO(client.request('/api/reports/attendance/export'+query).decode('utf-8-sig'))));assert len(rows)==1 and len(rows[0])==19
    assert b'id="app"' in client.request('/knowledge')
    anonymous=Client(client.origin);anonymous.request('/api/knowledge/documents',expected=401);anonymous.request('/api/reports/attendance'+query,expected=401)
    client.write('/api/assistant/ask',{'question':'年假'},expected=503)
    client.write('/api/assistant/approval-suggestions',{'instruction':'明天请假'},expected=503)
    return {'documentId':identifier,'version':doc['version'],'sha256':hashlib.sha256(content).hexdigest(),'source':source,'companyId':company,'query':query,'proof':'REAL_DATABASE_AND_PACKAGED_PARSER_NO_MODEL_FIXTURE'}

def after_restart(client,password,proof):
    client.login(password);doc=client.get('/api/knowledge/documents/'+proof['documentId']);assert doc['version']==proof['version'];assert doc['sha256']==proof['sha256'];assert doc['processing']=='PARSED';assert client.get(proof['source'])['documentId']==proof['documentId']
    client.write('/api/knowledge/documents/'+proof['documentId']+'?expectedVersion='+str(doc['version']),method='DELETE');client.request(proof['source'],expected=404)
    hits=client.get('/api/knowledge/search?'+urllib.parse.urlencode({'query':'年假','mode':'TEXT'}));assert all(h['citation']['documentId']!=proof['documentId'] for h in hits)
