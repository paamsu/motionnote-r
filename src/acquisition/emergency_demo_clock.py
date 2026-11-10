"""Live clock capture for an explicitly started synthetic response exercise.

This collects software liveness and explicit interactions, never a person's
medical state. The caller owns scheduling; missed ticks are not backfilled.
"""

import threading
import time

from acquisition.emergency_timeline import PROFILE, TIMELINE_SCHEMA, analyze


class EmergencyDemoClock:
    def __init__(
        self,
        *,
        timeout_seconds=30,
        heartbeat_max_gap_seconds=5,
        clock_ns=time.monotonic_ns,
    ):
        self._clock = clock_ns
        self._lock = threading.Lock()
        self._origin = self._clock()
        if type(self._origin) is not int:
            raise ValueError("integer monotonic clock required")
        self._last_read = self._origin
        self._closed = False
        self._timeline = dict(
            schema=TIMELINE_SCHEMA,
            source="authored_synthetic",
            profile=PROFILE,
            timeout_seconds=timeout_seconds,
            heartbeat_max_gap_seconds=heartbeat_max_gap_seconds,
            events=[dict(t=0, kind="unresponsive_onset")],
        )
        analyze(self._timeline, as_of=0)

    def _elapsed(self):
        now = self._clock()
        if type(now) is not int or now < self._last_read:
            raise ValueError("monotonic clock moved backwards or is invalid")
        elapsed = (now - self._origin) / 1_000_000_000
        if elapsed > 3600:
            raise ValueError("demo capture exceeds one hour")
        self._last_read = now
        return elapsed

    def record(self, kind):
        """Record a current tick/interaction; no caller-supplied timestamps."""
        with self._lock:
            if kind not in ("heartbeat", "response", "cancel") or self._closed:
                raise ValueError("open capture and supported interaction required")
            elapsed = self._elapsed()
            if elapsed <= self._timeline["events"][-1]["t"]:
                raise ValueError("distinct monotonic event time required")
            candidate = dict(
                self._timeline,
                events=self._timeline["events"] + [dict(t=elapsed, kind=kind)],
            )
            report = analyze(candidate, as_of=elapsed)
            self._timeline = candidate
            self._closed = kind in ("response", "cancel")
            return report

    def snapshot(self):
        with self._lock:
            return analyze(self._timeline, as_of=self._elapsed())
