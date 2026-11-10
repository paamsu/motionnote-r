"""Private per-subject notes, separate from immutable sensor evidence and verdicts."""
import hashlib
import json
import time
import unicodedata
import uuid


class NoteConflict(Exception):
    pass


class ReviewNotes:
    def __init__(self,store):
        self.store=store
        with store.connect() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS private_review_notes(
                event_id TEXT NOT NULL,subject TEXT NOT NULL,revision INTEGER NOT NULL,
                note TEXT NOT NULL,created REAL NOT NULL,request_key TEXT NOT NULL,request_hash TEXT NOT NULL,
                PRIMARY KEY(event_id,subject,revision),UNIQUE(event_id,subject,request_key))''')

    @staticmethod
    def checked(body):
        if not isinstance(body,dict) or set(body)!={'expected_revision','request_id','note'}:
            raise ValueError('note, expected_revision and request_id required')
        revision,key,note=(body[key] for key in ('expected_revision','request_id','note'))
        if type(revision) is not int or not 0<=revision<2147483647:raise ValueError('invalid note revision')
        if not isinstance(key,str) or str(uuid.UUID(key))!=key:raise ValueError('invalid note request id')
        if not isinstance(note,str) or len(note)>2000 or any(unicodedata.category(c) in ('Cc','Cs') and c not in '\n\t' for c in note):
            raise ValueError('note exceeds text limits or contains control characters')
        return revision,key,note

    def _allowed(self,event_id,principal):
        event=self.store.get(event_id)
        if (not event['evidence_valid'] or not self.store.visible_to(event_id,principal.vehicles,principal.subject)
                or event.get('case_overview') is None):raise KeyError(event_id)

    @staticmethod
    def _view(event_id,row):
        return {'schema':'road-guardian.private-review-note.v1','event_id':event_id,
                'revision':row['revision'] if row else 0,'note':row['note'] if row else '',
                'updated_at':row['created'] if row else None,'scope':'current_subject_only',
                'evidence_statement':False}

    def get(self,event_id,principal):
        self._allowed(event_id,principal)
        with self.store.connect() as db:
            row=db.execute('SELECT * FROM private_review_notes WHERE event_id=? AND subject=? ORDER BY revision DESC LIMIT 1',
                           (event_id,principal.subject)).fetchone()
        return self._view(event_id,row)

    def save(self,event_id,principal,body):
        if 'events:review' not in principal.scopes:raise PermissionError('review scope required')
        revision,key,note=self.checked(body)
        digest=hashlib.sha256(json.dumps([revision,note],ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
        self._allowed(event_id,principal)
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            replay=db.execute('SELECT * FROM private_review_notes WHERE event_id=? AND subject=? AND request_key=?',
                              (event_id,principal.subject,key)).fetchone()
            if replay:
                if replay['request_hash']!=digest:raise NoteConflict()
                return self._view(event_id,replay)
            current=db.execute('SELECT MAX(revision) FROM private_review_notes WHERE event_id=? AND subject=?',
                               (event_id,principal.subject)).fetchone()[0] or 0
            if current!=revision:raise NoteConflict()
            created=time.time()
            db.execute('INSERT INTO private_review_notes VALUES(?,?,?,?,?,?,?)',
                       (event_id,principal.subject,revision+1,note,created,key,digest))
        return self._view(event_id,{'revision':revision+1,'note':note,'created':created})
