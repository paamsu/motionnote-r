"""Immutable local photo-inference evidence; never replaces authored incident policy."""
import hashlib
import io
import json
import math
import re
import time
from pathlib import Path
from PIL import Image, ImageOps

SCHEMA='road-guardian.local-scene-analysis.v1'
ACTION='local_scene_analysis_attached'
TRANSFORM='EXIF display orientation; RGB; aspect-preserving LANCZOS thumbnail max768; metadata stripped'
CLASSES={'pothole','raised_bump','other','unclear'}
FIELDS={'id','input_sha256','visual_analysis_performed','camera_evidence','model_identity_sha256','prompt_sha256','max_tokens','temperature','original_dimensions','model_input_dimensions','model_input_sha256','transform','status','class','reason','format_valid','stream_chunks','elapsed_seconds'}

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha(blob):return hashlib.sha256(blob).hexdigest()
def hexhash(value):return isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value) is not None

def pairs(items):
    result={}
    for key,value in items:
        if key in result:raise ValueError('duplicate JSON key')
        result[key]=value
    return result

def parse(raw):
    try:
        obj=json.loads(raw,object_pairs_hook=pairs)
        if (not isinstance(obj,dict) or set(obj)!={'class','reason'} or not isinstance(obj['class'],str)
                or obj['class'] not in CLASSES or not isinstance(obj['reason'],str) or not 1<=len(obj['reason'].strip())<=1000):raise ValueError('output contract')
        return dict(status='abstained' if obj['class']=='unclear' else 'candidate',**obj,format_valid=True)
    except (ValueError,TypeError):
        return dict(status='abstained',**{'class':'unclear'},reason='Model output did not satisfy the fixed JSON contract.',format_valid=False)

def checked(result,raw,prompt,blob):
    if not isinstance(result,dict) or set(result)!=FIELDS:raise ValueError('complete inference result required')
    if not isinstance(raw,str) or not 1<=len(raw.encode())<=32768 or not isinstance(prompt,str) or not 1<=len(prompt.encode())<=16384:raise ValueError('bounded raw output and prompt required')
    if not isinstance(result['id'],str) or re.fullmatch('[0-9a-f]{32}',result['id']) is None:raise ValueError('inference ID')
    for key in ('input_sha256','model_identity_sha256','prompt_sha256','model_input_sha256'):
        if not hexhash(result[key]):raise ValueError('inference hash')
    if (result['visual_analysis_performed'] is not True or result['camera_evidence'] is not False
            or result['transform']!=TRANSFORM or type(result['max_tokens']) is not int or result['max_tokens']!=256
            or type(result['temperature']) not in (int,float) or result['temperature']!=0
            or type(result['stream_chunks']) is not int or not 1<=result['stream_chunks']<=4096
            or type(result['elapsed_seconds']) not in (int,float) or not 0<=result['elapsed_seconds']<=3600 or not math.isfinite(result['elapsed_seconds'])):raise ValueError('inference execution contract')
    for key,bound in [('original_dimensions',4096),('model_input_dimensions',768)]:
        dims=result[key]
        if not isinstance(dims,list) or len(dims)!=2 or any(type(v) is not int or not 1<=v<=bound for v in dims):raise ValueError('bounded dimensions required')
    parsed=parse(raw)
    if any(type(result[k]) is not type(v) or result[k]!=v for k,v in parsed.items()):raise ValueError('raw output/result mismatch')
    if sha(prompt.encode())!=result['prompt_sha256'] or sha(blob)!=result['model_input_sha256']:raise ValueError('prompt or model input hash mismatch')
    if not 1<=len(blob)<=4*1024*1024:raise ValueError('model image size')
    with Image.open(io.BytesIO(blob),formats=['PNG']) as image:
        if image.mode!='RGB' or list(image.size)!=result['model_input_dimensions'] or getattr(image,'n_frames',1)!=1:raise ValueError('model image contract')
        image.load()
    return parsed

