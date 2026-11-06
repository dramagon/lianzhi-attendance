#!/usr/bin/env python3
"""Explicit opt-in real local inference smoke with caller-provided synthetic fixtures."""
import argparse, json, time, urllib.parse
from pathlib import Path
from c_http_verification import Client, document

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--origin',default='http://127.0.0.1:8080');p.add_argument('--fixtures',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    c=Client(args.origin);c.login('Lianzhi@2026','employee');company=c.get('/api/employees/me')['companyId'];proof={'inference':'REAL_LOCAL_MODELS_NOT_PROTOCOL_FIXTURES','documents':[]}
    def upload(name,content,query):
        doc=c.upload(company,content,name,'PRIVATE');deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            doc=c.get('/api/knowledge/documents/'+doc['id'])
            if doc['processing'] not in ['QUEUED','PROCESSING']:break
            time.sleep(1)
        assert doc['processing']=='PARSED',doc
        assert doc['indexing']=='READY',doc
        hits=c.get('/api/knowledge/search?'+urllib.parse.urlencode({'query':query,'mode':'VECTOR','limit':50}));hit=next(h for h in hits if h['citation']['documentId']==doc['id'])
        source=hit['citation'];url=f'/api/knowledge/documents/{doc["id"]}/sources/{source["chunk"]["sequence"]}?version={source["version"]}'
        assert c.get(url)==source
        proof['documents'].append({'name':name,'id':doc['id'],'processing':doc['processing'],'indexing':doc['indexing'],'sourceKind':source['chunk']['source']['kind'],'sourceVerified':True})
        print(name,doc['processing'],doc['indexing'],source['chunk']['source']['kind'],flush=True)
        return doc
    upload('真实模型合成验收.docx',document('年假规定：每年五天。员工须先提交申请，再由人事审批。'),'年假规定')
    answer=c.write('/api/assistant/ask',{'question':'年假规定：每年几天？'})['data'];proof['answer']=answer
    print('REAL_CITED_ANSWER',answer['state'],flush=True)
    # Keep failure evidence instead of silently reporting the model as validated.
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
    assert answer['state']=='ANSWERED',answer
    for suffix,query in [('png','年假'),('pdf','年假'),('wav','审批')]:
        fixture=args.fixtures/('real-asr-fixture.wav' if suffix=='wav' else 'real-ocr-fixture.'+suffix)
        upload(fixture.name,fixture.read_bytes(),query)
    before=c.get('/api/assistant/approvals')['total']
    suggestion=c.write('/api/assistant/approval-suggestions',{'instruction':'申请2026年11月5日09:00到2026年11月5日18:00请假，原因是个人事务。只生成草稿。'})['data'];proof['suggestion']=suggestion
    assert c.get('/api/assistant/approvals')['total']==before
    proof['suggestionCreatedApplications']=False
    print('REAL_SUGGESTION_READY',suggestion['ready'],'NO_APPLICATION_CREATED',flush=True)
    args.output.write_text(json.dumps(proof,ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':main()
