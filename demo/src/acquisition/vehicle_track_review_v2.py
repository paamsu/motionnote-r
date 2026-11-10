"""Experimental image-space associations for review, never a collision detector.

No appearance text, scene labels or rendered truth is used. Sparse sampling and
perspective can defeat association; identity is a hypothesis, not an observation.
This module is deliberately not connected to incident policy or dispatch.
"""
import itertools
import math

PROFILE = 'vehicle-box-review-v2'
MAX_GAP_SECONDS = 2.1
MAX_CENTER_STEP = 0.45
MIN_ASSIGNMENT_MARGIN = 0.08


def _center(box):
    return ((box[0] + box[2]) / 2000, (box[1] + box[3]) / 2000)


def review(frames):
    """Return tentative associations; a continuity failure requires a new run."""
    if not isinstance(frames, list) or not frames:
        raise ValueError('Nonempty chronological frame list required')
    previous_time = None
    previous_boxes = None
    track_ids = []
    broken = False
    results = []
    for frame in frames:
        time = frame['t']
        if type(time) not in (int, float) or not math.isfinite(time) or time < 0:
            raise ValueError('Finite nonnegative time required')
        if previous_time is not None and time <= previous_time:
            raise ValueError('Strictly increasing timestamps required')
        boxes = frame['boxes']
        if not isinstance(boxes, list) or len(boxes) > 6:
            raise ValueError('At most six boxes supported by experimental profile')
        for box in boxes:
            if (not isinstance(box, list) or len(box) != 4
                    or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1000 for v in box)
                    or box[0] >= box[2] or box[1] >= box[3]):
                raise ValueError('Invalid normalized box')
        reason = None
        assignments = []
        if broken:
            reason = 'continuity_already_unresolved'
        elif not boxes:
            reason = 'no_detections'
        elif previous_boxes is None:
            track_ids = list(range(1, len(boxes) + 1))
            assignments = track_ids[:]
        elif time - previous_time > MAX_GAP_SECONDS:
            reason = 'sampling_gap'
        else:
            # With fewer detections, multiple prior centers suggest a merged box.
            # At unchanged counts, motion can put a prior center in another box;
            # use one-to-one assignment and its ambiguity margin instead.
            centers = [_center(box) for box in previous_boxes]
            merged = any(sum(box[0] / 1000 <= x <= box[2] / 1000 and
                             box[1] / 1000 <= y <= box[3] / 1000
                             for x, y in centers) > 1 for box in boxes)
            if merged and len(boxes) < len(previous_boxes):
                reason = 'possible_merge_or_occlusion'
            elif len(boxes) != len(previous_boxes):
                reason = 'detection_count_changed'
            else:
                candidates = []
                for permutation in itertools.permutations(range(len(boxes))):
                    distances = [math.dist(_center(previous_boxes[j]), _center(boxes[i]))
                                 for i, j in enumerate(permutation)]
                    if max(distances) <= MAX_CENTER_STEP:
                        candidates.append((sum(distances), permutation))
                candidates.sort()
                if not candidates:
                    reason = 'motion_not_associable'
                elif len(candidates) > 1 and candidates[1][0] - candidates[0][0] < MIN_ASSIGNMENT_MARGIN:
                    reason = 'ambiguous_assignment'
                else:
                    assignments = [track_ids[j] for j in candidates[0][1]]
        broken = broken or reason is not None
        results.append(dict(t=time, state='unresolved' if reason else 'tentative',
                            reason=reason, track_ids=assignments))
        previous_time = time
        previous_boxes = [box[:] for box in boxes]
        if assignments:
            track_ids = assignments
    return dict(profile=PROFILE, state='unresolved' if broken else 'tentative',
                frames=results, identity_verified=False, collision_verified=False,
                scope='Image-space review only; thresholds not calibrated for road use')
