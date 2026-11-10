"""Private append-only reported measurement runs and read-only derived comparisons."""
import hashlib
import json
import time
import uuid
from diagnostics.condition_comparison import validate_run, compare_runs

MAX_RUNS = 16


class MeasurementConflict(ValueError):pass
class MeasurementLimit(ValueError):pass


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def checked_id(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('canonical measurement UUID required')
    return value


class MeasurementRuns:
    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS private_measurement_runs(
                event_id TEXT NOT NULL,subject TEXT NOT NULL,id TEXT NOT NULL,created REAL NOT NULL,
                raw TEXT NOT NULL,hash TEXT NOT NULL,record_hash TEXT NOT NULL,
                PRIMARY KEY(event_id,subject,id))''')

    def _event(self, event_id, principal):
        event = self.store.get(event_id)
        if (event['source'] != 'owner_reported' or not event['evidence_valid']
                or event['data'].get('category') != 'maintenance'
                or not self.store.visible_to(event_id, principal.vehicles, principal.subject)):
            raise KeyError('event not found')
        return event

    @staticmethod
    def decode(raw):
        if not isinstance(raw, bytes) or not 0 < len(raw) <= 256 * 1024:
            raise ValueError('bounded measurement JSON required')
        def unique(pairs):
            data = {}
            for key, value in pairs:
                if key in data:raise ValueError('duplicate measurement field')
                data[key] = value
            return data
        def invalid(_):raise ValueError('non-finite JSON measurement')
        try:return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)
        except RecursionError:raise ValueError('measurement JSON nesting limit') from None

    @staticmethod
    def _record_hash(row):
        context = canonical({key: row[key] for key in ('event_id', 'subject', 'id', 'created', 'hash')})
        return hashlib.sha256(context.encode()+b'\0'+row['raw'].encode()).hexdigest()

    def _view(self, row, event, content=False):
        raw = row['raw']
        if hashlib.sha256(raw.encode()).hexdigest() != row['hash'] or self._record_hash(row) != row['record_hash']:
            raise ValueError('stored measurement integrity failure')
        body = self.decode(raw.encode())
        if (validate_run(body) != raw or body['run_id'] != row['id']
                or body['vehicle_id'] != event['data']['vehicle_id'] or row['event_id'] != event['id']):
            raise ValueError('stored measurement context failure')
        result = {'id': row['id'], 'event_id': row['event_id'], 'received_at': row['created'],
                  'sha256': row['hash'], 'hash_basis': 'canonical_validated_run_utf8',
                  'environment': body['environment'], 'channel': body['channel'],
                  'started_at': body['started_at'], 'sample_count': len(body['samples']),
                  'scope': 'current_subject_only', 'provenance': 'reported_measurements_not_attested',
                  'sensor_original': False, 'assessment_changed': False}
        if content:result.update(canonical_json=raw, data=body)
        return result

    def list(self, event_id, principal):
        event = self._event(event_id, principal)
        with self.store.connect() as db:
            rows = db.execute('SELECT * FROM private_measurement_runs WHERE event_id=? AND subject=? ORDER BY created,id',
                              (event_id, principal.subject)).fetchall()
        if len(rows) > MAX_RUNS:raise ValueError('stored measurement count exceeds limit')
        return {'schema': 'road-guardian.measurement-runs.v1', 'event_id': event_id,
                'scope': 'current_subject_only', 'limit': MAX_RUNS, 'runs': [self._view(row, event) for row in rows]}

    def get(self, event_id, run_id, principal):
        checked_id(run_id); event = self._event(event_id, principal)
        with self.store.connect() as db:
            row = db.execute('SELECT * FROM private_measurement_runs WHERE event_id=? AND subject=? AND id=?',
                             (event_id, principal.subject, run_id)).fetchone()
        if row is None:raise KeyError('measurement not found')
        return self._view(row, event, True)

    def save(self, event_id, principal, body):
        if 'events:measure' not in principal.scopes:raise PermissionError('measurement scope required')
        event = self._event(event_id, principal)
        raw = validate_run(body); digest = hashlib.sha256(raw.encode()).hexdigest()
        if body['vehicle_id'] != event['data']['vehicle_id']:raise ValueError('measurement vehicle mismatch')
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            # Keep the event owner and original stable while inserting the private record.
            if self._event(event_id, principal)['hash'] != event['hash']:
                raise ValueError('case changed during measurement save')
            prior = db.execute('SELECT * FROM private_measurement_runs WHERE event_id=? AND subject=? AND id=?',
                               (event_id, principal.subject, body['run_id'])).fetchone()
            if prior is not None:
                if prior['hash'] != digest:raise MeasurementConflict('measurement UUID reused with different input')
                return self._view(prior, event, True)
            count = db.execute('SELECT COUNT(*) FROM private_measurement_runs WHERE event_id=? AND subject=?',
                               (event_id, principal.subject)).fetchone()[0]
            if count >= MAX_RUNS:raise MeasurementLimit('measurement run limit reached')
            row = {'event_id': event_id, 'subject': principal.subject, 'id': body['run_id'],
                   'created': time.time(), 'raw': raw, 'hash': digest}
            row['record_hash'] = self._record_hash(row)
            db.execute('INSERT INTO private_measurement_runs VALUES(?,?,?,?,?,?,?)',
                       tuple(row[key] for key in ('event_id', 'subject', 'id', 'created', 'raw', 'hash', 'record_hash')))
        return self._view(row, event, True)

    def snapshot(self, event_id, before_id, after_id, principal):
        checked_id(before_id); checked_id(after_id); event = self._event(event_id, principal)
        with self.store.connect() as db:
            rows = db.execute('SELECT * FROM private_measurement_runs WHERE event_id=? AND subject=? AND id IN (?,?)',
                              (event_id, principal.subject, before_id, after_id)).fetchall()
        records = {row['id']: self._view(row, event, True) for row in rows}
        if before_id not in records or after_id not in records:raise KeyError('measurement pair not found')
        result = compare_runs(records[before_id]['data'], records[after_id]['data'])
        if self._event(event_id, principal)['hash'] != event['hash']:
            raise ValueError('case changed during measurement comparison')
        comparison = {'schema': 'road-guardian.case-condition-comparison.v1', 'event_id': event_id,
                'scope': 'current_subject_only', 'case_sha256': event['hash'],
                'before_id': before_id, 'after_id': after_id, 'comparison': result}

        return event, records[before_id], records[after_id], comparison

    def compare(self, event_id, before_id, after_id, principal):
        return self.snapshot(event_id, before_id, after_id, principal)[3]