def descriptor(event,result,raw,prompt,blob):
    checked(result,raw,prompt,blob)
    photo=(event.get('video') or {}).get('input_photo') or {}
    if (event.get('source')!='synthetic' or event.get('data',{}).get('source')!='synthetic' or not event.get('evidence_valid')
            or (event.get('video') or {}).get('integrity_valid') is not True or photo.get('integrity_valid') is not True
            or photo.get('event_id')!=event['id'] or photo.get('sensor_sha256')!=event['hash'] or photo.get('sha256')!=result['input_sha256']):raise ValueError('verified same-event synthetic photo required')
    return dict(schema=SCHEMA,event_id=event['id'],sensor_sha256=event['hash'],input_photo_sha256=result['input_sha256'],
        source='local_photo_inference',camera_evidence=False,visual_analysis_performed=True,
        model_identity_sha256=result['model_identity_sha256'],prompt_sha256=result['prompt_sha256'],
        model_input_sha256=result['model_input_sha256'],model_input_bytes=len(blob),transform=result['transform'],
        **{'class':result['class']},status=result['status'],reason=result['reason'],format_valid=result['format_valid'],
        raw_output=raw,raw_output_sha256=sha(raw.encode()),prompt=prompt,result=result,
        limitations=['Synthetic photo inference only; no camera truth, video temporal analysis or general-road accuracy claim.',
                     'Authored incident policy is unchanged; hashes preserve records, not proof of model execution.'])

def record_hash(encoded,blob):return sha(encoded.encode()+b'\0'+blob)

def read(db,event,content=False):
    row=db.execute('SELECT * FROM scene_analyses WHERE event_id=?',(event['id'],)).fetchone()
    history=db.execute('SELECT detail FROM history WHERE event_id=? AND action=? ORDER BY seq',(event['id'],ACTION)).fetchall()
    if row is None and not history:return None
    try:
        if row is None:raise ValueError('scene analysis missing')
        saved=json.loads(row['report'],object_pairs_hook=pairs);blob=bytes(row['model_input'])
        expected=descriptor(event,saved['result'],saved['raw_output'],saved['prompt'],blob)
        if saved!=expected or record_hash(row['report'],blob)!=row['record_hash'] or len(history)!=1 or json.loads(history[0]['detail'])!=expected:raise ValueError('scene analysis binding/history mismatch')
        public=dict(expected,integrity_valid=True)
        return (public,blob) if content else public
    except (ValueError,TypeError,KeyError,OverflowError,OSError,Image.DecompressionBombError):
        if content:raise ValueError('scene analysis integrity failure') from None
        return dict(schema=SCHEMA,event_id=event['id'],sensor_sha256=event['hash'],source='local_photo_inference',camera_evidence=False,visual_analysis_performed=False,status='unavailable',integrity_valid=False)

def attach(store,event_id,result,*,raw_output,prompt,model_input_path):
    with Path(model_input_path).open('rb') as stream:blob=stream.read(4*1024*1024+1)
    event=store.get(event_id)
    expected=descriptor(event,result,raw_output,prompt,blob)
    # Reproduce the actual model preprocessing from the currently bound original.
    with Image.open(store.scene_photo_file(event_id)) as source:
        source.load();image=ImageOps.exif_transpose(source).convert('RGB')
    if list(image.size)!=result['original_dimensions']:raise ValueError('original image dimensions mismatch')
    image.thumbnail((768,768),Image.Resampling.LANCZOS)
    clean=Image.new('RGB',image.size);clean.paste(image);image.close()
    output=io.BytesIO();clean.save(output,format='PNG');clean.close()
    if output.getvalue()!=blob:raise ValueError('model input transform mismatch')
    with store.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        current=store.get(event_id)
        if descriptor(current,result,raw_output,prompt,blob)!=expected:raise ValueError('scene binding changed')
        previous=read(db,current)
        if previous is not None:
            if previous!=dict(expected,integrity_valid=True):raise ValueError('immutable scene analysis conflict')
            return previous
        encoded=canonical(expected)
        db.execute('INSERT INTO scene_analyses VALUES(?,?,?,?)',(event_id,encoded,blob,record_hash(encoded,blob)))
        db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',(event_id,time.time(),ACTION,encoded))
    return dict(expected,integrity_valid=True)
