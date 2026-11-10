import hashlib
import base64
import math
import re
import json
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from incident.policy import assess
from integrations.repair import select_center, repair_case, validate_measurement_pair
from diagnostics.analysis import diagnose
from evidence.video import generate_fixture, sha256
from integrations.insurance import insurance_envelope, insurance_case
from incident.sharing import TTL_SECONDS
from evidence.road_review import road_review
from incident.road_followup import compare as compare_road, followup_case
from evidence.overview import case_overview,CATEGORIES,owner_assessment
from evidence.dossier import dossier
from evidence.traffic_review import traffic_review

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)

class Store:
    def __init__(self, path, profiles=None):
        self.path = str(path)
        self.profiles = profiles
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript('''
              PRAGMA journal_mode=WAL;
              CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, request_key TEXT UNIQUE NOT NULL,
                created REAL NOT NULL, source TEXT NOT NULL, raw TEXT NOT NULL, hash TEXT NOT NULL,
                assessment TEXT NOT NULL, status TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS history(seq INTEGER PRIMARY KEY, event_id TEXT NOT NULL,
                at REAL NOT NULL, action TEXT NOT NULL, detail TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS dispatches(id TEXT PRIMARY KEY, event_id TEXT NOT NULL,
                recipient TEXT NOT NULL, status TEXT NOT NULL, receipt TEXT, UNIQUE(event_id,recipient));
              CREATE TABLE IF NOT EXISTS repairs(event_id TEXT PRIMARY KEY, offer TEXT NOT NULL,
                post_repair TEXT, technician_note TEXT);
              CREATE TABLE IF NOT EXISTS owner_notifications(id TEXT PRIMARY KEY, event_id TEXT UNIQUE NOT NULL,
                vehicle_id TEXT NOT NULL, created REAL NOT NULL, read_at REAL);
              CREATE TABLE IF NOT EXISTS notification_queue(id TEXT PRIMARY KEY, event_id TEXT UNIQUE NOT NULL,
                vehicle_id TEXT NOT NULL, created REAL NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0, available_at REAL NOT NULL,
                lease_token TEXT, lease_until REAL, purpose TEXT NOT NULL DEFAULT 'legacy_event');
              CREATE INDEX IF NOT EXISTS notification_queue_due ON notification_queue(state,available_at);
              CREATE TABLE IF NOT EXISTS media(event_id TEXT PRIMARY KEY, manifest TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS video_visibility_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS video_visibility_frames(event_id TEXT NOT NULL,ordinal INTEGER NOT NULL,image BLOB NOT NULL,PRIMARY KEY(event_id,ordinal));
              CREATE TABLE IF NOT EXISTS video_road_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS video_road_frames(event_id TEXT NOT NULL,ordinal INTEGER NOT NULL,image BLOB NOT NULL,PRIMARY KEY(event_id,ordinal));
              CREATE TABLE IF NOT EXISTS video_collision_frames(event_id TEXT NOT NULL,ordinal INTEGER NOT NULL,image BLOB NOT NULL,PRIMARY KEY(event_id,ordinal));
              CREATE TABLE IF NOT EXISTS video_collision_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS video_lane_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS video_lane_frames(event_id TEXT NOT NULL,ordinal INTEGER NOT NULL,image BLOB NOT NULL,PRIMARY KEY(event_id,ordinal));
              CREATE TABLE IF NOT EXISTS plate_video_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS plate_video_resources(event_id TEXT NOT NULL,name TEXT NOT NULL,image BLOB NOT NULL,PRIMARY KEY(event_id,name));
              CREATE TABLE IF NOT EXISTS plate_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,model_input BLOB NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS scene_analyses(event_id TEXT PRIMARY KEY,report TEXT NOT NULL,model_input BLOB NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS synthetic_surround(event_id TEXT PRIMARY KEY,metadata TEXT NOT NULL,image BLOB NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS synthetic_surround_view_sets(event_id TEXT PRIMARY KEY,metadata TEXT NOT NULL,record_hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS synthetic_surround_view_images(event_id TEXT NOT NULL,direction TEXT NOT NULL,image BLOB NOT NULL,PRIMARY KEY(event_id,direction));
              CREATE TABLE IF NOT EXISTS help_video_sessions(event_id TEXT PRIMARY KEY,payload TEXT NOT NULL,hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS dispatch_payloads(dispatch_id TEXT PRIMARY KEY, payload TEXT NOT NULL, hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS parking_sessions(id TEXT PRIMARY KEY, vehicle_id TEXT NOT NULL, parked_at REAL NOT NULL, raw TEXT NOT NULL, hash TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS road_followups(event_id TEXT NOT NULL, followup_id TEXT NOT NULL,
                linked_at REAL NOT NULL, UNIQUE(event_id,followup_id));
              CREATE TABLE IF NOT EXISTS private_case_owners(event_id TEXT PRIMARY KEY,subject TEXT NOT NULL,
                request_id TEXT NOT NULL,request_hash TEXT NOT NULL,UNIQUE(subject,request_id));
            ''')
            db.execute('BEGIN IMMEDIATE')
            if 'purpose' not in {row[1] for row in db.execute('PRAGMA table_info(notification_queue)')}:
                db.execute("ALTER TABLE notification_queue ADD COLUMN purpose TEXT NOT NULL DEFAULT 'legacy_event'")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def create(self, data, request_key):
        if data.get('source') != 'synthetic':
            raise ValueError('This development endpoint accepts synthetic input only')
        if not isinstance(request_key, str) or not 1 <= len(request_key) <= 100:
            raise ValueError('request_key required, maximum 100 characters')
        raw = canonical(data)
        digest = hashlib.sha256(raw.encode()).hexdigest()
        report = assess(data)
        event_id = str(uuid.uuid4())
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            existing = db.execute('SELECT id, hash FROM events WHERE request_key=?', (request_key,)).fetchone()
            if existing:
                if existing['hash'] != digest:
                    raise ValueError('idempotency key reused with different evidence')
                return self.get(existing['id'])
            db.execute('INSERT INTO events VALUES(?,?,?,?,?,?,?,?)',
                       (event_id, request_key, time.time(), data['source'], raw, digest, canonical(report), report['status']))
            db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                       (event_id, time.time(), 'created', canonical({'source': data['source']})))
        return self.get(event_id)

    @staticmethod
    def _enqueue_comparison(db,event_id,vehicle_id):
        now=time.time()
        db.execute('''INSERT OR IGNORE INTO notification_queue
            (id,event_id,vehicle_id,created,available_at,purpose) VALUES(?,?,?,?,?,?)''',
            (str(uuid.uuid4()),event_id,vehicle_id,now,now,'comparison_available'))

    def get(self, event_id):
        with self.connect() as db:
            row = db.execute('SELECT * FROM events WHERE id=?', (event_id,)).fetchone()
            if not row:
                raise KeyError(event_id)
            result = dict(row)
            result['evidence_valid'] = hashlib.sha256(result['raw'].encode()).hexdigest() == result['hash']
            original_json=result.pop('raw')
            result['data'] = json.loads(original_json)
            result['assessment'] = json.loads(result['assessment'])
            # Read-only UI routing context, never a certificate of comparison validity.
            result['observation_purpose']='general'
            result['road_followup_parent_ids']=[]
            if result['source']=='synthetic' and result['data'].get('source')=='synthetic':
                parents=[row['event_id'] for row in db.execute(
                    'SELECT DISTINCT event_id FROM road_followups WHERE followup_id=? ORDER BY event_id',(event_id,))]
                result['road_followup_parent_ids']=parents
                if parents:result['observation_purpose']='road_followup'
                elif result['assessment'].get('kind')=='observation' and result['data'].get('scenario') in ('healthy','diagnosis'):
                    result['observation_purpose']='voltage_review'
            from evidence.synthetic_surround import read as read_surround
            result['surround']=read_surround(db,event_id,result['hash'],result['evidence_valid']) if result['source']=='synthetic' else None
            from evidence.surround_views import read as read_surround_views
            result['surround_views']=read_surround_views(db,event_id,result['hash'],result['evidence_valid']) if result['source']=='synthetic' else None
            if result['source']=='owner_reported':
                expected=owner_assessment(result['data'])
                result['evidence_valid']=result['evidence_valid'] and result['data'].get('schema')=='road-guardian.owner-record.v1' and result['data'].get('source')=='owner_reported' and result['assessment']==expected and result['status']=='owner_recorded'
                result['assessment']=expected
                owner=db.execute('SELECT request_id FROM private_case_owners WHERE event_id=?',(event_id,)).fetchone()
                result.update(video=None,history=[dict(r) for r in db.execute('SELECT * FROM history WHERE event_id=? ORDER BY seq',(event_id,))],
                    dispatches=[],repair=None,diagnostic_valid=False,insurance_case=None,repair_case=None,
                    road_evidence_review=None,road_followup_case=None,evidence_dossier=None,
                    content_kind='owner_reported_record',sensor_original=False,export_available=result['evidence_valid'],
                    content_original_json=original_json,creation_request_id=owner['request_id'] if owner else None)
                result['case_overview']=case_overview(result)
                return result
            ttl=TTL_SECONDS.get(result['assessment']['kind'])
            result['road_expires_at']=result['created']+ttl if ttl else None
            media = db.execute('SELECT manifest FROM media WHERE event_id=?',(event_id,)).fetchone()
            result['video'] = None
            if media:
                result['video'] = json.loads(media['manifest'])
                from evidence.scene_photo import public as public_scene_photo
                photo=public_scene_photo(result['video'],Path(self.path).parent/'video-fixtures',event_id,result['hash'],result['evidence_valid'])
                if photo is not None:result['video']['input_photo']=photo
                path = Path(result['video'].pop('path'))
                result['video']['integrity_valid'] = path.is_file() and sha256(path) == result['video']['sha256']
                result['video']['url'] = '/api/events/' + event_id + '/video'
            from evidence.scene_analysis import read as read_scene_analysis
            result['scene_analysis']=read_scene_analysis(db,result)
            from evidence.plate_analysis import read as read_plate_analysis
            result['plate_analysis']=read_plate_analysis(db,result,Path(self.path).parent/'video-fixtures')
            from evidence.plate_video_analysis import read as read_plate_video_analysis
            result['plate_video_analysis']=read_plate_video_analysis(db,result,Path(self.path).parent/'video-fixtures')
            from evidence.video_visibility_analysis import read as read_video_visibility
            result['video_visibility_analysis']=read_video_visibility(db,result)
            from evidence.video_road_analysis import read as read_video_road
            result['video_road_analysis']=read_video_road(db,result)
            from evidence.video_collision_analysis import read as read_video_collision
            result['video_collision_analysis']=read_video_collision(db,result)
            from evidence.video_lane_analysis import read as read_video_lane
            result['video_lane_analysis']=read_video_lane(db,result)
            result['history'] = [dict(r) for r in db.execute('SELECT * FROM history WHERE event_id=? ORDER BY seq', (event_id,))]
            result['dispatches'] = [dict(r) for r in db.execute('SELECT * FROM dispatches WHERE event_id=?', (event_id,))]
            from evidence.help_video import read as read_help_video
            result['help_video_session']=read_help_video(db,event_id)
            for dispatch in result['dispatches']:
                saved=db.execute('SELECT payload,hash FROM dispatch_payloads WHERE dispatch_id=?',(dispatch['id'],)).fetchone()
                if saved:
                    dispatch['payload']=json.loads(saved['payload'])
                    dispatch['payload_hash']=saved['hash']
                    dispatch['payload_valid']=hashlib.sha256(saved['payload'].encode()).hexdigest()==saved['hash']
            repair = db.execute('SELECT * FROM repairs WHERE event_id=?',(event_id,)).fetchone()
            result['repair'] = None if not repair else {
                'offer':json.loads(repair['offer']),
                'post_repair':json.loads(repair['post_repair']) if repair['post_repair'] else None,
                'technician_note':repair['technician_note']}
            result['diagnostic_valid'] = result['evidence_valid'] and diagnose(result['data'])==result['assessment'].get('diagnostic')
            post=result['repair']['post_repair'] if result['repair'] else None
            if post:
                try:
                    raw=post['raw']
                    validate_measurement_pair(result['data'],raw)
                    report=diagnose(raw)
                    before=result['assessment']['diagnostic'].get('metrics',{}).get('voltage_median_v')
                    after=report.get('metrics',{}).get('voltage_median_v')
                    delta=round(after-before,3) if after is not None and before is not None else None
                    expected='observed_signal_recovered' if report['verdict']=='no_anomaly_in_observed_signals' else 'follow_up_required'
                    post['integrity_valid']=(result['diagnostic_valid'] and raw.get('source')=='synthetic'
                        and raw.get('vehicle_id')==result['data']['vehicle_id'] and raw.get('baseline')==result['data']['baseline']
                        and hashlib.sha256(canonical(raw).encode()).hexdigest()==post['hash']
                        and report==post['assessment'] and post.get('delta_v')==delta and post['status']==expected)
                except (KeyError,TypeError,ValueError,AttributeError,OverflowError):
                    post['integrity_valid']=False
            result['insurance_case'] = insurance_case(result, time.time(), self.profiles)
            result['repair_case'] = repair_case(result)
            result['road_evidence_review'] = road_review(result)
            result['road_followup_case'] = self._road_followup_case(db,result)
            result['case_overview'] = case_overview(result)
            result['evidence_dossier'] = dossier(result)
            result['traffic_review'] = traffic_review(result)
            return result

    def _road_followup_case(self, db, original):
        links=db.execute('SELECT * FROM road_followups WHERE event_id=?',(original['id'],)).fetchall()
        empty=followup_case(original,count=len(links))
        if empty is None:
            return empty
        # Validate every linked original against its link-time hash before ranking.
        # A corrupt timestamp must never make a newer record disappear from selection.
        try:
            hashes={}
            for entry in original['history']:
                if entry['action']=='synthetic_road_followup_linked':
                    detail=json.loads(entry['detail'])
                    key=detail['followup_id']; value=detail['followup_sha256']
                    if key in hashes and hashes[key]!=value:
                        raise ValueError('conflicting_followup_link_history')
                    hashes[key]=value
            if set(hashes)!={link['followup_id'] for link in links}:
                raise ValueError('followup_link_history_mismatch')
            if not links:
                return empty
            candidates=[]
            for link in links:
                reference=self._road_reference(db.execute('SELECT * FROM events WHERE id=?',(link['followup_id'],)).fetchone())
                if not reference['evidence_valid'] or hashes.get(reference['id'])!=reference['hash']:
                    raise ValueError('linked_observation_integrity_failure')
                comparison=compare_road(original,reference)
                candidates.append((comparison['after']['captured_at'],link['linked_at'],link['followup_id'],reference))
            latest=max(candidates,key=lambda item:item[:3])[3]
            return followup_case(original,latest,len(links))
        except (ValueError,TypeError,KeyError,OverflowError,AttributeError) as error:
            return dict(empty,stage='comparison_invalid',reason=str(error))

    @staticmethod
    def _road_reference(row):
        if row is None: raise KeyError('road observation not found')
        return {'id':row['id'],'source':row['source'],'status':row['status'],'hash':row['hash'],
                'evidence_valid':hashlib.sha256(row['raw'].encode()).hexdigest()==row['hash'],
                'data':json.loads(row['raw']),'assessment':json.loads(row['assessment'])}

    def link_road_followup(self,event_id,followup_id):
        if not isinstance(followup_id,str): raise ValueError('followup_event_id required')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            original=self._road_reference(db.execute('SELECT * FROM events WHERE id=?',(event_id,)).fetchone())
            following=self._road_reference(db.execute('SELECT * FROM events WHERE id=?',(followup_id,)).fetchone())
            compare_road(original,following)
            inserted=db.execute('INSERT OR IGNORE INTO road_followups VALUES(?,?,?)',(event_id,followup_id,time.time())).rowcount
            if inserted:
                db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                           (event_id,time.time(),'synthetic_road_followup_linked',canonical({'followup_id':followup_id,'followup_sha256':following['hash']})))
                self._enqueue_comparison(db,event_id,original['data']['vehicle_id'])
        return self.get(event_id)

    def list(self):
        with self.connect() as db:
            clause,_=self._visibility(None)
            ids = [r[0] for r in db.execute('SELECT id FROM events WHERE '+clause+' ORDER BY created DESC LIMIT 100')]
        return [self.get(i) for i in ids]

    @staticmethod
    def _visibility(subject):
        public="(source!='owner_reported' AND NOT EXISTS(SELECT 1 FROM private_case_owners p WHERE p.event_id=events.id))"
        if subject is None:return public,[]
        return '('+public+' OR EXISTS(SELECT 1 FROM private_case_owners p WHERE p.event_id=events.id AND p.subject=?))',[subject]

    def has_private_cases(self):
        with self.connect() as db:
            return bool(db.execute("SELECT EXISTS(SELECT 1 FROM private_case_owners) OR EXISTS(SELECT 1 FROM events WHERE source='owner_reported')").fetchone()[0])

    def visible_to(self,event_id,vehicles,subject=None):
        if not vehicles:return False
        with self.connect() as db:
            row=db.execute('SELECT source,raw FROM events WHERE id=?',(event_id,)).fetchone()
            if row is None:return False
            owner=db.execute('SELECT subject FROM private_case_owners WHERE event_id=?',(event_id,)).fetchone()
        try:vehicle=json.loads(row['raw']).get('vehicle_id')
        except (ValueError,AttributeError):return False
        if not isinstance(vehicle,str) or vehicle not in vehicles:return False
        if owner is not None:return subject is not None and subject==owner['subject']
        return row['source']!='owner_reported'

    def list_for_vehicles(self, vehicles,subject=None):
        if not vehicles:return []
        with self.connect() as db:
            placeholders=','.join('?' for _ in vehicles)
            clause,private_values=self._visibility(subject)
            ids=[r[0] for r in db.execute(
                f"SELECT id FROM events WHERE json_extract(raw,'$.vehicle_id') IN ({placeholders}) AND {clause} ORDER BY created DESC LIMIT 100",
                tuple(vehicles)+tuple(private_values))]
        return [event for identity in ids if (event:=self.get(identity))['evidence_valid']]

    def page_for_vehicles(self, vehicles, cursor=None, category=None,subject=None):
        if category is not None and category not in ('all','road','maintenance','incident'):
            raise ValueError('invalid case category')
        boundary = None
        if cursor is not None:
            try:
                if not re.fullmatch(r'[A-Za-z0-9_-]{1,256}', cursor):raise ValueError()
                boundary = json.loads(base64.urlsafe_b64decode(cursor + '=' * (-len(cursor) % 4)))
                if (not isinstance(boundary, list) or len(boundary) != (2 if category is None else 3)
                        or type(boundary[0]) not in (int, float) or not math.isfinite(boundary[0])
                        or not isinstance(boundary[1], str) or str(uuid.UUID(boundary[1])) != boundary[1]
                        or (category is not None and boundary[2]!=category)):
                    raise ValueError()
            except (ValueError, TypeError, UnicodeError) as error:
                raise ValueError('invalid event cursor') from error
        if not vehicles:return dict({'events': [], 'next_cursor': None},**({'category':category} if category is not None else {}))
        with self.connect() as db:
            placeholders = ','.join('?' for _ in vehicles)
            query = f"SELECT id,created FROM events WHERE json_valid(raw) AND json_extract(raw,'$.vehicle_id') IN ({placeholders})"
            values = list(vehicles)
            clause,private_values=self._visibility(subject)
            query+=' AND '+clause;values.extend(private_values)
            if category is not None:
                kinds=[kind for kind,(group,_) in CATEGORIES.items() if category=='all' or group==category]
                query += f" AND json_valid(assessment) AND (json_extract(assessment,'$.kind') IN ({','.join('?' for _ in kinds)})"
                values.extend(kinds)
                query+=" OR (json_extract(assessment,'$.kind')='owner_record' AND json_extract(assessment,'$.category') IN ("+','.join('?' for _ in (('road','maintenance','incident') if category=='all' else (category,)))+")))"
                values.extend(('road','maintenance','incident') if category=='all' else (category,))
            if boundary is not None:
                query += ' AND (created < ? OR (created = ? AND id < ?))'
                values.extend([boundary[0], boundary[0], boundary[1]])
            rows = db.execute(query + ' ORDER BY created DESC,id DESC LIMIT 26', values).fetchall()
        events = []
        for row in rows[:25]:
            event = self.get(row['id'])
            if event['evidence_valid']:
                events.append({key: event[key] for key in ('id', 'created', 'source', 'assessment','case_overview')})
        next_cursor = None
        if len(rows) > 25:
            last = rows[24]
            key=[last['created'],last['id']]+([category] if category is not None else [])
            next_cursor = base64.urlsafe_b64encode(json.dumps(key).encode()).decode().rstrip('=')
        return dict({'events': events, 'next_cursor': next_cursor},**({'category':category} if category is not None else {}))

    def confirm(self, event_id, accepted):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT status FROM events WHERE id=?', (event_id,)).fetchone()
            if not row:
                raise KeyError(event_id)
            if row['status'] != 'pending_confirmation':
                raise ValueError('event is not awaiting confirmation')
            status = 'dispatch_ready' if accepted else 'dismissed'
            db.execute('UPDATE events SET status=? WHERE id=?', (status,event_id))
            db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                       (event_id,time.time(),'driver_confirmation',canonical({'accepted': accepted})))
        return self.get(event_id)

    def road_events(self):
        # Sharing must not lose active reports when more than 100 recent events exist.
        with self.connect() as db:
            clause,_=self._visibility(None)
            ids = [r[0] for r in db.execute('SELECT id FROM events WHERE created>? AND '+clause+' ORDER BY created DESC', (time.time()-86400,))]
        return [self.get(event_id) for event_id in ids]

    def save_parking(self,data):
        if data.get('source')!='synthetic':raise ValueError('synthetic parking record required')
        raw=canonical(data);identity=str(uuid.uuid4())
        with self.connect() as db:
            db.execute('INSERT INTO parking_sessions VALUES(?,?,?,?,?)',(identity,data['vehicle_id'],data['parked_at'],raw,hashlib.sha256(raw.encode()).hexdigest()))
        return self.latest_parking(data['vehicle_id'])

    def latest_parking(self,vehicle_id):
        with self.connect() as db:
            row=db.execute('SELECT * FROM parking_sessions WHERE vehicle_id=? ORDER BY parked_at DESC LIMIT 1',(vehicle_id,)).fetchone()
        if row is None:return None
        if hashlib.sha256(row['raw'].encode()).hexdigest()!=row['hash']:raise ValueError('parking record integrity failure')
        return dict(json.loads(row['raw']),id=row['id'],hash=row['hash'])

    def dispatch(self, event_id, recipient=None, fail=False):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM events WHERE id=?',(event_id,)).fetchone()
            if not row:
                raise KeyError(event_id)
            if hashlib.sha256(row['raw'].encode()).hexdigest() != row['hash']:
                raise ValueError('evidence integrity failure')
            report = json.loads(row['assessment'])
            target = recipient or report['recipient']
            allowed = {report['recipient']}
            if report['kind'] == 'diagnosis':
                allowed.update({'bluehands-mock','kia-service-mock','garage-mock'})
            if not target or target not in allowed or not target.endswith('-mock'):
                raise ValueError('recipient not allowed for this event')
            if row['status'] in ('pending_confirmation','dismissed'):
                raise ValueError('driver confirmation required')
            old = db.execute('SELECT * FROM dispatches WHERE event_id=? AND recipient=?',(event_id,target)).fetchone()
            if old and old['status'] == 'acknowledged':
                return dict(old)
            dispatch_id = old['id'] if old else str(uuid.uuid4())
            payload=None
            if target=='insurance-mock':
                payload=insurance_envelope(event_id,json.loads(row['raw']),row['hash'],time.time(),self.profiles)
                if payload['status']!='ready':
                    db.execute('UPDATE events SET status=? WHERE id=?',('insurance_check_required',event_id))
                    db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                               (event_id,time.time(),'insurance_check_required',canonical(payload)))
                    return {'event_id':event_id,'recipient':target,'status':'insurance_check_required','reason':payload['reason']}
                media=db.execute('SELECT manifest FROM media WHERE event_id=?',(event_id,)).fetchone()
                if media:
                    video=json.loads(media['manifest']);path=Path(video['path'])
                    if not path.is_file() or sha256(path)!=video['sha256']:
                        raise ValueError('video integrity failure')
                    payload['evidence'].update(video_status=video['status'],video_sha256=video['sha256'],camera_evidence=False)
                previous=db.execute('SELECT payload,hash FROM dispatch_payloads WHERE dispatch_id=?',(dispatch_id,)).fetchone()
                if previous:
                    if hashlib.sha256(previous['payload'].encode()).hexdigest()!=previous['hash']:
                        raise ValueError('dispatch payload integrity failure')
                    previous_payload=json.loads(previous['payload'])
                    if any(previous_payload[key]!=payload[key] for key in ('vehicle','insurance')):
                        raise ValueError('ownership or policy changed; dispatch review required')
                else:
                    raw_payload=canonical(payload)
                    db.execute('INSERT INTO dispatch_payloads VALUES(?,?,?)',(dispatch_id,raw_payload,hashlib.sha256(raw_payload.encode()).hexdigest()))
            status = 'retry_pending' if fail else 'acknowledged'
            receipt = None if fail else 'MOCK-' + dispatch_id[:8]
            db.execute('INSERT INTO dispatches VALUES(?,?,?,?,?) ON CONFLICT(event_id,recipient) DO UPDATE SET status=excluded.status,receipt=excluded.receipt',
                       (dispatch_id,event_id,target,status,receipt))
            db.execute('UPDATE events SET status=? WHERE id=?',('dispatch_retry' if fail else 'acknowledged',event_id))
            if target == 'owner-app-mock' and not fail:
                data = json.loads(row['raw'])
                db.execute('INSERT OR IGNORE INTO owner_notifications VALUES(?,?,?,?,NULL)',
                           (dispatch_id,event_id,data['vehicle_id'],time.time()))
            db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                       (event_id,time.time(),'mock_dispatch',canonical({'recipient':target,'status':status,'receipt':receipt})))
        return {'id':dispatch_id,'event_id':event_id,'recipient':target,'status':status,'receipt':receipt}

    def owner_inbox(self, vehicle_id):
        with self.connect() as db:
            rows = db.execute('SELECT * FROM owner_notifications WHERE vehicle_id=? ORDER BY created DESC', (vehicle_id,)).fetchall()
        results = []
        for row in rows:
            event = self.get(row['event_id'])
            item = dict(row)
            item.update(title=event['assessment']['title'], kind=event['assessment']['kind'], location=event['data']['location'],
                        evidence_valid=event['evidence_valid'], media=event['data']['media'],
                        plate=event['data']['observations'].get('plate'), source=event['source'], video=event['video'])
            results.append(item)
        return results

    def create_video_fixture(self,event_id,scene_fixture=None,media_root=None):
        event=self.get(event_id)
        if not event['evidence_valid'] or event['source']!='synthetic':
            raise ValueError('valid synthetic event required')
        observations=event['data'].get('observations',{})
        stationary=observations.get('synthetic_stationary_analysis')
        if stationary is not None:
            from acquisition.synthetic_stationary import event_input
            expected_input=event_input(stationary,restricted_zone=observations.get('restricted_zone'))
            if any(observations.get(key)!=expected_input['observations'][key] for key in ('stationary_seconds','stationary_motion')):
                raise ValueError('stationary input must match decoded report')
            supplied=scene_fixture.get('video_sha256') if isinstance(scene_fixture,dict) else (event['video'] or {}).get('original_sha256')
            if supplied!=stationary['video_sha256']:
                raise ValueError('video must match the decoded stationary input')
        motion=event['data'].get('observations',{}).get('synthetic_motion_analysis')
        if isinstance(motion,dict) and motion.get('source')=='synthetic_video_tracking':
            expected=motion.get('video_sha256')
            supplied=scene_fixture.get('video_sha256') if isinstance(scene_fixture,dict) else (event['video'] or {}).get('original_sha256')
            if not isinstance(expected,str) or not re.fullmatch(r'[0-9a-f]{64}',expected) or supplied!=expected:
                raise ValueError('video must match the decoded motion input')
        if event['video']:
            if scene_fixture is not None and (event['video'].get('scene')!=scene_fixture.get('scenario')
                    or event['video'].get('original_sha256')!=scene_fixture.get('video_sha256')
                    or event['video'].get('input_photo',{}).get('sha256')!=scene_fixture.get('photo_sha256')):
                raise ValueError('another synthetic video is already attached')
            return event['video']
        if scene_fixture is None:
            manifest=generate_fixture(Path(self.path).parent/'video-fixtures')
        else:
            from evidence.synthetic_scene import import_scene
            manifest=import_scene(Path(self.path).parent/'video-fixtures',media_root,scene_fixture,event['data']['scenario'],stationary_report=stationary)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            inserted=db.execute('INSERT OR IGNORE INTO media VALUES(?,?)',(event_id,canonical(manifest))).rowcount
            if inserted:
                db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                           (event_id,time.time(),'synthetic_video_added',canonical({'sha256':manifest['sha256'],'camera_evidence':False})))
        attached=self.get(event_id)['video']
        if scene_fixture is not None and (attached.get('scene')!=scene_fixture.get('scenario')
                or attached.get('original_sha256')!=scene_fixture.get('video_sha256')
                or attached.get('input_photo',{}).get('sha256')!=scene_fixture.get('photo_sha256')):
            raise ValueError('another synthetic video won the attachment race')
        return attached

    def video_file(self,event_id):
        event=self.get(event_id)
        if not event['video'] or not event['video']['integrity_valid'] or not event['evidence_valid']:
            raise ValueError('valid video evidence required')
        with self.connect() as db:
            row=db.execute('SELECT manifest FROM media WHERE event_id=?',(event_id,)).fetchone()
        return Path(json.loads(row['manifest'])['path'])

    def scene_photo_file(self,event_id):
        event=self.get(event_id)
        photo=(event.get('video') or {}).get('input_photo')
        if event['source']!='synthetic' or not event['evidence_valid'] or not photo or photo.get('integrity_valid') is not True:
            raise ValueError('valid synthetic scene photo required')
        with self.connect() as db:
            row=db.execute('SELECT manifest FROM media WHERE event_id=?',(event_id,)).fetchone()
        from evidence.scene_photo import checked
        manifest=json.loads(row['manifest'])
        declared=manifest.get('input_photo',{})
        if any(declared.get(key)!=photo.get(key) for key in ('sha256','bytes','mime','width','height')):
            raise ValueError('scene photo relationship changed')
        return checked(manifest,Path(self.path).parent/'video-fixtures')

    def attach_surround(self,event_id,path,expected_sha256):
        from evidence.synthetic_surround import attach
        return attach(self,event_id,path,expected_sha256)

    def surround_bytes(self,event_id):
        from evidence.synthetic_surround import read
        with self.connect() as db:
            row=db.execute('SELECT source,raw,hash FROM events WHERE id=?',(event_id,)).fetchone()
            if row is None or row['source']!='synthetic':raise KeyError(event_id)
            result=read(db,event_id,row['hash'],hashlib.sha256(row['raw'].encode()).hexdigest()==row['hash'],True)
            if result is None:raise KeyError('surround not found')
            return result[1]

    def attach_video_visibility_analysis(self,event_id,result,*,raw_output,prompt,frame_paths):
        from evidence.video_visibility_analysis import attach
        return attach(self,event_id,result,raw_output=raw_output,prompt=prompt,frame_paths=frame_paths)

    def video_visibility_analysis_content(self,event_id):
        from evidence.video_visibility_analysis import read
        event=self.get(event_id)
        with self.connect() as db:result=read(db,event,content=True)
        if result is None:raise ValueError('video visibility analysis missing')
        return result

    def attach_video_road_analysis(self,event_id,result,*,raw_output,prompt,frame_paths):
        from evidence.video_road_analysis import attach
        return attach(self,event_id,result,raw_output=raw_output,prompt=prompt,frame_paths=frame_paths)

    def video_road_analysis_content(self,event_id):
        from evidence.video_road_analysis import read
        event=self.get(event_id)
        with self.connect() as db:result=read(db,event,content=True)
        if result is None:raise ValueError('video road analysis missing')
        return result

    def attach_video_collision_analysis(self,event_id,result,*,raw_output,prompt,frame_paths):
        from evidence.video_collision_analysis import attach
        return attach(self,event_id,result,raw_output=raw_output,prompt=prompt,frame_paths=frame_paths)

    def video_collision_analysis_content(self,event_id):
        from evidence.video_collision_analysis import read
        event=self.get(event_id)
        with self.connect() as db:result=read(db,event,content=True)
        if result is None:raise ValueError('video collision analysis missing')
        return result

    def attach_video_lane_analysis(self,event_id,result,*,raw_output,prompt,frame_paths):
        from evidence.video_lane_analysis import attach
        return attach(self,event_id,result,raw_output=raw_output,prompt=prompt,frame_paths=frame_paths)

    def video_lane_analysis_content(self,event_id):
        from evidence.video_lane_analysis import read
        event=self.get(event_id)
        with self.connect() as db:result=read(db,event,content=True)
        if result is None:raise ValueError('video lane analysis missing')
        return result

    def attach_plate_video_analysis(self,event_id,frames):
        from evidence.plate_video_analysis import attach
        return attach(self,event_id,frames)

    def plate_video_analysis_content(self,event_id):
        from evidence.plate_video_analysis import read
        event=self.get(event_id)
        with self.connect() as db:
            result=read(db,event,Path(self.path).parent/'video-fixtures',content=True)
        if result is None:raise ValueError('plate video analysis missing')
        return result

    def attach_plate_analysis(self,event_id,bundle,model_input_path):
        from evidence.plate_analysis import attach
        return attach(self,event_id,bundle,model_input_path)

    def plate_analysis_content(self,event_id):
        from evidence.plate_analysis import read
        event=self.get(event_id)
        with self.connect() as db:result=read(db,event,Path(self.path).parent/'video-fixtures',content=True)
        if result is None:raise ValueError('plate analysis missing')
        return result

    def attach_scene_analysis(self,event_id,result,*,raw_output,prompt,model_input_path):
        from evidence.scene_analysis import attach
        return attach(self,event_id,result,raw_output=raw_output,prompt=prompt,model_input_path=model_input_path)

    def scene_analysis_content(self,event_id):
        from evidence.scene_analysis import read
        event=self.get(event_id)
        with self.connect() as db:
            result=read(db,event,content=True)
        if result is None:raise ValueError('scene analysis missing')
        return result

    def attach_surround_views(self,event_id,views):
        from evidence.surround_views import attach
        return attach(self,event_id,views)

    def surround_view_bytes(self,event_id,direction):
        from evidence.surround_views import read,DIRECTIONS
        if direction not in DIRECTIONS:raise KeyError('unsupported surround direction')
        with self.connect() as db:
            row=db.execute('SELECT source,raw,hash FROM events WHERE id=?',(event_id,)).fetchone()
            if row is None or row['source']!='synthetic':raise KeyError(event_id)
            valid=hashlib.sha256(row['raw'].encode()).hexdigest()==row['hash'] and json.loads(row['raw']).get('source')=='synthetic'
            result=read(db,event_id,row['hash'],valid,True)
            if result is None or direction not in result[1]:raise KeyError('surround direction missing')
            return result[1][direction]

    def read_notification(self, notification_id, vehicle_id):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM owner_notifications WHERE id=? AND vehicle_id=?', (notification_id,vehicle_id)).fetchone()
            if row is None:
                raise KeyError('notification not found for vehicle')
            if row['read_at'] is None:
                at = time.time()
                db.execute('UPDATE owner_notifications SET read_at=? WHERE id=?', (at,notification_id))
                db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                           (row['event_id'],at,'owner_read',canonical({'notification_id':notification_id})))
            else:
                at = row['read_at']
        return {'id':notification_id, 'read_at':at}

    def repair_offer(self,event_id,brand,warranty):
        event=self.get(event_id)
        if not event['evidence_valid'] or not event['diagnostic_valid'] or event['assessment']['kind']!='diagnosis':
            raise ValueError('valid diagnostic evidence required')
        offer=select_center(brand,warranty)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            old=db.execute('SELECT offer FROM repairs WHERE event_id=?',(event_id,)).fetchone()
            if old:
                return json.loads(old['offer'])
            if offer['status']=='accepted_mock':
                db.execute('INSERT INTO repairs(event_id,offer) VALUES(?,?)',(event_id,canonical(offer)))
                db.execute('UPDATE events SET status=? WHERE id=?',('service_accepted',event_id))
            db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                       (event_id,time.time(),'repair_offer',canonical(offer)))
        return offer

    def repair_complete(self,event_id,post_data,technician_note):
        if post_data.get('source')!='synthetic' or not isinstance(technician_note,str) or not technician_note.strip() or len(technician_note)>1000:
            raise ValueError('synthetic follow-up and technician note required')
        event=self.get(event_id)
        if not event['repair']:
            raise ValueError('accepted service required')
        if not event['evidence_valid']:
            raise ValueError('evidence integrity failure')
        if not event['diagnostic_valid']:
            raise ValueError('diagnostic integrity failure')
        validate_measurement_pair(event['data'],post_data)
        if event['repair']['post_repair']:
            if not event['repair']['post_repair'].get('integrity_valid'):
                raise ValueError('follow-up integrity failure')
            return event['repair']['post_repair']
        report=diagnose(post_data)
        before=event['assessment']['diagnostic']
        pre=before.get('metrics',{}).get('voltage_median_v')
        post=report.get('metrics',{}).get('voltage_median_v')
        result={'source':'synthetic','assessment':report,'raw':post_data,
                'hash':hashlib.sha256(canonical(post_data).encode()).hexdigest(),
                'delta_v':round(post-pre,3) if post is not None and pre is not None else None,
                'status':'observed_signal_recovered' if report['verdict']=='no_anomaly_in_observed_signals' else 'follow_up_required'}
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            changed=db.execute('UPDATE repairs SET post_repair=?,technician_note=? WHERE event_id=? AND post_repair IS NULL',
                               (canonical(result),technician_note,event_id)).rowcount
            if changed:
                db.execute('UPDATE events SET status=? WHERE id=?',('repair_reviewed',event_id))
                db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                           (event_id,time.time(),'post_repair_review',canonical({'status':result['status'],'delta_v':result['delta_v']})))
                self._enqueue_comparison(db,event_id,event['data']['vehicle_id'])
        return self.get(event_id)['repair']['post_repair']
