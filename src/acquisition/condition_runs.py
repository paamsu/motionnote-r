"""Explicit synthetic measurement runs. Never a fallback for unavailable vehicle data."""
import argparse
import copy
import json
import math
import uuid
from pathlib import Path
from diagnostics.condition_comparison import SCHEMA, UNITS, compare_runs

SCENARIOS = ('higher_voltage', 'unchanged_voltage', 'conditions_differ', 'missing_conditions', 'low_coverage')


def fixture_pair(scenario='higher_voltage'):
    if scenario not in SCENARIOS:raise ValueError('unknown condition fixture')
    before = {'schema': SCHEMA, 'run_id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'synthetic-run-before/'+scenario)),
              'vehicle_id': 'synthetic-condition-vehicle', 'environment': 'synthetic', 'channel': 'auxiliary_12v',
              'instrument_id': 'synthetic-voltmeter', 'calibration_ref': 'synthetic-reference-not-a-certificate',
              'started_at': 1700000000, 'sample_period_ms': 500, 'units': dict(UNITS), 'samples': []}
    for i in range(40):
        before['samples'].append({'elapsed_ms': i * 500, 'voltage_v': round(11.7 + .03 * math.sin(i), 4),
            'speed_kph': 0, 'rpm': 1200, 'battery_temperature_c': 25, 'ambient_temperature_c': 22,
            'state_of_charge_pct': 70, 'battery_current_a': 1.5, 'gear': 'P',
            'operating_mode': 'engine_idle', 'load_state': 'fixed_test_load'})
    after = copy.deepcopy(before)
    after.update(run_id=str(uuid.uuid5(uuid.NAMESPACE_URL, 'synthetic-run-after/'+scenario)), started_at=1700000060)
    for sample in after['samples']:
        if scenario != 'unchanged_voltage':sample['voltage_v'] = round(sample['voltage_v'] + 2.2, 4)
        if scenario == 'conditions_differ':sample['state_of_charge_pct'] = 90
        if scenario == 'missing_conditions':sample['battery_current_a'] = None
    if scenario == 'low_coverage':
        for sample in after['samples'][28:]:sample['state_of_charge_pct'] = 90
    return before, after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, default='higher_voltage')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    before, after = fixture_pair(args.scenario)
    content = json.dumps({'fixture_only': True, 'vehicle_measured': False, 'before': before, 'after': after,
                          'comparison': compare_runs(before, after)}, ensure_ascii=False, indent=2)
    if args.output:
        with args.output.open('x') as output:output.write(content+'\n')
    else:print(content)


if __name__ == '__main__':main()
