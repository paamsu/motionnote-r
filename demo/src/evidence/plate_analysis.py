"""Immutable synthetic plate observations bound to an event's original photo."""
import io,json,time
from pathlib import Path
from PIL import Image,ImageOps
from acquisition.plate_text_agreement import corroborate,parse_localization,LEGACY_PROFILE,KOREAN_PROFILE
from evidence.scene_analysis import canonical,sha,hexhash,pairs

SCHEMA='road-guardian.plate-analysis.v1'
ACTION='plate_analysis_attached'
TRANSFORM='EXIF display orientation; RGB; aspect-preserving LANCZOS thumbnail max1536; metadata stripped'

def observation(photo,bundle,model_image):
    keys={'localization','ocr','raw_output','prompt'}
    if not isinstance(bundle,dict) or set(bundle) not in (keys,keys|{'agreement_profile'}):raise ValueError('complete plate observation bundle required')
    if 'agreement_profile' in bundle and bundle['agreement_profile']!=KOREAN_PROFILE:raise ValueError('unsupported explicit agreement profile')
    loc=bundle['localization'];ocr=bundle['ocr'];raw=bundle['raw_output'];prompt=bundle['prompt']
    if not isinstance(raw,str) or not 1<=len(raw.encode())<=32768 or not isinstance(prompt,str) or not 1<=len(prompt.encode())<=16384:raise ValueError('bounded raw and prompt required')
    if not isinstance(loc,dict) or loc.get('camera_evidence') is not False or loc.get('transform')!=TRANSFORM:raise ValueError('local synthetic inference required')
    if any(not hexhash(loc.get(k)) for k in ['model_identity_sha256','prompt_sha256','model_input_sha256']):raise ValueError('model provenance hashes required')
    parsed=parse_localization(raw)
    if any(type(loc.get(k))is not type(v) or loc.get(k)!=v for k,v in parsed.items()):raise ValueError('raw output disagrees with localization')
    if sha(prompt.encode())!=loc['prompt_sha256'] or sha(model_image)!=loc['model_input_sha256']:raise ValueError('prompt/model image hash mismatch')
    if not 1<=len(model_image)<=16*1024*1024:raise ValueError('bounded model image required')
    agreement=corroborate(photo,loc,ocr,profile=bundle.get('agreement_profile',LEGACY_PROFILE))
    with Image.open(io.BytesIO(photo)) as source:
        source.load();image=ImageOps.exif_transpose(source).convert('RGB')
    image.thumbnail((1536,1536),Image.Resampling.LANCZOS)
    clean=Image.new('RGB',image.size);clean.paste(image);image.close()
    if list(clean.size)!=loc.get('model_input_dimensions'):raise ValueError('model dimensions mismatch')
    output=io.BytesIO();clean.save(output,format='PNG');clean.close()
    if output.getvalue()!=model_image:raise ValueError('model preprocessing mismatch')
    return agreement


def descriptor(event,photo,bundle,model_image):
    declared=(event.get('video') or {}).get('input_photo') or {}
    if (event.get('source')!='synthetic' or event.get('data',{}).get('source')!='synthetic' or event.get('evidence_valid')is not True
        or (event.get('video') or {}).get('integrity_valid')is not True or declared.get('integrity_valid')is not True
        or declared.get('event_id')!=event['id'] or declared.get('sensor_sha256')!=event['hash'] or declared.get('sha256')!=sha(photo)):
        raise ValueError('verified same-event synthetic photo required')
    agreement=observation(photo,bundle,model_image)
    return dict(schema=SCHEMA,event_id=event['id'],sensor_sha256=event['hash'],input_photo_sha256=sha(photo),
                source='local_synthetic_plate_analysis',bundle=bundle,agreement=agreement,
                model_input_sha256=sha(model_image),model_input_bytes=len(model_image),
                requires_review=True,camera_evidence=False,recipient=None,
                limitations=['Synthetic photo only; no vehicle identity, watchlist lookup or automatic incident.',
                             'Hashes bind records, not proof of inference execution.'])

def original(db,event,root):
    from evidence.scene_photo import checked
    row=db.execute('SELECT manifest FROM media WHERE event_id=?',(event['id'],)).fetchone()
    if row is None:raise ValueError('photo missing')
    path=checked(json.loads(row['manifest']),root)
    with path.open('rb') as stream:blob=stream.read(16*1024*1024+1)
    return blob

def read(db,event,root,content=False):
    row=db.execute('SELECT * FROM plate_analyses WHERE event_id=?',(event['id'],)).fetchone()
    history=db.execute('SELECT detail FROM history WHERE event_id=? AND action=?',(event['id'],ACTION)).fetchall()
    if row is None and not history:return None
    try:
        if row is None:raise ValueError('analysis missing')
        saved=json.loads(row['report'],object_pairs_hook=pairs);blob=bytes(row['model_input'])
        expected=descriptor(event,original(db,event,root),saved['bundle'],blob)
        if expected!=saved or sha(row['report'].encode()+b'\0'+blob)!=row['record_hash'] or len(history)!=1 or json.loads(history[0]['detail'])!=expected:raise ValueError('analysis/history mismatch')
        public=dict(expected,integrity_valid=True)
        return (public,blob) if content else public
    except (ValueError,TypeError,KeyError,OverflowError,OSError,Image.DecompressionBombError):
        if content:raise ValueError('plate analysis integrity failure') from None
        return dict(schema=SCHEMA,event_id=event['id'],integrity_valid=False,status='unavailable',requires_review=True,camera_evidence=False,recipient=None)

def attach(store,event_id,bundle,model_input_path):
    with Path(model_input_path).open('rb') as stream:blob=stream.read(16*1024*1024+1)
    root=Path(store.path).parent/'video-fixtures'
    with store.connect() as db:
        db.execute('BEGIN IMMEDIATE');event=store.get(event_id)
        expected=descriptor(event,original(db,event,root),bundle,blob)
        previous=read(db,event,root)
        if previous is not None:
            if previous!=dict(expected,integrity_valid=True):raise ValueError('immutable plate analysis conflict')
            return previous
        encoded=canonical(expected)
        db.execute('INSERT INTO plate_analyses VALUES(?,?,?,?)',(event_id,encoded,blob,sha(encoded.encode()+b'\0'+blob)))
        db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',(event_id,time.time(),ACTION,encoded))
    return dict(expected,integrity_valid=True)
