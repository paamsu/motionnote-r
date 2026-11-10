"""Evaluate an authored nonvisual demo interaction timeline.

Heartbeats mean the authored test channel stayed available, not that a camera
or medical sensor detected an unresponsive person. No external service is called.
"""
import copy
import math
import json

LEGACY_PROFILE = 'authored-unresponsive-timeline-v1'
PROFILE = 'authored-unresponsive-timeline-v2'
TIMELINE_SCHEMA = 'road-guardian.emergency-timeline.v1'
REPORT_SCHEMA = 'road-guardian.emergency-timeline-analysis.v1'


def _number(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def analyze(timeline, *, as_of):
    """Replay bounded authored observations at an explicit relative test time."""
    keys = {'schema', 'source', 'profile', 'timeout_seconds', 'heartbeat_max_gap_seconds', 'events'}
    if (not isinstance(timeline, dict) or set(timeline) != keys
            or timeline.get('schema') != TIMELINE_SCHEMA
            or timeline.get('source') != 'authored_synthetic' or timeline.get('profile') not in (LEGACY_PROFILE, PROFILE)):
        raise ValueError('explicit authored synthetic timeline profile required')
    timeout, gap = timeline['timeout_seconds'], timeline['heartbeat_max_gap_seconds']
    if (not _number(timeout) or not 10 <= timeout <= 120 or not _number(gap) or not 1 <= gap <= 5
            or not _number(as_of) or not 0 <= as_of <= 3600):
        raise ValueError('bounded finite timeout, heartbeat gap and evaluation time required')
    events = timeline['events']
    if not isinstance(events, list) or not 1 <= len(events) <= 1000:
        raise ValueError('bounded nonempty event sequence required')
    previous = -1
    terminal = None
    for index, event in enumerate(events):
        if (not isinstance(event, dict) or set(event) != {'t', 'kind'}
                or not _number(event['t']) or not 0 <= event['t'] <= as_of
                or event['t'] <= previous or type(event['kind']) is not str
                or event['kind'] not in ('unresponsive_onset', 'heartbeat', 'response', 'cancel')):
            raise ValueError('finite chronological events without future or duplicate observations required')
        if (index == 0 and event['kind'] != 'unresponsive_onset'
                or index > 0 and event['kind'] == 'unresponsive_onset'
                or terminal is not None):
            raise ValueError('one onset and at most one final response or cancel required')
        if event['kind'] in ('response', 'cancel'):
            terminal = event
        previous = event['t']
    onset = events[0]['t']
    deadline = onset + timeout
    # A terminal interaction closes the observation interval; the profile below
    # determines how much of that interval must establish the past decision.
    endpoint = terminal['t'] if terminal else as_of
    # v1 keeps historical report bytes reproducible. v2 evaluates only the
    # monitoring interval before the first terminal interaction or deadline.
    coverage_end = min(endpoint, deadline) if timeline['profile'] == PROFILE else endpoint
    coverage_events = [event for event in events if event['t'] <= coverage_end]
    gaps = [{'from': left['t'], 'to': right['t']} for left, right in zip(coverage_events, coverage_events[1:])
            if right['t'] - left['t'] > gap]
    if coverage_end - coverage_events[-1]['t'] > gap:
        gaps.append({'from': coverage_events[-1]['t'], 'to': coverage_end})
    if gaps:
        status, reason = 'inconclusive', 'heartbeat_gap'
    elif terminal and terminal['t'] <= deadline:
        status = 'responded' if terminal['kind'] == 'response' else 'cancelled'
        reason = 'interaction_at_or_before_deadline'
    elif as_of >= deadline:
        status, reason = 'timed_out', 'continuous_authored_channel_reached_timeout'
    else:
        status, reason = 'waiting', 'deadline_not_reached'
    return {'schema': REPORT_SCHEMA, 'source': 'authored_synthetic', 'profile': timeline['profile'],
        'timeline': copy.deepcopy(timeline), 'as_of': as_of, 'onset': onset, 'deadline': deadline,
        'elapsed_seconds': endpoint-onset, 'status': status, 'reason': reason, 'heartbeat_gaps': gaps,
        'unresponsive_test_signal': status == 'timed_out' if status != 'inconclusive' else None,
        'visual_analysis_performed': False, 'medical_detection_performed': False,
        'external_submission': False,
        'scope': 'authored nonvisual sensor/interaction demo; no camera, medical or real emergency detection'}


def event_input(report):
    """Bind replay-consistent authored data to a synthetic event; unknown refuses."""
    if not isinstance(report, dict) or report.get('schema') != REPORT_SCHEMA:
        raise ValueError('authored timeline analysis report required')
    expected = analyze(report.get('timeline'), as_of=report.get('as_of'))
    try:
        consistent = json.dumps(report, sort_keys=True, allow_nan=False) == json.dumps(expected, sort_keys=True, allow_nan=False)
    except (TypeError, ValueError):
        consistent = False
    if not consistent or expected['status'] == 'inconclusive':
        raise ValueError('consistent conclusive authored timeline required')
    from acquisition.scenarios import generate
    data = generate('healthy')
    data['scenario'] = 'emergency' if expected['status'] == 'timed_out' else 'healthy'
    data['observations']['unresponsive_test_signal'] = expected['unresponsive_test_signal']
    data['observations']['emergency_timeline'] = copy.deepcopy(expected['timeline'])
    data['observations']['emergency_timeline_analysis'] = copy.deepcopy(expected)
    data['context']['facts'] = [
        {'label': '응답 확인 시험', 'value': expected['status']},
        {'label': '경과 시간', 'value': str(expected['elapsed_seconds'])+'초 · 작성된 시험 입력'},
        {'label': '대기 기준', 'value': str(expected['timeline']['timeout_seconds'])+'초 · 시연 설정'}]
    data['context']['unknowns'] = ['실제 무응답·의학적 상태 감지 아님', '카메라 분석 미실행', '실제 119 연결 없음']
    return data
