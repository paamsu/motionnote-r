"""Synthetic road sharing. Eligibility is not a live identity assertion."""
import hashlib
import json
import math
from incident.policy import approaching

TTL_SECONDS = {'pothole': 86400, 'rain': 900}
DEMO_RECEIVER = {'id': 'demo-kia-receiver', 'source': 'synthetic', 'brand': 'Kia',
                 'authenticated': True, 'lat': 37.5, 'lon': 126.997, 'heading': 90,
                 'accuracy_m': 8, 'road_id': 'demo-road-east', 'speed_kph': 42}

def demo_feed(events, now):
    """Server-owned driving fixture, not authentication supplied by a live client."""
    warnings = []
    vehicle = dict(DEMO_RECEIVER)
    for hazard in aggregate(events, now):
        loc = hazard['location']
        dx = (loc['lon'] - vehicle['lon']) * 111000 * math.cos(math.radians(vehicle['lat']))
        dy = (loc['lat'] - vehicle['lat']) * 111000
        heading = math.radians(vehicle['heading'])
        ahead = dx * math.sin(heading) + dy * math.cos(heading)
        if ahead <= 0:
            continue
        distance = math.hypot(dx, dy)
        candidate = dict(vehicle, eta_seconds=max(1, math.ceil(distance / (vehicle['speed_kph'] / 3.6))))
        if approaching(hazard, [candidate], now):
            warnings.append(dict(hazard, distance_m=round(distance), eta_seconds=candidate['eta_seconds'],
                                 advisory=dict(hazard['advisory'],direction='같은 도로 · 같은 진행 방향')))
    warnings.sort(key=lambda item: item['distance_m'])
    return {'schema': 'road-guardian.shared-road-feed.v1', 'source': 'synthetic', 'mode': 'synthetic-only', 'vehicle': vehicle, 'warnings': warnings, 'generated_at': now}

def authored_lane(event):
    lane=event.get('data',{}).get('observations',{}).get('road_lane')
    loc=event.get('data',{}).get('location',{})
    if (event.get('source')!='synthetic' or not isinstance(lane,dict)
            or lane.get('source')!='authored_synthetic' or lane.get('road_id')!=loc.get('road_id')
            or type(lane.get('lane_number')) is not int or type(lane.get('lane_count')) is not int
            or not 1<=lane['lane_number']<=lane['lane_count']<=8):return None
    return dict(source='authored_synthetic',road_id=lane['road_id'],lane_number=lane['lane_number'],lane_count=lane['lane_count'])

