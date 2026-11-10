"""Experimental descriptive voltage comparison, never a repair or fault verdict.

The input contract requires explicit per-sample timing and reported conditions.
Neither this contract nor its development tolerances are OEM service criteria.
Cloud powertrain arrays and gear/speed-only SDK observations are not this input.
"""
import hashlib
import json
import math
import time
import uuid
import argparse
from pathlib import Path
from statistics import median

SCHEMA = 'road-guardian.timed-voltage-run.v1'
RULE = 'reported-voltage-conditions-dev-v1'
UNITS = {'voltage_v': 'V', 'speed_kph': 'km/h', 'rpm': 'rpm', 'battery_temperature_c': 'degC',
         'ambient_temperature_c': 'degC', 'state_of_charge_pct': 'percent', 'battery_current_a': 'A'}
LIMITS = {'speed_kph': (0, 400), 'rpm': (0, 20000), 'battery_temperature_c': (-60, 150),
          'ambient_temperature_c': (-60, 80), 'state_of_charge_pct': (0, 100), 'battery_current_a': (-2000, 2000)}
TOLERANCES = {'speed_kph': .5, 'rpm': 100, 'battery_temperature_c': 2,
              'ambient_temperature_c': 3, 'state_of_charge_pct': 2, 'battery_current_a': 2}


