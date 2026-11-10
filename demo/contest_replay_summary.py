"""Create fresh contest observations without editing preserved sensor input or timestamps."""
from pathlib import Path
import json,hashlib
from evidence.store import Store

def refresh_summary(directory):
    root=Path(directory).resolve()
    database=root/'events.sqlite3'
    preserved=database
    replay=root/'replay-events.sqlite3'
    if replay.exists():raise ValueError('summary replay already prepared')
    old=Store(preserved);fresh=Store(replay);mapping=[]
    for row in reversed(old.list()):
        original=old.get(row['id'])
        if original['assessment']['kind']!='pothole':continue
        if original['source']!='synthetic' or not original['evidence_valid']:raise ValueError('invalid preserved input')
        event=fresh.create(original['data'],'contest-summary-'+original['id'])
        assert event['hash']==original['hash']
        if event['status']=='pending_confirmation' and original['status']=='dispatch_ready':fresh.confirm(event['id'],True)
        elif event['status']=='pending_confirmation' and original['status']=='dismissed':fresh.confirm(event['id'],False)
        if original.get('video'):
            with old.connect() as db:
                manifest=json.loads(db.execute('select manifest from media where event_id=?',(original['id'],)).fetchone()[0])
            photo=Path(manifest['input_photo']['path']);source=Path(manifest['path']).parent/'source.mp4'
            fixture=dict(schema='road-guardian.synthetic-scene.v1',scenario=event['data']['scenario'],
                photo=str(photo.relative_to(root)),photo_sha256=hashlib.sha256(photo.read_bytes()).hexdigest(),
                video=str(source.relative_to(root)),video_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),video_kind=manifest['input_kind'])
            fresh.create_video_fixture(event['id'],fixture,root)
            check=fresh.get(event['id']);assert check['video']['integrity_valid'] and check['video']['input_photo']['integrity_valid']
        mapping.append(dict(preserved_id=original['id'],replay_id=event['id'],raw_sha256=original['hash']))
    (root/'summary-replay.json').write_text(json.dumps(dict(scope='Fresh synthetic replay; preserved inputs and original DB retained',events=mapping),indent=2)+'\n')
    return fresh
