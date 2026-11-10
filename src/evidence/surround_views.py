"""Independent authored PNG views; same-event binding is not camera attestation."""
import hashlib
import json
import re
import time
from pathlib import Path
from evidence.synthetic_surround import checked, canonical, MAX_BYTES

DIRECTIONS=('front','rear','left','right')
SCHEMA='road-guardian.synthetic-surround-views.v1'
ACTION='synthetic_surround_views_attached'


def metadata(event_id,sensor_hash,images):
    views=[]
    for direction in DIRECTIONS:
        if direction not in images:continue
        blob=images[direction];width,height=checked(blob)
        views.append(dict(direction=direction,sha256=hashlib.sha256(blob).hexdigest(),bytes=len(blob),
            mime='image/png',width=width,height=height,url=f'/api/events/{event_id}/surround-views/{direction}'))
    if not 1<=len(views)<=4 or set(images)-set(DIRECTIONS) or len({view['sha256'] for view in views})!=len(views):
        raise ValueError('one to four byte-distinct directions required')
    missing=[direction for direction in DIRECTIONS if direction not in images]
    return dict(schema=SCHEMA,event_id=event_id,sensor_sha256=sensor_hash,source='synthetic',camera_evidence=False,
        status='partial' if missing else 'complete',directions=list(DIRECTIONS),missing_directions=missing,views=views)


def record_hash(encoded,images):
    digest=hashlib.sha256(encoded.encode()+b'\0')
    for direction in DIRECTIONS:
        if direction in images:digest.update(direction.encode()+b'\0'+images[direction])
    return digest.hexdigest()


def read(db,event_id,sensor_hash,sensor_valid,content=False):
    row=db.execute('SELECT * FROM synthetic_surround_view_sets WHERE event_id=?',(event_id,)).fetchone()
    image_rows=db.execute('SELECT direction,image FROM synthetic_surround_view_images WHERE event_id=?',(event_id,)).fetchall()
    history=db.execute('SELECT detail FROM history WHERE event_id=? AND action=? ORDER BY seq',(event_id,ACTION)).fetchall()
    if row is None and not image_rows and not history:return None
    try:
        if row is None:raise ValueError('surround view set missing')
        images={item['direction']:bytes(item['image']) for item in image_rows}
        expected=metadata(event_id,sensor_hash,images)
        if (not sensor_valid or json.loads(row['metadata'])!=expected
                or record_hash(row['metadata'],images)!=row['record_hash']
                or len(history)!=1 or json.loads(history[0]['detail'])!=expected):
            raise ValueError('surround views binding or history mismatch')
        result=dict(expected,integrity_valid=True)
        return (result,images) if content else result
    except (ValueError,TypeError,KeyError,OverflowError):
        if content:raise ValueError('surround views integrity failure') from None
        return dict(schema=SCHEMA,event_id=event_id,sensor_sha256=sensor_hash,source='synthetic',camera_evidence=False,
            status='unavailable',integrity_valid=False,directions=list(DIRECTIONS),missing_directions=list(DIRECTIONS),views=[])


def attach(store,event_id,views):
    if not isinstance(views,dict) or not 1<=len(views)<=4 or set(views)-set(DIRECTIONS):
        raise ValueError('explicit supported direction inputs required')
    images={}
    for direction,item in views.items():
        if (not isinstance(item,dict) or set(item)!={'path','sha256'} or not isinstance(item['sha256'],str)
                or re.fullmatch('[0-9a-f]{64}',item['sha256']) is None):
            raise ValueError('each view requires path and SHA-256')
        with Path(item['path']).open('rb') as stream:blob=stream.read(MAX_BYTES+1)
        checked(blob)
        if hashlib.sha256(blob).hexdigest()!=item['sha256']:raise ValueError('view input hash mismatch')
        images[direction]=blob
    with store.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        event=db.execute('SELECT source,raw,hash FROM events WHERE id=?',(event_id,)).fetchone()
        if event is None:raise KeyError(event_id)
        if (event['source']!='synthetic' or hashlib.sha256(event['raw'].encode()).hexdigest()!=event['hash']
                or json.loads(event['raw']).get('source')!='synthetic'):
            raise ValueError('intact synthetic event required')
        expected=metadata(event_id,event['hash'],images)
        previous=read(db,event_id,event['hash'],True)
        if previous is not None:
            if previous!=dict(expected,integrity_valid=True):raise ValueError('immutable surround views conflict')
            return previous
        encoded=canonical(expected)
        db.execute('INSERT INTO synthetic_surround_view_sets VALUES(?,?,?)',(event_id,encoded,record_hash(encoded,images)))
        db.executemany('INSERT INTO synthetic_surround_view_images VALUES(?,?,?)',[(event_id,direction,blob) for direction,blob in images.items()])
        db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',(event_id,time.time(),ACTION,encoded))
        return dict(expected,integrity_valid=True)