def _finite(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def _label(value):
    return isinstance(value, str) and 1 <= len(value) <= 128 and all(ord(c) >= 32 for c in value)


def validate_run(run, now=None):
    """Validate reported measurements. Null conditions remain missing, never inferred."""
    fields = {'schema', 'run_id', 'vehicle_id', 'environment', 'channel', 'instrument_id',
              'calibration_ref', 'started_at', 'sample_period_ms', 'units', 'samples'}
    if not isinstance(run, dict) or set(run) != fields or run['schema'] != SCHEMA:
        raise ValueError('timed voltage run contract required')
    if not isinstance(run['run_id'], str) or str(uuid.UUID(run['run_id'])) != run['run_id']:
        raise ValueError('canonical run UUID required')
    if not _label(run['vehicle_id']) or not _label(run['instrument_id']):
        raise ValueError('vehicle and instrument reference required')
    if run['calibration_ref'] is not None and not _label(run['calibration_ref']):
        raise ValueError('calibration reference or null required')
    if run['environment'] not in ('synthetic', 'external_unattested') or run['channel'] not in ('auxiliary_12v', 'hv_pack'):
        raise ValueError('explicit environment and circuit required')
    if not isinstance(run['units'], dict) or run['units'] != UNITS:
        raise ValueError('explicit channel units required')
    if not _finite(run['started_at']) or not 0 < run['started_at'] <= (time.time() if now is None else now) + 300:
        raise ValueError('valid reported start time required')
    period = run['sample_period_ms']
    if type(period) is not int or not 100 <= period <= 10000:
        raise ValueError('declared sample period required')
    samples = run['samples']
    if not isinstance(samples, list) or not 1 <= len(samples) <= 256:
        raise ValueError('one to 256 timed samples required')
    sample_fields = set(UNITS) | {'elapsed_ms', 'gear', 'operating_mode', 'load_state'}
    previous = -1
    for i, sample in enumerate(samples):
        if not isinstance(sample, dict) or set(sample) != sample_fields:
            raise ValueError('complete sample fields required')
        elapsed = sample['elapsed_ms']
        if type(elapsed) is not int or not previous < elapsed <= 180000 or i == 0 and elapsed != 0:
            raise ValueError('explicit ordered elapsed times required')
        previous = elapsed
        for field, bounds in dict(LIMITS, voltage_v=(0, 60 if run['channel'] == 'auxiliary_12v' else 6553)).items():
            value = sample[field]
            if value is not None and (not _finite(value) or not bounds[0] <= value <= bounds[1]):
                raise ValueError('finite measurement in declared range or null required')
        if sample['gear'] not in (None, 'P', 'R', 'N', 'D'):
            raise ValueError('known gear or null required')
        if sample['operating_mode'] not in (None, 'engine_idle', 'vehicle_ready'):
            raise ValueError('known operating mode or null required')
        if sample['load_state'] not in (None, 'accessories_off', 'fixed_test_load'):
            raise ValueError('known reported load or null required')
    if run['started_at'] + samples[-1]['elapsed_ms'] / 1000 > (time.time() if now is None else now) + 300:
        raise ValueError('reported capture end is in the future')
    raw = json.dumps(run, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
    if len(raw.encode()) > 256 * 1024:
        raise ValueError('measurement run too large')
    return raw


def _eligible(sample):
    return (sample['voltage_v'] is not None and all(sample[key] is not None for key in TOLERANCES)
            and sample['gear'] == 'P' and sample['speed_kph'] <= 1
            and sample['operating_mode'] is not None and sample['load_state'] is not None)


def compare_runs(before, after, now=None):
    """Maximum condition-only matching; voltage never influences sample selection."""
    raw = [validate_run(run, now) for run in (before, after)]
    result = {'schema': 'road-guardian.condition-comparison.v1', 'rule': RULE,
              'environment': before['environment'], 'provenance': 'reported_measurements_not_attested',
              'before_run_id': before['run_id'], 'after_run_id': after['run_id'],
              'before_sha256': hashlib.sha256(raw[0].encode()).hexdigest(),
              'after_sha256': hashlib.sha256(raw[1].encode()).hexdigest(),
              'hash_basis': 'canonical_validated_run_utf8',
              'state': 'not_comparable', 'reasons': [], 'metrics': None, 'matched_pairs': [],
              'conditions_verified': False, 'repair_confirmed': False, 'diagnosis_performed': False,
              'scope': 'descriptive_voltage_difference_only', 'channel': before['channel'],
              'voltage_unit': 'V',
              'tolerances': dict(TOLERANCES), 'tolerance_authority': 'development_only_not_oem_criteria',
              'unverified': ['vehicle_identity', 'instrument_calibration_validity', 'condition_accuracy',
                             'capture_clock', 'repair_action', 'causal_attribution']}
    reasons = result['reasons']
    if before['run_id'] == after['run_id']:reasons.append('distinct_runs_required')
    for field in ('vehicle_id', 'environment', 'channel', 'instrument_id', 'sample_period_ms'):
        if before[field] != after[field]:reasons.append(field + '_mismatch')
    if before['calibration_ref'] is None or after['calibration_ref'] is None:
        reasons.append('calibration_reference_missing')
    elif before['calibration_ref'] != after['calibration_ref']:reasons.append('calibration_reference_mismatch')
    if before['started_at'] + before['samples'][-1]['elapsed_ms'] / 1000 >= after['started_at']:
        reasons.append('nonoverlapping_reported_chronology_required')
    if any(len(run['samples']) < 20 or run['samples'][-1]['elapsed_ms'] < 10000 for run in (before, after)):
        reasons.append('minimum_observation_window_required')
    if any(any(abs(b['elapsed_ms'] - a['elapsed_ms'] - run['sample_period_ms']) > run['sample_period_ms'] * .1
               for a, b in zip(run['samples'], run['samples'][1:])) for run in (before, after)):
        reasons.append('reported_sample_spacing_outside_10_percent')
    if reasons:return result
    eligible = [[i for i, sample in enumerate(run['samples']) if _eligible(sample)] for run in (before, after)]
    result['sample_counts'] = {'before': len(before['samples']), 'after': len(after['samples']),
                               'before_eligible': len(eligible[0]), 'after_eligible': len(eligible[1]), 'matched': 0}
    edges = {}
    for a in eligible[0]:
        first = before['samples'][a]; candidates = []
        for b in eligible[1]:
            second = after['samples'][b]
            if first['operating_mode'] != second['operating_mode'] or first['load_state'] != second['load_state']:
                continue
            distance = [abs(first[key] - second[key]) / tolerance for key, tolerance in TOLERANCES.items()]
            if all(value <= 1 for value in distance):candidates.append((sum(distance), b))
        edges[a] = [b for _, b in sorted(candidates)]
    matched = {}
    def assign(a, seen):
        for b in edges[a]:
            if b in seen:continue
            seen.add(b)
            if b not in matched or assign(matched[b], seen):
                matched[b] = a
                return True
        return False
    for a in eligible[0]:assign(a, set())
    pairs = sorted((a, b) for b, a in matched.items())
    result['sample_counts']['matched'] = len(pairs)
    # Coverage uses all reported samples, not only a convenient eligible subset.
    if len(pairs) < 20:reasons.append('matched_samples_insufficient')
    if any(len(pairs) / len(run['samples']) < .8 for run in (before, after)):
        reasons.append('whole_run_coverage_below_80_percent')
    if reasons:return result
    left = [before['samples'][a]['voltage_v'] for a, _ in pairs]
    right = [after['samples'][b]['voltage_v'] for _, b in pairs]
    result.update(state='reported_conditions_matched', matched_pairs=[list(pair) for pair in pairs],
                  metrics={'before_median_v': round(median(left), 6), 'after_median_v': round(median(right), 6),
                           'paired_delta_median_v': round(median(b - a for a, b in zip(left, right)), 6),
                           'before_range_v': [min(left), max(left)], 'after_range_v': [min(right), max(right)],
                           'matched_samples': len(pairs), 'before_coverage': len(pairs) / len(before['samples']),
                           'after_coverage': len(pairs) / len(after['samples'])})
    result['condition_differences_max'] = {key: max(abs(before['samples'][a][key] - after['samples'][b][key])
                                                   for a, b in pairs) for key in TOLERANCES}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    def read(path):
        with path.open('rb') as stream:raw = stream.read(256 * 1024 + 1)
        if len(raw) > 256 * 1024:raise ValueError('measurement file too large')
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:raise ValueError('duplicate measurement JSON field')
                result[key] = value
            return result
        def invalid(_):raise ValueError('non-finite measurement JSON')
        return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid)
    try:
        output = json.dumps(compare_runs(read(args.before), read(args.after)), ensure_ascii=False, indent=2)
        if args.output:
            with args.output.open('x') as stream:stream.write(output+'\n')
        else:print(output)
    except (ValueError, OSError, RecursionError):
        parser.exit(2, 'Comparison rejected: check measurement format, file access and output location.\n')


if __name__ == '__main__':main()
