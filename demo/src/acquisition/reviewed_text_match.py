"""Exact synthetic-list comparison of OCR inside manually reviewed image regions.

This does not locate vehicles/plates, authenticate a real watchlist, or create an
incident. Regions remain explicit reviewer input, not model-derived detections.
"""
import hashlib
import io
import json
import math
import re
from PIL import Image

TOKEN = re.compile(r'DEMO-[0-9]{3,4}\Z')


def _hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=False, allow_nan=False).encode()).hexdigest()


def _time(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError('finite nonnegative timestamp required')
    return value


def compare(image_bytes, ocr, regions, fixture_list, *, at):
    """Return a review-only text candidate; malformed contracts raise ValueError."""
    if type(image_bytes) is not bytes or not 1 <= len(image_bytes) <= 16*1024*1024:
        raise ValueError('bounded original image bytes required')
    photo_hash = hashlib.sha256(image_bytes).hexdigest()
    with Image.open(io.BytesIO(image_bytes), formats=['PNG', 'JPEG']) as image:
        width, height = image.size
        if not 1 <= width <= 4096 or not 1 <= height <= 4096 or getattr(image, 'n_frames', 1) != 1:
            raise ValueError('bounded single image required')
        image.load()
    if not isinstance(ocr, dict) or ocr.get('input_sha256') != photo_hash:
        raise ValueError('OCR must reference these image bytes')
    if not isinstance(regions, dict) or regions.get('source') != 'manual_synthetic_review' or regions.get('input_sha256') != photo_hash:
        raise ValueError('explicit same-image manual region review required')
    boxes = regions.get('boxes')
    lines = ocr.get('lines')
    if not isinstance(boxes, list) or not 1 <= len(boxes) <= 8 or not isinstance(lines, list) or len(lines) > 128:
        raise ValueError('bounded region and OCR lists required')

    def box(value):
        if (not isinstance(value, list) or len(value) != 4 or any(type(v) is not int for v in value)
                or not 0 <= value[0] < value[2] <= width or not 0 <= value[1] < value[3] <= height):
            raise ValueError('integer box within original image required')
        return value

    boxes = [box(b) for b in boxes]
    for i, a in enumerate(boxes):
        for b in boxes[i+1:]:
            if a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]:
                raise ValueError('review regions must not overlap')
    checked = []
    for line in lines:
        if not isinstance(line, dict) or not isinstance(line.get('text'), str) or not 1 <= len(line['text']) <= 1000:
            raise ValueError('bounded original OCR line required')
        # Missing geometry cannot be safely classified as outside the reviewed region.
        checked.append((line['text'], None if line.get('bounding_box') is None else box(line['bounding_box'])))
    if not isinstance(fixture_list, dict) or fixture_list.get('source') != 'synthetic_fixture' or fixture_list.get('authorized') is not True:
        raise ValueError('explicitly authorized synthetic fixture list required')
    if not isinstance(fixture_list.get('id'), str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', fixture_list['id']):
        raise ValueError('bounded list identifier required')
    start = _time(fixture_list.get('valid_from')); end = _time(fixture_list.get('valid_until')); now = _time(at)
    if start >= end:
        raise ValueError('nonempty validity interval required')
    entries = fixture_list.get('entries')
    if (not isinstance(entries, list) or len(entries) > 100 or any(not isinstance(e, str) or not TOKEN.fullmatch(e) for e in entries)
            or len(entries) != len(set(entries))):
        raise ValueError('unique bounded synthetic tokens required')
    result = {'schema': 'road-guardian.reviewed-text-match.v1', 'input_sha256': photo_hash,
              'ocr_sha256': _hash(ocr), 'region_review_sha256': _hash(regions), 'fixture_list_sha256': _hash(fixture_list),
              'list_id': fixture_list['id'], 'evaluated_at': now, 'region_source': 'manual_synthetic_review',
              'status': 'inconclusive', 'observed_text': None, 'requires_review': True,
              'vehicle_identification_performed': False, 'plate_localization_performed': False,
              'real_watchlist_accessed': False, 'camera_evidence': False, 'recipient': None}
    if not start <= now < end:
        return dict(result, status='list_unavailable', reason='outside_list_validity')
    if any(b is None for _, b in checked):
        return dict(result, reason='ocr_geometry_missing')
    # Multiple manually selected vehicles remain ambiguous, even with equal tokens.
    if len(boxes) != 1:
        return dict(result, reason='multiple_review_regions')
    region = boxes[0]; selected = []
    for text, b in checked:
        overlaps = b[0] < region[2] and region[0] < b[2] and b[1] < region[3] and region[1] < b[3]
        contained = region[0] <= b[0] and region[1] <= b[1] and b[2] <= region[2] and b[3] <= region[3]
        if overlaps and not contained:
            return dict(result, reason='ocr_crosses_review_boundary')
        if contained:
            selected.append(text)
    if len(selected) != 1 or not TOKEN.fullmatch(selected[0]):
        return dict(result, reason='single_exact_demo_token_not_observed')
    token = selected[0]
    return dict(result, status='text_match_candidate' if token in entries else 'text_not_in_list',
                reason='exact_text_comparison_only', observed_text=token)
