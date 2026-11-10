import argparse
import os
import json
import time
import re
import threading
from http.server import BaseHTTPRequestHandler
from backend.http_server import BoundedHTTPServer
from backend.transfer import transfer_deadline
from pathlib import Path
from urllib.parse import urlsplit
from acquisition.scenarios import CATALOG, generate
from evidence.store import Store
from incident.sharing import aggregate, recipients, demo_feed, demo_road_summary, demo_road_group, RoadGroupChanged
from integrations.insurance import profile_for
from parking.locator import fixture as parking_fixture, elevator_cue, locate
from evidence.package import prepared_package
from parking.arrival import ArrivalRelay
from backend.access import AccessPolicy, AccessDenied, AccessUnavailable, ReviewDenied
from evidence.review_notes import ReviewNotes, NoteConflict
from backend.capabilities import capabilities
from evidence.observations import Observations, ObservationConflict, ObservationLimit
from evidence.owner_cases import OwnerCases, CaseConflict, CaseLimit
from evidence.case_photos import CasePhotos, PhotoConflict, PhotoLimit
from integrations.pleos_powertrain import PleosPowertrainClient, PowertrainReadError
from integrations.pleos_consents import PleosConsentRegistry
from evidence.measurement_runs import MeasurementRuns, MeasurementConflict, MeasurementLimit
from evidence.measurement_package import build_measurement_package

