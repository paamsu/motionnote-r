"""Local contest video-call rehearsal. Never opens a camera or external connection."""
import hashlib,json,time,uuid
from pathlib import Path
from evidence.video import sha256
from incident.policy import assess
SCHEMA='road-guardian.help-video-session.v1'
def canonical(x):return json.dumps(x,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def binding(db,event_id):
    row=db.execute('SELECT * FROM events WHERE id=?',(event_id,)).fetchone()
    if not row:raise ValueError('event missing')
    raw=json.loads(row['raw']);report=json.loads(row['assessment']);recipient=report.get('recipient')
    if (row['source']!='synthetic' or raw.get('source')!='synthetic' or hashlib.sha256(row['raw'].encode()).hexdigest()!=row['hash']
        or report.get('kind') not in ('manual_help','emergency') or recipient not in ('119-mock','112-mock') or row['status']!='acknowledged'):
        raise ValueError('acknowledged synthetic help evidence required')
    assessed=assess(raw)
    if assessed.get('kind')!=report.get('kind') or assessed.get('recipient')!=recipient:raise ValueError('help assessment does not match original')
    delivery=db.execute('SELECT * FROM dispatches WHERE event_id=? AND recipient=?',(event_id,recipient)).fetchone()
    if not delivery or delivery['status']!='acknowledged' or delivery['receipt']!='MOCK-'+delivery['id'][:8]:raise ValueError('matching mock receipt required')
    media=db.execute('SELECT manifest FROM media WHERE event_id=?',(event_id,)).fetchone()
    if not media:raise ValueError('stored scene video required')
    video=json.loads(media['manifest']);path=Path(video['path'])
    if (video.get('source')!='synthetic_scene_fixture' or video.get('camera_evidence') is not False or not path.is_file()
        or path.stat().st_size!=video.get('bytes') or sha256(path)!=video.get('sha256')):raise ValueError('scene video integrity failure')
    return dict(event_id=event_id,original_hash=row['hash'],recipient=recipient,video_sha256=video['sha256'],video_bytes=video['bytes'],dispatch_id=delivery['id'],mock_receipt=delivery['receipt'])
def read(db,event_id):
    row=db.execute('SELECT payload,hash FROM help_video_sessions WHERE event_id=?',(event_id,)).fetchone()
    if not row:return None
    try:
        if hashlib.sha256(row['payload'].encode()).hexdigest()!=row['hash']:return None
        value=json.loads(row['payload']);expected=binding(db,event_id)
        if any(value.get(k)!=v for k,v in expected.items()):return None
        if (value.get('schema')!=SCHEMA or value.get('state')!='connected_mock' or value.get('external_connection') is not False
            or value.get('live_camera') is not False or value.get('camera_evidence') is not False
            or value.get('source')!='stored_synthetic_video' or value.get('delivery_state')!='acknowledged_mock'):return None
        if str(uuid.UUID(value['session_id']))!=value['session_id']:return None
        return value
    except (ValueError,KeyError,TypeError,OSError):return None

def start(store,event_id,expected_hash):
    with store.connect() as db:
        db.execute('BEGIN IMMEDIATE');bound=binding(db,event_id)
        if expected_hash!=bound['original_hash']:raise ValueError('opened evidence changed')
        old=db.execute('SELECT 1 FROM help_video_sessions WHERE event_id=?',(event_id,)).fetchone()
        if old:
            value=read(db,event_id)
            if value is None:raise ValueError('existing video session no longer matches evidence')
            return value
        value=dict(bound,schema=SCHEMA,session_id=str(uuid.uuid4()),state='connected_mock',delivery_state='acknowledged_mock',created_at=time.time(),source='stored_synthetic_video',camera_evidence=False,external_connection=False,live_camera=False)
        payload=canonical(value)
        db.execute('INSERT INTO help_video_sessions VALUES(?,?,?)',(event_id,payload,hashlib.sha256(payload.encode()).hexdigest()))
        db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',(event_id,time.time(),'mock_video_connection',payload))
        return value
