#!/usr/bin/env python3
"""Single isolated real OCR/ASR inference. Called only by the loopback media bridge."""
import argparse, io, json, math, os, shutil, subprocess, sys
from pathlib import Path

def ocr(path):
    import numpy as np
    from PIL import Image
    from rapidocr import RapidOCR
    engine=RapidOCR();sections=[];total=0
    def recognize(image,page):
        nonlocal total
        if image.width*image.height>16_000_000:raise ValueError('image size')
        # ndarray input follows OpenCV BGR order; no cached expected answer.
        result=engine(np.ascontiguousarray(np.asarray(image.convert('RGB'))[:, :, ::-1]))
        text='\n'.join(text for text,score in zip(result.txts or [],result.scores or []) if score>=.5).strip()
        total+=len(text)
        if total>160_000:raise ValueError('text size')
        if text:sections.append({'source':{'kind':'PAGE','ordinal':page,'name':None,'startMillis':None,'endMillis':None},'text':text})
    if path.suffix.lower()=='.pdf':
        import pymupdf as fitz
        with fitz.open(path) as document:
            if document.is_encrypted or document.page_count>20:raise ValueError('PDF page limit')
            for i in range(document.page_count):
                page=document.load_page(i)
                if page.rect.width*page.rect.height*4>16_000_000:raise ValueError('page pixels')
                pix=page.get_pixmap(matrix=fitz.Matrix(2,2),alpha=False)
                recognize(Image.frombytes('RGB',[pix.width,pix.height],pix.samples),i+1)
    else:
        with Image.open(path) as image:recognize(image,1)
    return {'sections':sections,'engine':'RapidOCR CPU ONNX','quality':'RECOGNIZED_TEXT_REQUIRES_SOURCE_REVIEW'}

def transcription(path,model_dir):
    ffmpeg=shutil.which('ffmpeg');ffprobe=shutil.which('ffprobe')
    if not ffmpeg or not ffprobe:raise ValueError('FFmpeg required')
    kind={'wav':'wav','mp3':'mp3','mp4':'mov','m4a':'mov','webm':'matroska'}.get(path.suffix[1:].lower())
    if not kind:raise ValueError('media type')
    input_options=['-protocol_whitelist','file,pipe','-f',kind]
    if kind=='mov':input_options+=['-enable_drefs','0']
    probe=subprocess.run([ffprobe,'-v','error',*input_options,'-i',str(path),'-show_format','-show_streams','-of','json'],capture_output=True,timeout=10,check=True)
    info=json.loads(probe.stdout);audio=next((s for s in info['streams'] if s.get('codec_type')=='audio'),None)
    if audio is None:raise ValueError('no audio')
    duration=float(info['format'].get('duration',audio.get('duration','nan')))
    if not math.isfinite(duration) or duration<=0 or duration>120:raise ValueError('duration must be measured and at most 120 seconds')
    def measured(value):
        try:return float(value)
        except (TypeError,ValueError):return 0.0
    offset=max(0,measured(audio.get('start_time'))-measured(info['format'].get('start_time')))
    output=path.with_name('normalized.wav')
    subprocess.run([ffmpeg,'-nostdin','-v','error','-y',*input_options,'-i',str(path),'-map','0:a:0','-vn','-sn','-dn','-ac','1','-ar','16000',str(output)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=20,check=True)
    if output.stat().st_size>5*1024*1024:raise ValueError('decoded audio limit')
    from faster_whisper import WhisperModel
    model=WhisperModel(str(model_dir),device='cpu',compute_type='int8',cpu_threads=2,num_workers=1,local_files_only=True)
    segments,result=model.transcribe(str(output),language='zh',beam_size=5,condition_on_previous_text=False,initial_prompt='企业考勤制度，年假、请假、加班、补卡、员工与人事审批。')
    rows=[];total=0
    for segment in segments:
        text=segment.text.strip();start=max(0,int((segment.start+offset)*1000));end=min(math.ceil(duration*1000),math.ceil((segment.end+offset)*1000))
        if not text or end<=start:continue
        total+=len(text)
        if total>160_000 or len(rows)>=1000:raise ValueError('transcript limit')
        if rows and start<rows[-1]['source']['endMillis']:
            rows[-1]['source']['endMillis']=max(end,rows[-1]['source']['endMillis']);rows[-1]['text']+=' '+text
        else:rows.append({'source':{'kind':'TIME_RANGE','ordinal':None,'name':None,'startMillis':start,'endMillis':end},'text':text})
    return {'sections':rows,'engine':'faster-whisper tiny CPU int8','durationSeconds':duration,'quality':'AUTOMATIC_TRANSCRIPT_REQUIRES_AUDIO_REVIEW'}

def main():
    p=argparse.ArgumentParser();p.add_argument('--mode',choices=['ocr','transcribe'],required=True);p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--model',type=Path);args=p.parse_args()
    if args.input.stat().st_size>10*1024*1024:raise ValueError('input limit')
    result=ocr(args.input) if args.mode=='ocr' else transcription(args.input,args.model)
    args.output.write_text(json.dumps(result,ensure_ascii=False),encoding='utf-8')
if __name__=='__main__':main()
