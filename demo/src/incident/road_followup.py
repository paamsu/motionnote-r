"""Synthetic repeated road observations. Never certifies road repair or road safety."""
import hashlib
import json
import math
from statistics import median
from evidence.road_review import number
from incident.sharing import valid_location
from incident.policy import assess


def metrics(event):
    data = event['data']
    raw = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()
    if event.get('evidence_valid') is not True or hashlib.sha256(raw).hexdigest() != event.get('hash'):
        raise ValueError('original_integrity_failure')
    if event.get('source') != 'synthetic' or data.get('source') != 'synthetic':
        raise ValueError('synthetic_comparison_only')
    if assess(data)['kind'] != event['assessment']['kind']:
        raise ValueError('assessment_changed')
    samples = data.get('samples')
    rate = data.get('sample_hz')
    if not isinstance(samples, list) or len(samples) < 2 or not number(rate) or rate <= 0:
        raise ValueError('sample_metadata_required')
    times, impacts, speeds = [], [], []
    for sample in samples:
        if not isinstance(sample, dict) or not all(number(sample.get(k)) for k in ('t', 'vertical_g', 'speed_kph')):
            raise ValueError('sample_values_required')
        if sample['speed_kph'] < 0:
            raise ValueError('nonnegative_speed_required')
        times.append(sample['t']); impacts.append(abs(sample['vertical_g'])); speeds.append(sample['speed_kph'])
    if times[0] != 0 or any(not math.isclose(b-a, 1/rate, rel_tol=.1, abs_tol=1e-6) for a,b in zip(times,times[1:])):
        raise ValueError('sampling_interval_mismatch')
    captured = data.get('capture_started_at')
    if not number(captured) or captured <= 0:
        raise ValueError('observation_time_required')
    location=data.get('location')
    if not isinstance(location,dict) or not valid_location(location):
        raise ValueError('location_quality_required')
    units=data.get('units')
    unit = units.get('vertical_g') if isinstance(units,dict) else None
    if unit != 'g, gravity removed': raise ValueError('gravity_removed_g_unit_required')
    return {'peak_g':max(impacts), 'speed_kph':median(speeds), 'samples':len(samples),
            'sample_hz':rate, 'captured_at':captured, 'duration_s':times[-1], 'unit':unit}


def compare(original, followup):
    try:
        before, after = metrics(original), metrics(followup)
    except (OverflowError,AttributeError) as error:
        raise ValueError('invalid_observation_metadata') from error
    if original['id'] == followup['id']: raise ValueError('different_record_required')
    if original['assessment']['kind'] != 'pothole' or original['status'] in ('pending_confirmation','dismissed'):
        raise ValueError('confirmed_road_record_required')
    if followup['assessment']['kind'] not in ('pothole','observation'):
        raise ValueError('road_observation_required')
    a,b = original['data'],followup['data']
    if not a.get('vehicle_id') or a.get('vehicle_id') != b.get('vehicle_id'):
        raise ValueError('same_vehicle_required')
    if after['captured_at'] <= before['captured_at'] + before['duration_s']:
        raise ValueError('later_nonoverlapping_observation_required')
    x,y = a['location'],b['location']
    distance = math.hypot((x['lat']-y['lat'])*111000,(x['lon']-y['lon'])*111000*math.cos(math.radians(x['lat'])))
    direction = abs((x['heading']-y['heading']+180)%360-180)
    if x['road_id'] != y['road_id'] or distance > 30 or direction > 15:
        raise ValueError('same_road_position_direction_required')
    if before['unit'] != after['unit'] or before['sample_hz'] != after['sample_hz'] or before['samples'] != after['samples'] or abs(before['speed_kph']-after['speed_kph']) > 5:
        raise ValueError('matching_measurement_conditions_required')
    return {'stage':'repeat_signal' if after['peak_g'] > 1.5 else 'no_repeat_signal',
            'before':before,'after':after,'distance_m':round(distance,2),'heading_difference_deg':round(direction,2),
            'followup_id':followup['id'],'followup_sha256':followup['hash']}


def followup_case(original, followup=None, count=0):
    if original['assessment']['kind'] != 'pothole': return None
    result = {'schema':'road-guardian.road-followup.v1','source':original.get('source'),
              'scope':'single_vehicle_development_comparison','repair_confirmed':False,
              'road_safe':None,'link_count':count,'stage':'awaiting_observation'}
    if followup is not None:
        try: result.update(compare(original,followup))
        except (ValueError,TypeError,KeyError,OverflowError,AttributeError) as error:
            result.update(stage='comparison_invalid',reason=str(error))
    return result