def aggregate(events, now):
    groups = {}
    for event in events:
        kind = event['assessment']['kind']
        if kind not in TTL_SECONDS or not event.get('evidence_valid') or event['status'] in ('pending_confirmation', 'dismissed'):
            continue
        if event.get('source') != 'synthetic' or event['created'] + TTL_SECONDS[kind] <= now:
            continue
        location = event['data']['location']
        if not valid_location(location):
            continue
        # Same road, direction, kind and 100 m grid. Raw reports remain separate.
        key = (kind, location['road_id'], int(location['heading'] // 45),
               int(location['lat'] * 111000 // 100), int(location['lon'] * 88000 // 100))
        group = groups.setdefault(key, {'id': hashlib.sha256(repr(key).encode()).hexdigest()[:16],
            'kind': kind, 'location': dict(location), 'source': 'synthetic',
            'event_ids': [], 'vehicle_ids': set(), '_lanes': [], 'expires_at': 0, 'latest_at': 0})
        group['event_ids'].append(event['id'])
        group['_lanes'].append(authored_lane(event))
        group['vehicle_ids'].add(event['data']['vehicle_id'])
        group['expires_at'] = max(group['expires_at'], event['created'] + TTL_SECONDS[kind])
        if event['created'] >= group['latest_at']:
            group['latest_at'] = event['created']
            group['location'] = dict(location)
    for group in groups.values():
        group['independent_vehicle_count'] = len(group.pop('vehicle_ids'))
        group['report_count'] = len(group['event_ids'])
        group['message'] = '도로 파손 주의' if group['kind'] == 'pothole' else '시야 저하 주의'
        lanes=group.pop('_lanes')
        group['lane_guidance'] = lanes[0] if lanes and lanes[0] is not None and all(lane==lanes[0] for lane in lanes) else None
        group['advisory'] = {
            'title':group['message'],
            'instruction':'전방 도로 상태를 확인하고 서행하세요.' if group['kind']=='pothole' else '전방 시야 저하 구간입니다. 감속하고 안전거리를 확보하세요.',
            'basis':'운전자 확인을 거친 도로 충격 기록' if group['kind']=='pothole' else '와이퍼·시야·우적 신호를 함께 확인한 기록',
            'lane_status':(str(group['lane_guidance']['lane_number'])+'차로 위험 관측 · 감속하세요') if group['lane_guidance'] else '차로 정보 없음 · 차로 변경 안내 없음'}
    return sorted(groups.values(), key=lambda item: item['latest_at'], reverse=True)

def valid_location(location):
    def number(key, low, high):
        value = location.get(key)
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and low <= value <= high
    return (number('lat', -90, 90) and number('lon', -180, 180) and
            number('heading', 0, 359.999999) and number('accuracy_m', 0, 30) and
            isinstance(location.get('road_id'), str) and bool(location['road_id']))

def recipients(hazards, vehicles, now):
    if not isinstance(vehicles, list) or len(vehicles) > 100:
        raise ValueError('maximum 100 synthetic vehicles required')
    for vehicle in vehicles:
        if not isinstance(vehicle, dict) or vehicle.get('source') != 'synthetic' or not valid_location(vehicle):
            raise ValueError('valid synthetic vehicle position required')
        if not isinstance(vehicle.get('id'), str) or not vehicle['id']:
            raise ValueError('vehicle id required')
        if not isinstance(vehicle.get('authenticated'), bool):
            raise ValueError('synthetic authentication fixture required')
    return [{'hazard_id': hazard['id'], 'vehicle_ids': approaching(hazard, vehicles, now)} for hazard in hazards]


def demo_road_summary(events, now):
    """Contest-only inventory over existing aggregation; no institutional delivery claim."""
    unique={event['id']:event for event in events if event.get('assessment',{}).get('kind')=='pothole'}
    rows=list(unique.values())
    groups=aggregate(rows,now)
    included={event_id for group in groups for event_id in group['event_ids']}
    pending=sum(event.get('source')=='synthetic' and event.get('evidence_valid') is True and
                event.get('status')=='pending_confirmation' and event['created']+TTL_SECONDS['pothole']>now and
                valid_location(event['data']['location']) for event in rows)
    return {'schema':'road-guardian.demo-road-summary.v1','source':'synthetic',
            'generated_at':now,'external_submission':False,'repair_verified':False,
            'input_count':len(rows),'included_count':len(included),'pending_count':pending,
            'excluded_count':len(rows)-len(included)-pending,'group_count':len(groups),
            'groups':[{'id':group['id'],'report_count':group['report_count'],'fixture_vehicle_count':group['independent_vehicle_count'],
                       'latest_at':group['latest_at'],'expires_at':group['expires_at']} for group in groups],
            'grouping':{'grid_m':100,'heading_bucket_degrees':45,'same_road_required':True}}


class RoadGroupChanged(ValueError):pass


def demo_road_group(events, now, group_id, offset=0, snapshot=None):
    """Explicit synthetic-source inspection, separate from the anonymous summary."""
    import re
    if not isinstance(group_id,str) or not re.fullmatch(r'[0-9a-f]{16}',group_id):
        raise ValueError('valid synthetic group reference required')
    if type(offset) is not int or not 0 <= offset <= 100000:
        raise ValueError('bounded source offset required')
    unique={event['id']:event for event in events if event.get('assessment',{}).get('kind')=='pothole'}
    group=next((g for g in aggregate(list(unique.values()),now) if g['id']==group_id),None)
    if group is None:raise KeyError('synthetic road group not found')
    rows=sorted((unique[id] for id in group['event_ids']),key=lambda e:(e['created'],e['id']),reverse=True)
    # A page token covers the current evidence manifests as well as sensor identity.
    # A changed/corrupt photo must invalidate a token even when sensor bytes stayed equal.
    members=[{'id':e['id'],'hash':e['hash'],'evidence_valid':e.get('evidence_valid'),
              **{key:e.get(key) for key in ('video','surround_views','surround')}} for e in rows]
    membership=hashlib.sha256(json.dumps(members,sort_keys=True,separators=(',',':'),
                                        ensure_ascii=False,allow_nan=False).encode('utf-8')).hexdigest()
    if offset>len(rows):raise ValueError('source offset exceeds current group')
    if (offset>0 and snapshot is None) or (snapshot is not None and (not isinstance(snapshot,str) or not re.fullmatch(r'[0-9a-f]{64}',snapshot))):
        raise ValueError('source membership reference required')
    if snapshot is not None and snapshot!=membership:raise RoadGroupChanged('source group changed; reload from first page')
    page=rows[offset:offset+10]
    return {'schema':'road-guardian.demo-road-group.v1','source':'synthetic','group_id':group_id,
            'generated_at':now,'membership_sha256':membership,'total':len(rows),'offset':offset,'next_offset':offset+len(page) if offset+len(page)<len(rows) else None,
            'events':page,'external_submission':False,'repair_verified':False}
