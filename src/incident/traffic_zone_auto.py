"""Bounded authored traffic-zone demo; no vision/legal verdict or external submission."""
import hashlib
import json
import math
import time
from incident.policy import assess

ZONE_LABELS = {'school_zone': '어린이보호구역', 'no_parking': '주정차금지구역'}
# Only visually reviewed representative assets may enable an authored scenario.
ZONE_PHOTOS = {'school_zone': 'e33c47aefa74867675a6e7b086ba2c19e6415206b8a13deb23d9f05c2b887f51',
               'no_parking': 'eea83c3e7d9e2286b0325fce1aef7b529b615ec9a10a46ad79abdfac1343e4f9'}

def authoring(zone):
    if zone not in ZONE_LABELS:
        raise ValueError('supported traffic zone required')
    return {'schema': 'road-guardian.traffic-zone-auto.v1', 'source': 'authored_contest_scenario',
            'zone_type': zone, 'zone_label': ZONE_LABELS[zone], 'dispatch_mode': 'automatic_contest',
            'recipient': 'public-report-mock', 'recognition_performed': False,
            'external_submission': False, 'target_actor_id': 'vehicle-1',
            'target_plate': 'DEMO-001', 'target_photo_sha256': ZONE_PHOTOS[zone]}

def checked(event):
    data = event.get('data', {}); context = data.get('context', {}); obs = data.get('observations', {})
    authored = context.get('traffic_zone_auto')
    zone = authored.get('zone_type') if isinstance(authored, dict) else None
    if (zone not in ZONE_LABELS or not ZONE_PHOTOS[zone] or authored != authoring(zone)
        or event.get('source') != 'synthetic' or data.get('source') != 'synthetic'
        or event.get('evidence_valid') is not True
        or event.get('status') not in ('pending_confirmation', 'dispatch_ready', 'retry_pending', 'acknowledged')):
        raise ValueError('explicit bound authored traffic zone required')
    location = data.get('location', {})
    if (any(not isinstance(location.get(k), str) or not location[k].strip() for k in ('address_label', 'road_id'))
        or any(type(location.get(k)) not in (int, float) or not math.isfinite(location[k]) or abs(location[k]) > limit
               for k, limit in (('lat', 90), ('lon', 180)))):
        raise ValueError('valid authored location required')
    actors = context.get('actors')
    if (not isinstance(actors, list) or len(actors) != 1 or not isinstance(actors[0], dict)
        or actors[0].get('id') != authored['target_actor_id'] or actors[0].get('plate') != authored['target_plate']
        or actors[0].get('recognition_status') != 'not_run' or obs.get('plate') != authored['target_plate']):
        raise ValueError('authored target identity required')
    fresh = assess(data)
    if any(fresh.get(k) != value or event.get('assessment', {}).get(k) != value
           for k, value in [('kind', 'violation'), ('recipient', 'public-report-mock')]):
        raise ValueError('restricted-zone dwell candidate required')
    video = event.get('video') or {}; photo = video.get('input_photo') or {}
    if (video.get('source') != 'synthetic_scene_fixture' or video.get('camera_evidence') is not False
        or video.get('integrity_valid') is not True or photo.get('integrity_valid') is not True
        or photo.get('event_id') != event['id'] or photo.get('sensor_sha256') != event.get('hash')
        or photo.get('sha256') != authored['target_photo_sha256']):
        raise ValueError('same-event representative photo and original evidence required')
    return authored

def dispatch_authored_traffic_zone(store, event_id):
    event = store.get(event_id); authored = checked(event)
    # This bounded fixture authorization is not a driver's confirmation. Keep the
    # generic Store.confirm/dispatch gates unchanged and retain the original bytes.
    with store.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT raw,hash,status FROM events WHERE id=?', (event_id,)).fetchone()
        if not row or row['hash'] != event['hash'] or hashlib.sha256(row['raw'].encode()).hexdigest() != event['hash']:
            raise ValueError('original event changed')
        if row['status'] != event['status']:
            raise ValueError('event state changed; retry from fresh state')
        if row['status'] == 'pending_confirmation':
            db.execute('UPDATE events SET status=? WHERE id=?', ('dispatch_ready', event_id))
            db.execute('INSERT INTO history(event_id,at,action,detail) VALUES(?,?,?,?)',
                       (event_id, time.time(), 'authored_traffic_auto_authorized',
                        json.dumps({'source': 'authored_contest_scenario', 'zone_type': authored['zone_type'],
                                    'driver_confirmation': False, 'external_submission': False}, sort_keys=True)))
    return store.dispatch(event_id, 'public-report-mock')
