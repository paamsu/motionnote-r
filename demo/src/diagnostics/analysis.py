from statistics import median
import math

def finite_number(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False

def diagnose(data: dict) -> dict:
    samples = data.get('samples', [])
    baseline = data.get('baseline', {})
    units = data.get('units', {})
    if (not isinstance(samples, list) or not isinstance(baseline, dict)
            or not isinstance(units, dict) or units.get('voltage') != 'V' or units.get('rpm') != 'rpm'
            or not all(finite_number(baseline.get(key)) for key in ('voltage', 'rpm', 'temperature_c'))
            or any(not isinstance(s, dict) or any(s.get(key) is not None and not finite_number(s[key])
                       for key in ('voltage', 'rpm', 'temperature_c')) for s in samples)):
        return {'verdict': 'inconclusive', 'summary': '측정 단위와 수치 확인이 필요합니다.',
                'candidates': [], 'missing': ['valid voltage/rpm units and finite numeric measurements']}
    matching = [s for s in samples if s.get('voltage') is not None
                and finite_number(s.get('rpm')) and finite_number(s.get('temperature_c'))
                and abs(s['rpm'] - baseline['rpm']) <= 100
                and abs(s['temperature_c'] - baseline['temperature_c']) <= 3]
    if len(matching) < 20 or baseline.get('voltage') is None:
        return {'verdict': 'inconclusive', 'summary': '비교 조건에 맞는 측정 자료가 부족합니다.', 'candidates': [], 'missing': ['matched baseline or samples']}
    observed = median(s['voltage'] for s in matching)
    delta = observed - baseline['voltage']
    result = {'verdict': 'no_anomaly_in_observed_signals', 'summary': '현재 비교 구간에서 전압 이상을 찾지 못했습니다.',
              'metrics': {'voltage_median_v': round(observed, 3), 'baseline_v': baseline['voltage'], 'delta_v': round(delta, 3), 'matched_samples': len(matching)},
              'candidates': [], 'missing': ['charging_current', 'battery_temperature', 'physical_inspection'],
              'scope': 'development voltage rule only; not a complete vehicle diagnosis',
              'rule': 'synthetic-voltage-v1', 'dtc_required': False}
    if delta < -1:
        result.update(verdict='inspection_required', summary='같은 조건에서 전압이 낮습니다. 충전 계통 점검이 필요합니다.')
        result['candidates'] = [
            {'component': '충전 계통 또는 연결부', 'support': [f'기준 대비 전압 {delta:.2f} V'],
             'counterevidence': ['충전 전류와 연결부 저항 자료가 없어 원인을 확정할 수 없음'],
             'next_tests': ['정비사가 충전 전압·전류와 단자 전압 강하를 측정'], 'certainty': 'unconfirmed'},
            {'component': '배터리 상태', 'support': ['전압 저하 반복'],
             'counterevidence': ['전압만으로 배터리 용량이나 수명을 판단할 수 없음'],
             'next_tests': ['정차 후 배터리 부하 시험'], 'certainty': 'unconfirmed'}]
    return result
