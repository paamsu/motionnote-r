"""Account-private, append-only SDK observations; never a sensor attestation or assessment."""
import hashlib
import json
import math
import time
import uuid


class ObservationConflict(ValueError):pass
class ObservationLimit(ValueError):pass


def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)


class Observations:
    def __init__(self,store):
        self.store=store
        with store.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS private_sdk_observations(
                event_id TEXT NOT NULL,subject TEXT NOT NULL,id TEXT NOT NULL,created REAL NOT NULL,
                raw TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(event_id,subject,id))''')

    def _event(self,event_id,principal):
        event=self.store.get(event_id)
        if (not event['evidence_valid'] or not self.store.visible_to(event_id,principal.vehicles,principal.subject)
                or event.get('case_overview') is None):raise KeyError('event not found')
        return event

    @staticmethod
    def validate(body):
        if not isinstance(body,dict) or set(body)!={'schema','request_id','environment','sdk_version','captured_at','samples'}:
            raise ValueError('observation envelope required')
        if body['schema']!='road-guardian.sdk-observation.v1':raise ValueError('unsupported observation schema')
        key=body['request_id']
        if not isinstance(key,str) or str(uuid.UUID(key))!=key:raise ValueError('canonical request UUID required')
        if body['environment'] not in ('pleos_emulator','vehicle_unattested'):raise ValueError('explicit environment required')
        version=body['sdk_version']
        if not isinstance(version,str) or not 1<=len(version)<=40 or any(c not in '0123456789.-abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ' for c in version):
            raise ValueError('SDK version required')
        captured=body['captured_at']
        if type(captured) not in (int,float) or not 0<captured<=time.time()+300 or not math.isfinite(captured):
            raise ValueError('valid client capture time required')
        samples=body['samples']
        if not isinstance(samples,list) or not 1<=len(samples)<=32:raise ValueError('one to 32 samples required')
        previous=-1
        for sample in samples:
            if not isinstance(sample,dict) or set(sample)!={'elapsed_ms','gear','speed'}:raise ValueError('sample fields required')
            elapsed=sample['elapsed_ms']
            if type(elapsed) is not int or not previous<elapsed<=60000:raise ValueError('ordered elapsed time required')
            previous=elapsed
            for name,key_name in (('gear','value'),('speed','kmh')):
                value=sample[name]
                if not isinstance(value,dict) or set(value)!={key_name,'age_ms'}:raise ValueError('value and receive age required')
                age=value['age_ms']
                if value[key_name] is None:
                    if age is not None:raise ValueError('missing value cannot have a receive age')
                    continue
                if type(age) is not int or not 0<=age<5000:raise ValueError('fresh SDK values required')
                if name=='gear':
                    if value[key_name] not in ('GEAR_PARK','GEAR_REVERSE','GEAR_NEUTRAL','GEAR_DRIVE'):raise ValueError('known gear required')
                elif type(value[key_name]) not in (int,float) or not 0<=value[key_name]<=400 or not math.isfinite(value[key_name]):
                    raise ValueError('finite speed required')
        raw=canonical(body)
        if len(raw.encode())>32768:raise ValueError('observation too large')
        return raw

    @staticmethod
    def _view(row):
        raw=row['raw']
        if hashlib.sha256(raw.encode()).hexdigest()!=row['hash']:raise ValueError('stored observation integrity failure')
        return {'id':row['id'],'received_at':row['created'],'sha256':row['hash'],'canonical_json':raw,'data':json.loads(raw),
                'provenance':'client_reported_not_attested','sensor_original':False,'assessment_changed':False}

    def list(self,event_id,principal):
        self._event(event_id,principal)
        with self.store.connect() as db:
            rows=db.execute('SELECT * FROM private_sdk_observations WHERE event_id=? AND subject=? ORDER BY created,id',
                            (event_id,principal.subject)).fetchall()
        return {'schema':'road-guardian.sdk-observations.v1','event_id':event_id,
                'scope':'current_subject_only','observations':[self._view(row) for row in rows]}

    def save(self,event_id,principal,body):
        if 'events:observe' not in principal.scopes:raise PermissionError('observation scope required')
        event=self._event(event_id,principal)
        raw=self.validate(body);digest=hashlib.sha256(raw.encode()).hexdigest()
        if event['source']=='synthetic' and body['environment']!='pleos_emulator':
            raise ValueError('vehicle observations cannot be attached to synthetic cases')
        key=body['request_id']
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT * FROM private_sdk_observations WHERE event_id=? AND subject=? AND id=?',
                           (event_id,principal.subject,key)).fetchone()
            if row:
                if row['hash']!=digest:raise ObservationConflict('request ID reused with different observation')
                return self._view(row)
            count=db.execute('SELECT COUNT(*) FROM private_sdk_observations WHERE event_id=? AND subject=?',
                             (event_id,principal.subject)).fetchone()[0]
            if count>=64:raise ObservationLimit('observation limit reached')
            created=time.time()
            db.execute('INSERT INTO private_sdk_observations VALUES(?,?,?,?,?,?)',(event_id,principal.subject,key,created,raw,digest))
        return self._view({'id':key,'created':created,'raw':raw,'hash':digest})