def handler(store, access=None, review_enabled=False, observations_enabled=False,owner_cases_enabled=False,photos_enabled=False,powertrain=None,measurements_enabled=False):
    if access is None and (owner_cases_enabled or photos_enabled or powertrain is not None or measurements_enabled or store.has_private_cases()):
        raise ValueError('private cases require an authenticated server')
    notes=ReviewNotes(store) if access is not None and review_enabled else None
    observations=Observations(store) if access is not None and observations_enabled else None
    owner_cases=OwnerCases(store) if access is not None and owner_cases_enabled else None
    photos=CasePhotos(store) if access is not None and photos_enabled else None
    measurements=MeasurementRuns(store) if access is not None and measurements_enabled else None
    arrivals = ArrivalRelay(store)
    export_slots = threading.BoundedSemaphore(2)
    photo_slots = threading.BoundedSemaphore(2)
    powertrain_slots = threading.BoundedSemaphore(2)
    measurement_slots = threading.BoundedSemaphore(2)
    class Handler(BaseHTTPRequestHandler):
        def respond(self, status, body):
            content = json.dumps(body, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(content)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            self.wfile.write(content)

        def send_export(self,event_id,review_note=None,private_observations=None,photo_principal=None):
            if not export_slots.acquire(blocking=False):
                return self.respond(503,{'error':'evidence downloads busy; retry later'})
            headers_started=False
            try:
                photo_bundle=photos.bundle(event_id,photo_principal) if photos is not None and photo_principal is not None else None
                with prepared_package(store,event_id,review_note,private_observations,photo_bundle) as (payload,size), transfer_deadline(self.connection,60) as check_transfer:
                    headers_started=True
                    self.send_response(200)
                    self.send_header('Content-Type','application/zip')
                    self.send_header('Content-Disposition','attachment; filename="road-guardian-evidence.zip"')
                    self.send_header('Content-Length',str(size))
                    self.send_header('Cache-Control','no-store')
                    self.send_header('X-Content-Type-Options','nosniff')
                    self.end_headers()
                    while block:=payload.read(65536):
                        check_transfer()
                        self.wfile.write(block)
            except OSError:
                if headers_started:self.close_connection=True
                else:self.respond(503,{'error':'evidence storage temporarily unavailable'})
            finally:
                export_slots.release()

        def do_GET(self):
            path = urlsplit(self.path).path
            if access is None and store.has_private_cases():return self.respond(503,{'error':'authenticated server required for this datastore'})
            try:
                parts=path.strip('/').split('/')
                principal=None
                if access is None and len(parts)>=4 and parts[:2]==['api','events'] and parts[3] in ('powertrain-data','measurement-runs','measurement-comparison'):
                    return self.respond(404,{'error':'not found'})
                if access is not None and path!='/health':
                    try:
                        principal=access.principal(self.headers.get('Authorization'))
                        vehicles=principal.vehicles
                    except AccessDenied:return self.respond(401,{'error':'authentication required'})
                    except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                    if path=='/api/capabilities':
                        if principal is None:
                            try:principal=access.principal(self.headers.get('Authorization'))
                            except AccessDenied:return self.respond(401,{'error':'authentication required'})
                            except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                        return self.respond(200,capabilities(principal,notes is not None,observations is not None,owner_cases is not None,photos is not None,powertrain is not None,measurements is not None))
                    if path=='/api/my-vehicles':
                        return self.respond(200,{'schema':'road-guardian.registered-vehicles.v1','vehicles':[{'id':identity,'identity_status':'service_grant_only'} for identity in sorted(vehicles)],'ownership_verified':False})
                    if len(parts) in (2,3) and parts[:2]==['api','event-pages']:
                        return self.respond(200,store.page_for_vehicles(vehicles,parts[2] if len(parts)==3 else None,subject=principal.subject))
                    if len(parts) in (3,4) and parts[:2]==['api','case-pages']:
                        return self.respond(200,store.page_for_vehicles(vehicles,parts[3] if len(parts)==4 else None,parts[2],subject=principal.subject))
                    if path=='/api/events':return self.respond(200,store.list_for_vehicles(vehicles,subject=principal.subject))
                    if measurements is not None and len(parts) in (4,5) and parts[:2]==['api','events'] and parts[3]=='measurement-runs':
                        return self.respond(200,measurements.get(parts[2],parts[4],principal) if len(parts)==5 else measurements.list(parts[2],principal))
                    if measurements is not None and len(parts)==7 and parts[:2]==['api','events'] and parts[3]=='measurement-comparison' and parts[6]=='export':
                        if not export_slots.acquire(blocking=False):return self.respond(503,{'error':'evidence downloads busy'})
                        try:
                            if not measurement_slots.acquire(blocking=False):return self.respond(503,{'error':'measurement comparisons busy'})
                            try:
                                snapshot=measurements.snapshot(parts[2],parts[4],parts[5],principal)
                                payload=build_measurement_package(*snapshot)
                            finally:measurement_slots.release()
                            try:current=access.principal(self.headers.get('Authorization'))
                            except AccessDenied:return self.respond(401,{'error':'authentication required'})
                            except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                            if current.subject!=principal.subject or not store.visible_to(parts[2],current.vehicles,current.subject) or store.get(parts[2])['hash']!=snapshot[0]['hash']:
                                return self.respond(404,{'error':'event not found'})
                            with transfer_deadline(self.connection,60) as check_transfer:
                                self.send_response(200)
                                self.send_header('Content-Type','application/zip')
                                self.send_header('Content-Disposition','attachment; filename="naepyeon-measurement-comparison.zip"')
                                self.send_header('Content-Length',str(len(payload)))
                                self.send_header('Cache-Control','no-store')
                                self.send_header('X-Content-Type-Options','nosniff')
                                self.end_headers()
                                try:
                                    for offset in range(0,len(payload),65536):
                                        check_transfer();self.wfile.write(payload[offset:offset+65536])
                                except OSError:self.close_connection=True
                            return
                        finally:export_slots.release()
                    if measurements is not None and len(parts)==6 and parts[:2]==['api','events'] and parts[3]=='measurement-comparison':
                        if not measurement_slots.acquire(blocking=False):return self.respond(503,{'error':'measurement comparisons busy'})
                        try:
                            result=measurements.compare(parts[2],parts[4],parts[5],principal)
                            try:current=access.principal(self.headers.get('Authorization'))
                            except AccessDenied:return self.respond(401,{'error':'authentication required'})
                            except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                            if current.subject!=principal.subject or not store.visible_to(parts[2],current.vehicles,current.subject):
                                return self.respond(404,{'error':'event not found'})
                            if store.get(parts[2])['hash']!=result['case_sha256']:return self.respond(404,{'error':'event not found'})
                            return self.respond(200,result)
                        finally:measurement_slots.release()
                    if len(parts)==6 and parts[:2]==['api','events'] and parts[3]=='powertrain-data':
                        if powertrain is None:return self.respond(404,{'error':'not found'})
                        if 'events:powertrain' not in principal.scopes:return self.respond(403,{'error':'powertrain scope required'})
                        event=store.get(parts[2])
                        def eligible(item,identity):
                            return (item['evidence_valid'] and item['source']=='owner_reported'
                                    and item['data'].get('category')=='maintenance'
                                    and store.visible_to(parts[2],identity.vehicles,identity.subject))
                        if not eligible(event,principal):return self.respond(404,{'error':'event not found'})
                        if not powertrain_slots.acquire(blocking=False):return self.respond(503,{'error':'powertrain reads busy'})
                        try:
                            try:batch=powertrain.fetch(principal.subject,event['data']['vehicle_id'],parts[4],parts[5])
                            except PowertrainReadError as error:
                                code=str(error)
                                statuses={'date_window_required':400,'consent_or_provider_token_required':403,
                                          'provider_sign_in_required':403,'provider_access_denied':403,
                                          'provider_rate_limited':429,'consent_changed':403}
                                return self.respond(statuses.get(code,503),{'error':code})
                            # Recheck app authorization too; provider consent is a separate gate.
                            try:current=access.principal(self.headers.get('Authorization'))
                            except AccessDenied:return self.respond(401,{'error':'authentication required'})
                            except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                            if current.subject!=principal.subject or 'events:powertrain' not in current.scopes:
                                return self.respond(403,{'error':'powertrain access changed'})
                            latest=store.get(parts[2])
                            if latest['hash']!=event['hash'] or not eligible(latest,current):return self.respond(404,{'error':'event not found'})
                            return self.respond(200,{'schema':'road-guardian.case-powertrain.v1','event_id':parts[2],
                                'scope':'current_subject_only','start_date':parts[4],'end_date':parts[5],'batch':batch})
                        finally:powertrain_slots.release()
                    if photos is not None and len(parts) in (4,5) and parts[:2]==['api','events'] and parts[3]=='photos':
                        return self.respond(200,photos.get(parts[2],parts[4],principal) if len(parts)==5 else photos.list(parts[2],principal))
                    if observations is not None and len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='sdk-observations':
                        if principal is None:principal=access.principal(self.headers.get('Authorization'))
                        return self.respond(200,observations.list(parts[2],principal))
                    if notes is not None and len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='review-note':
                        return self.respond(200,notes.get(parts[2],principal))
                    if not (parts[:2]==['api','events'] and (
                            (len(parts) in (3,4) and (len(parts)==3 or parts[3] in ('video','export','surround','scene-photo')))
                            or (len(parts)==5 and parts[3]=='surround-views'))):
                        return self.respond(404,{'error':'not found'})
                    event=store.get(parts[2])
                    if not event['evidence_valid'] or not store.visible_to(parts[2],vehicles,principal.subject):
                        return self.respond(404,{'error':'event not found'})
                if len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='scene-photo':
                    import hashlib
                    path=store.scene_photo_file(parts[2])
                    with path.open('rb') as source:payload=source.read(8*1024*1024+1)
                    photo=(store.get(parts[2]).get('video') or {}).get('input_photo') or {}
                    if (photo.get('integrity_valid') is not True or len(payload)!=photo.get('bytes')
                            or hashlib.sha256(payload).hexdigest()!=photo.get('sha256')):
                        raise ValueError('scene photo changed during read')
                    if access is not None:
                        try:current=access.principal(self.headers.get('Authorization'))
                        except AccessDenied:return self.respond(401,{'error':'authentication required'})
                        except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                        if current.subject!=principal.subject or not store.visible_to(parts[2],current.vehicles,current.subject):
                            return self.respond(404,{'error':'event not found'})
                    self.send_response(200)
                    self.send_header('Content-Type',photo['mime'])
                    self.send_header('Content-Length',str(len(payload)))
                    self.send_header('Cache-Control','no-store')
                    self.send_header('X-Content-Type-Options','nosniff')
                    self.end_headers()
                    with transfer_deadline(self.connection,30) as check_transfer:
                        for start in range(0,len(payload),65536):
                            check_transfer();self.wfile.write(payload[start:start+65536])
                    return
                if parts[:2]==['api','events'] and ((len(parts)==4 and parts[3]=='surround')
                        or (len(parts)==5 and parts[3]=='surround-views')):
                    payload=store.surround_bytes(parts[2]) if len(parts)==4 else store.surround_view_bytes(parts[2],parts[4])
                    self.send_response(200)
                    self.send_header('Content-Type','image/png')
                    self.send_header('Content-Length',str(len(payload)))
                    self.send_header('Cache-Control','no-store')
                    self.send_header('X-Content-Type-Options','nosniff')
                    self.end_headers()
                    with transfer_deadline(self.connection,30) as check_transfer:
                        for start in range(0,len(payload),65536):
                            check_transfer();self.wfile.write(payload[start:start+65536])
                    return
                if len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='export':
                    review=notes.get(parts[2],principal) if notes is not None and event.get('case_overview') is not None else None
                    private_observations=observations.list(parts[2],principal) if observations is not None and event['source']=='owner_reported' else None
                    photo_principal=principal if principal is not None and event['source']=='owner_reported' else None
                    return self.send_export(parts[2],review,private_observations,photo_principal)
                if len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='video':
                    with store.video_file(parts[2]).open('rb') as video:
                        size=os.fstat(video.fileno()).st_size;start=0;end=size-1;status=200
                        requested=self.headers.get('Range')
                        if requested:
                            match=re.fullmatch(r'bytes=(\d+)-(\d*)',requested)
                            if not match:
                                return self.respond(416,{'error':'unsupported byte range'})
                            start=int(match[1]);end=min(int(match[2]) if match[2] else size-1,size-1)
                            if start>=size or end<start:return self.respond(416,{'error':'range outside video'})
                            status=206
                        self.send_response(status)
                        self.send_header('Content-Type','video/mp4')
                        self.send_header('Accept-Ranges','bytes')
                        self.send_header('Content-Length',str(end-start+1))
                        self.send_header('Cache-Control','no-store')
                        self.send_header('X-Content-Type-Options','nosniff')
                        if status==206:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
                        self.end_headers()
                        video.seek(start)
                        remaining=end-start+1
                        try:
                            while remaining>0:
                                block=video.read(min(65536,remaining))
                                if not block:
                                    self.close_connection=True
                                    return
                                self.wfile.write(block)
                                remaining-=len(block)
                        except (BrokenPipeError,ConnectionResetError):
                            self.close_connection=True
                        return
                if path == '/health':
                    return self.respond(200, {'status': 'ok', 'mode': 'authenticated-private-photos' if photos is not None else 'authenticated-private-cases' if owner_cases is not None else 'authenticated-sdk-observations' if observations is not None else 'authenticated-private-notes' if notes is not None else 'authenticated-read-only' if access else 'synthetic-only', 'external_dispatch': False})
                if path == '/api/scenarios':
                    return self.respond(200, [{'id': k, 'title': v} for k,v in CATALOG.items()])
                if path == '/api/events':
                    return self.respond(200, store.list())
                if path == '/api/hazards':
                    return self.respond(200, aggregate(store.road_events(), time.time()))
                if path == '/api/demo/road-summary':
                    return self.respond(200, demo_road_summary(store.road_events(), time.time()))
                if path.startswith('/api/demo/road-summary/groups/'):
                    route=path.removeprefix('/api/demo/road-summary/groups/').split('/')
                    if len(route) not in (1,3) or urlsplit(self.path).query:
                        raise ValueError('bounded source page route required')
                    group_id=route[0];offset=route[1] if len(route)==3 else '0'
                    if not offset.isascii() or not offset.isdigit():raise ValueError('source page offset required')
                    return self.respond(200,demo_road_group(store.road_events(),time.time(),group_id,int(offset),route[2] if len(route)==3 else None))
                if path == '/api/demo/approaching-hazards':
                    return self.respond(200, demo_feed(store.road_events(), time.time()))
                if path == '/api/demo/owner/inbox':
                    return self.respond(200, {'mode':'synthetic-only','vehicle_id':'demo-hyundai-01','notifications':store.owner_inbox('demo-hyundai-01')})
                if path == '/api/demo/vehicle':
                    return self.respond(200, profile_for('demo-hyundai-01',store.profiles))
                if path == '/api/demo/parking':
                    return self.respond(200,store.latest_parking('demo-hyundai-01'))
                if path == '/api/dev/parking-arrival':
                    return self.respond(200, arrivals.latest())
                if path.startswith('/api/events/'):
                    return self.respond(200, store.get(path.split('/')[3]))
                return self.respond(404, {'error': 'not found'})
            except RoadGroupChanged as exc:
                return self.respond(409,{'error':str(exc)})
            except KeyError:
                return self.respond(404, {'error': 'event not found'})
            except ValueError as exc:
                return self.respond(400, {'error':str(exc)})

        def do_POST(self):
            if access is None and store.has_private_cases():return self.respond(503,{'error':'authenticated server required for this datastore'})
            try:
                if access is not None:
                    try:access.vehicles(self.headers.get('Authorization'))
                    except AccessDenied:return self.respond(401,{'error':'authentication required'})
                    except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                    parts=urlsplit(self.path).path.strip('/').split('/')
                    if measurements is not None and len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='measurement-runs':
                        principal=access.principal(self.headers.get('Authorization'))
                        if 'events:measure' not in principal.scopes:return self.respond(403,{'error':'measurement scope required'})
                        if self.headers.get('Origin'):return self.respond(403,{'error':'cross-origin writes not allowed'})
                        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':return self.respond(415,{'error':'application/json required'})
                        lengths=self.headers.get_all('Content-Length',[])
                        if self.headers.get('Transfer-Encoding') or len(lengths)!=1 or not re.fullmatch(r'[0-9]{1,6}',lengths[0]):
                            return self.respond(400,{'error':'one bounded content length required'})
                        size=int(lengths[0])
                        if not 0<size<=256*1024:return self.respond(413,{'error':'measurement request too large'})
                        if not measurement_slots.acquire(blocking=False):return self.respond(503,{'error':'measurement writes busy'})
                        try:
                            body=measurements.decode(self.rfile.read(size))
                            principal=access.principal(self.headers.get('Authorization'))
                            if 'events:measure' not in principal.scopes:return self.respond(403,{'error':'measurement scope required'})
                            try:return self.respond(200,measurements.save(parts[2],principal,body))
                            except KeyError:return self.respond(404,{'error':'event not found'})
                        finally:measurement_slots.release()
                    if photos is not None and len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='photos':
                        principal=access.principal(self.headers.get('Authorization'))
                        if 'events:photos' not in principal.scopes:return self.respond(403,{'error':'photo scope required'})
                        if self.headers.get('Origin'):return self.respond(403,{'error':'cross-origin writes not allowed'})
                        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':return self.respond(415,{'error':'application/json required'})
                        lengths=self.headers.get_all('Content-Length',[])
                        if self.headers.get('Transfer-Encoding') or len(lengths)!=1 or not re.fullmatch(r'[0-9]{1,7}',lengths[0]):
                            return self.respond(400,{'error':'one bounded content length required'})
                        size=int(lengths[0])
                        if not 0<size<=1500000:return self.respond(413,{'error':'photo request too large'})
                        if not photo_slots.acquire(blocking=False):return self.respond(503,{'error':'photo uploads busy; retry later'})
                        try:
                            try:return self.respond(200,photos.save(parts[2],principal,json.loads(self.rfile.read(size))))
                            except KeyError:return self.respond(404,{'error':'event not found'})
                        finally:photo_slots.release()
                    if owner_cases is not None and parts==['api','cases']:
                        principal=access.principal(self.headers.get('Authorization'))
                        if 'events:create' not in principal.scopes:return self.respond(403,{'error':'case creation scope required'})
                        if self.headers.get('Origin'):return self.respond(403,{'error':'cross-origin writes not allowed'})
                        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':return self.respond(415,{'error':'application/json required'})
                        lengths=self.headers.get_all('Content-Length',[])
                        if self.headers.get('Transfer-Encoding') or len(lengths)!=1 or not re.fullmatch(r'[0-9]{1,6}',lengths[0]):
                            return self.respond(400,{'error':'one bounded content length required'})
                        size=int(lengths[0])
                        if not 0<size<=16384:return self.respond(413,{'error':'case request too large'})
                        body=json.loads(self.rfile.read(size))
                        try:return self.respond(200,owner_cases.create(principal,body))
                        except KeyError:return self.respond(404,{'error':'vehicle not registered'})
                    if observations is not None and len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='sdk-observations':
                        principal=access.principal(self.headers.get('Authorization'))
                        if 'events:observe' not in principal.scopes:return self.respond(403,{'error':'observation scope required'})
                        if self.headers.get('Origin'):return self.respond(403,{'error':'cross-origin writes not allowed'})
                        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':return self.respond(415,{'error':'application/json required'})
                        lengths=self.headers.get_all('Content-Length',[])
                        if self.headers.get('Transfer-Encoding') or len(lengths)!=1 or not re.fullmatch(r'[0-9]{1,6}',lengths[0]):
                            return self.respond(400,{'error':'one bounded content length required'})
                        size=int(lengths[0])
                        if not 0<size<=32768:return self.respond(413,{'error':'observation too large'})
                        body=json.loads(self.rfile.read(size))
                        try:return self.respond(200,observations.save(parts[2],principal,body))
                        except KeyError:return self.respond(404,{'error':'event not found'})
                    if notes is not None and len(parts)==4 and parts[:2]==['api','events'] and parts[3]=='review-note':
                        try:principal=access.reviewer(self.headers.get('Authorization'))
                        except AccessDenied:return self.respond(401,{'error':'authentication required'})
                        except ReviewDenied:return self.respond(403,{'error':'review scope required'})
                        except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
                        if self.headers.get('Origin'):return self.respond(403,{'error':'cross-origin writes not allowed'})
                        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':
                            return self.respond(415,{'error':'application/json required'})
                        lengths=self.headers.get_all('Content-Length',[])
                        if self.headers.get('Transfer-Encoding') or len(lengths)!=1 or not re.fullmatch(r'[0-9]{1,6}',lengths[0]):
                            return self.respond(400,{'error':'one bounded content length required'})
                        size=int(lengths[0])
                        if not 0<size<=8192:return self.respond(413,{'error':'note request too large'})
                        body=json.loads(self.rfile.read(size))
                        try:return self.respond(200,notes.save(parts[2],principal,body))
                        except KeyError:return self.respond(404,{'error':'event not found'})
                    return self.respond(403,{'error':'writes not enabled in authenticated read-only mode'})
                # No browser-origin writes. Native app and local CLI only.
                if self.headers.get('Origin'):
                    return self.respond(403, {'error': 'cross-origin writes not allowed'})
                if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                    return self.respond(415, {'error': 'application/json required'})
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= 1_000_000:
                    return self.respond(413, {'error': 'body size out of range'})
                body = json.loads(self.rfile.read(size))
                if not isinstance(body, dict):
                    raise ValueError('object required')
                parts = urlsplit(self.path).path.strip('/').split('/')
                if parts == ['api', 'demo', 'emergency-timeline']:
                    from acquisition.emergency_timeline import analyze, event_input
                    if set(body) != {'timeline', 'as_of', 'request_key'}:
                        raise ValueError('timeline, as_of and request key required')
                    report = analyze(body['timeline'], as_of=body['as_of'])
                    if report['status'] not in ('responded', 'cancelled', 'timed_out'):
                        raise ValueError('completed conclusive demo required')
                    event = store.create(event_input(report), body['request_key'])
                    if report['status'] == 'timed_out':
                        # Local mock only; dispatch is idempotent for this event/recipient.
                        store.dispatch(event['id'], '119-mock')
                        event = store.get(event['id'])
                    return self.respond(201, event)
                if parts == ['api','dev','parking-arrival']:
                    return self.respond(200, arrivals.accept(body))
                if parts==['api','demo','parking','record']:
                    return self.respond(201,store.save_parking(parking_fixture()))
                if parts==['api','demo','parking','elevator-cue']:
                    return self.respond(200,elevator_cue(body['samples']))
                if parts==['api','demo','parking','locate']:
                    parking=store.latest_parking('demo-hyundai-01')
                    if parking is None:raise ValueError('parking record required')
                    return self.respond(200,locate(parking,body['signs']))
                if len(parts)==6 and parts[:4]==['api','demo','owner','notifications'] and parts[5]=='read':
                    return self.respond(200, store.read_notification(parts[4], 'demo-hyundai-01'))
                if parts == ['api', 'emergency', 'request']:
                    if body.get('target') not in ('119','112'):
                        raise ValueError('119 or 112 target required')
                    result = store.create(generate('help_' + body['target']), body['request_key'])
                    store.dispatch(result['id'])
                    return self.respond(201, store.get(result['id']))
                if parts == ['api', 'hazards', 'preview-recipients']:
                    now = time.time()
                    return self.respond(200, {'mode': 'synthetic-only', 'deliveries': recipients(aggregate(store.road_events(), now), body['vehicles'], now)})
                if parts == ['api', 'scenarios', 'run']:
                    seed = body.get('seed', 7)
                    if not isinstance(seed, int) or isinstance(seed, bool):
                        raise ValueError('integer seed required')
                    result = store.create(generate(body['scenario'], seed), body['request_key'])
                    if result['assessment']['kind'] in ('crash','emergency','manual_help','wanted','parking') and result['status'] == 'evidence_ready':
                        store.dispatch(result['id'])
                        result = store.get(result['id'])
                    return self.respond(201, result)
                if len(parts) == 4 and parts[:2] == ['api', 'events']:
                    if parts[3] == 'help-video-session':
                        from evidence.help_video import start as start_help_video
                        return self.respond(200,start_help_video(store,parts[2],body.get('original_hash')))
                    if parts[3] == 'road-followups':
                        return self.respond(200,store.link_road_followup(parts[2],body.get('followup_event_id')))
                    if parts[3]=='video-fixture':
                        return self.respond(200,store.create_video_fixture(parts[2]))
                    if parts[3] == 'repair-offer':
                        return self.respond(200, store.repair_offer(parts[2],body['brand'],body['warranty']))
                    if parts[3] == 'repair-complete':
                        if body.get('scenario') not in ('healthy','diagnosis'):
                            raise ValueError('healthy or diagnosis follow-up scenario required')
                        return self.respond(200, store.repair_complete(parts[2],generate(body['scenario']),body['technician_note']))
                    if parts[3] == 'confirm':
                        if not isinstance(body.get('accepted'), bool):
                            raise ValueError('accepted must be boolean')
                        return self.respond(200, store.confirm(parts[2], body['accepted']))
                    if parts[3] == 'dispatch':
                        if not isinstance(body.get('simulate_failure', False), bool):
                            raise ValueError('simulate_failure must be boolean')
                        return self.respond(200, store.dispatch(parts[2], body.get('recipient'), body.get('simulate_failure', False)))
                return self.respond(404, {'error': 'not found'})
            except AccessDenied:return self.respond(401,{'error':'authentication required'})
            except AccessUnavailable:return self.respond(503,{'error':'authorization temporarily unavailable'})
            except MeasurementConflict:return self.respond(409,{'error':'measurement request conflict'})
            except MeasurementLimit:return self.respond(409,{'error':'measurement run limit reached'})
            except PhotoConflict:return self.respond(409,{'error':'photo request conflict'})
            except PhotoLimit:return self.respond(409,{'error':'photo limit reached'})
            except ObservationConflict:return self.respond(409,{'error':'observation request conflict'})
            except ObservationLimit:return self.respond(409,{'error':'observation limit reached'})
            except CaseConflict:return self.respond(409,{'error':'case request conflict'})
            except CaseLimit:return self.respond(409,{'error':'private case limit reached'})
            except NoteConflict:
                return self.respond(409,{'error':'note revision or request conflict; reload current note'})
            except (ValueError, TypeError, KeyError) as exc:
                return self.respond(400, {'error': str(exc)})

        def log_message(self, fmt, *args):
            pass
    return Handler

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--database', default=str(Path(__file__).resolve().parents[2]/'.runtime/events.sqlite3'))
    parser.add_argument('--access-config',help='Trusted issuer, RSA public keys and vehicle grants JSON; enables authenticated read-only API')
    parser.add_argument('--enable-review-notes',action='store_true',help='Opt in to private notes; writes also require events:review scope')
    parser.add_argument('--enable-sdk-observations',action='store_true',help='Opt in to private SDK observations; writes require events:observe scope')
    parser.add_argument('--enable-owner-cases',action='store_true',help='Opt in to owner-authored private cases; writes require events:create scope')
    parser.add_argument('--enable-case-photos',action='store_true',help='Opt in to private authored-case photos; writes require events:photos scope')
    parser.add_argument('--pleos-consent-config',help='Private operator-managed Pleos consent/token registry; requires access config and events:powertrain scope')
    parser.add_argument('--enable-measurement-runs',action='store_true',help='Opt in to private timed measurements and derived condition comparison; writes require events:measure')
    args = parser.parse_args()
    access=AccessPolicy.from_file(args.access_config) if args.access_config else None
    if args.enable_review_notes and access is None:parser.error('--enable-review-notes requires --access-config')
    if args.enable_sdk_observations and access is None:parser.error('--enable-sdk-observations requires --access-config')
    if args.enable_owner_cases and access is None:parser.error('--enable-owner-cases requires --access-config')
    if args.enable_case_photos and access is None:parser.error('--enable-case-photos requires --access-config')
    if args.pleos_consent_config and access is None:parser.error('--pleos-consent-config requires --access-config')
    if args.enable_measurement_runs and access is None:parser.error('--enable-measurement-runs requires --access-config')
    powertrain=PleosPowertrainClient(PleosConsentRegistry(args.pleos_consent_config)) if args.pleos_consent_config else None
    server = BoundedHTTPServer(('127.0.0.1', args.port), handler(Store(args.database),access,args.enable_review_notes,args.enable_sdk_observations,args.enable_owner_cases,args.enable_case_photos,powertrain,args.enable_measurement_runs))
    mode='authenticated-private-photos' if args.enable_case_photos else 'authenticated-private-cases' if args.enable_owner_cases else 'authenticated-sdk-observations' if args.enable_sdk_observations else 'authenticated-private-notes' if args.enable_review_notes else 'authenticated-read-only' if access else 'synthetic-only'
    print(f'Road Guardian {mode} server: http://127.0.0.1:{args.port}', flush=True)
    server.serve_forever()

if __name__ == '__main__':
    main()
