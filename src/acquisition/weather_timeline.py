"""Authored demo signal timing; never visual rain recognition or vehicle sensing."""

import copy
import math

SCHEMA = "road-guardian.weather-timeline.v1"
REPORT_SCHEMA = "road-guardian.weather-timeline-analysis.v1"
SIGNALS = ("wiper_high", "visibility_low", "rain_sensor_wet", "washer_active")
THRESHOLDS = {
    "sustained_seconds": 5.0,
    "recovery_seconds": 3.0,
    "recent_washer_seconds": 10.0,
    "maximum_gap_seconds": 1.0,
}


def number(value):
    # Bound before float conversion so enormous integers cannot overflow it.
    if (
        type(value) not in (int, float)
        or not 0 <= value <= 600
        or not math.isfinite(value)
    ):
        raise ValueError("timestamp must be finite in 0..600 seconds")
    return float(value)


def analyze(timeline, *, as_of):
    now = number(as_of)
    if (
        not isinstance(timeline, dict)
        or set(timeline) != {"schema", "source", "samples"}
        or timeline["schema"] != SCHEMA
        or timeline["source"] != "authored_synthetic"
        or not isinstance(timeline["samples"], list)
        or len(timeline["samples"]) > 601
    ):
        raise ValueError("strict authored weather timeline required")
    samples = timeline["samples"]
    times = []
    for sample in samples:
        if (
            not isinstance(sample, dict)
            or set(sample) != {"t", *SIGNALS}
            or any(type(sample[key]) is not bool for key in SIGNALS)
        ):
            raise ValueError("sample needs timestamp and four explicit booleans")
        at = number(sample["t"])
        if (not times and at != 0) or (times and at <= times[-1]) or at > now:
            raise ValueError(
                "samples must start at zero, strictly increase, and never be future observations"
            )
        times.append(at)
    if times and now - times[-1] > 1:
        raise ValueError("evaluation must be within one second of the latest sample")
    report = dict(
        schema=REPORT_SCHEMA,
        source="auth_timeline",
        camera_analysis_performed=False,
        timeline=copy.deepcopy(timeline),
        as_of=now,
        thresholds=copy.deepcopy(THRESHOLDS),
        status="inconclusive",
        reason="missing_samples",
        sustained_seconds=0.0,
        clear_seconds=0.0,
        qualified_before=False,
        recent_washer=False,
        last_washer_end=None,
        current=({key: samples[-1][key] for key in SIGNALS} if samples else None),
    )
    if not times:
        return report
    if any(b - a > 1 for a, b in zip(times, times[1:])):
        report["reason"] = "sample_gap"
        return report

    rain_start = None
    clear_start = None
    qualified = False
    last_washer_end = None
    for index, sample in enumerate(samples):
        start = times[index]
        end = times[index + 1] if index + 1 < len(times) else now
        raining = (
            sample["wiper_high"]
            and sample["visibility_low"]
            and sample["rain_sensor_wet"]
            and not sample["washer_active"]
        )
        if raining:
            if rain_start is None:
                rain_start = start
            if end - rain_start >= 5 and (
                last_washer_end is None or end - last_washer_end > 10
            ):
                qualified = True
        else:
            rain_start = None
        if not sample["visibility_low"]:
            if clear_start is None:
                clear_start = start
        else:
            clear_start = None
        if sample["washer_active"]:
            last_washer_end = end
    sustained = 0.0 if rain_start is None else now - rain_start
    clear = 0.0 if clear_start is None else now - clear_start
    recent = last_washer_end is not None and now - last_washer_end <= 10
    report.update(
        sustained_seconds=sustained,
        clear_seconds=clear,
        qualified_before=qualified,
        recent_washer=recent,
        last_washer_end=last_washer_end,
    )
    if recent:
        report.update(
            status="washer_suppressed", reason="washer_active_or_within_10_seconds"
        )
    elif sustained >= 5:
        report.update(
            status="sustained_candidate",
            reason="continuous_authored_signal_conjunction",
        )
    elif qualified and clear >= 3:
        report.update(
            status="recovered", reason="qualified_history_then_clear_visibility"
        )
    else:
        report.update(
            status="collecting",
            reason=(
                "candidate_not_sustained"
                if samples[-1]["visibility_low"]
                else "recovery_not_sustained" if qualified else "no_qualified_candidate"
            ),
        )
    return report
