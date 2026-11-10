"""Corroborate synthetic model-localized plate text with same-image native OCR.

This is a review artifact, not vehicle identity, watchlist access or an incident.
Both observations must come from trusted local runners; hashes do not authenticate
an arbitrary caller's claim that inference happened.
"""
import hashlib
import io
import json
import re
from PIL import Image


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


LEGACY_PROFILE = 'demo-token-v1'
KOREAN_PROFILE = 'synthetic-korean-text-v2'


def corroborate(image_bytes, localization, ocr, *, profile=LEGACY_PROFILE):
    if profile not in (LEGACY_PROFILE, KOREAN_PROFILE):
        raise ValueError('unsupported synthetic text profile')
    if type(image_bytes) is not bytes or not 1 <= len(image_bytes) <= 16 * 1024 * 1024:
        raise ValueError('bounded original image required')
    sha = hashlib.sha256(image_bytes).hexdigest()
    with Image.open(io.BytesIO(image_bytes), formats=['PNG', 'JPEG']) as image:
        width, height = image.size
        if not 1 <= width <= 4096 or not 1 <= height <= 4096 or getattr(image, 'n_frames', 1) != 1:
            raise ValueError('bounded single image required')
        if image.getexif().get(274, 1) != 1:
            raise ValueError('non-identity orientation needs explicit coordinate transform')
        image.load()
    for observation in (localization, ocr):
        if not isinstance(observation, dict) or observation.get('input_sha256') != sha:
            raise ValueError('observations must reference original bytes')
    if localization.get('original_dimensions') != [width, height]:
        raise ValueError('original coordinate dimensions required')
    plates = localization.get('plates'); lines = ocr.get('lines')
    if not isinstance(plates, list) or len(plates) > 12 or not isinstance(lines, list) or len(lines) > 128:
        raise ValueError('bounded observations required')
    def box(b, w, h):
        if not isinstance(b, list) or len(b) != 4 or any(type(v) is not int for v in b) or not 0 <= b[0] < b[2] <= w or not 0 <= b[1] < b[3] <= h:
            raise ValueError('ordered integer coordinates required')
        return b
    boxes = []
    for p in plates:
        if not isinstance(p, dict) or (p.get('text') is not None and (not isinstance(p['text'], str) or len(p['text']) > 80)):
            raise ValueError('bounded model text required')
        b = box(p.get('box'), 1000, 1000)
        boxes.append([b[0]*width/1000, b[1]*height/1000, b[2]*width/1000, b[3]*height/1000])
    for line in lines:
        if not isinstance(line, dict) or not isinstance(line.get('text'), str) or not 1 <= len(line['text']) <= 1000:
            raise ValueError('bounded OCR text required')
        if line.get('bounding_box') is not None:box(line['bounding_box'], width, height)
    def overlaps(a, b):
        return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]
    rows = []
    for index, (plate, region) in enumerate(zip(plates, boxes)):
        reason = None; selected = []
        if localization.get('format_valid') is not True or localization.get('visual_analysis_performed') is not True:
            reason = 'localization_unavailable'
        elif any(line.get('bounding_box') is None for line in lines):
            reason = 'ocr_geometry_missing'
        elif any(overlaps(region, b) for j, b in enumerate(boxes) if j != index):
            reason = 'overlapping_plate_regions'
        elif plate.get('text') is None:
            reason = 'model_text_unreadable'
        else:
            for line in lines:
                b = line['bounding_box']
                if not overlaps(region, b):continue
                if not (region[0] <= b[0] and region[1] <= b[1] and b[2] <= region[2] and b[3] <= region[3]):
                    reason = 'ocr_crosses_plate_boundary';break
                selected.append(line['text'])
            if reason is None:
                if len(selected) != 1:reason = 'single_ocr_line_required'
                elif selected[0] != plate['text']:reason = 'text_disagreement'
                elif not (re.fullmatch(r'DEMO-[0-9]{3,4}', selected[0]) or
                          (profile == KOREAN_PROFILE and re.fullmatch(r'[0-9]{2,3}[가-힣] ?[0-9]{4}', selected[0]))):
                    # Chosen synthetic fixture syntax, not legal registration validation.
                    # Exact observations must already agree; no character/space repair.
                    reason = 'synthetic_token_required' if profile == LEGACY_PROFILE else 'incomplete_or_unsupported_synthetic_text'
                elif sum(p.get('text') == plate['text'] for p in plates) != 1:reason = 'duplicate_plate_text'
        rows.append(dict(index=index, box_original=region, observed_text=plate.get('text'),
                         status='inconclusive' if reason else 'corroborated_text_candidate',
                         reason=reason or 'same_region_exact_text_agreement'))
    result = dict(schema='road-guardian.plate-text-agreement.v1', input_sha256=sha,
                localization_sha256=_digest(localization), ocr_sha256=_digest(ocr), rows=rows,
                requires_review=True, vehicle_identification_performed=False,
                real_watchlist_accessed=False, camera_evidence=False, recipient=None)
    if profile == KOREAN_PROFILE:
        result.update(schema='road-guardian.plate-text-agreement.v2', profile=profile)
    return result


def parse_localization(raw):
    try:
        raw=raw.strip()
        if raw.startswith('```json\n') and raw.endswith('\n```'):
            raw=raw[len('```json\n'):-len('\n```')]
        def unique(pairs):
            result={}
            for key,value in pairs:
                if key in result:raise ValueError('duplicate key')
                result[key]=value
            return result
        obj=json.loads(raw,object_pairs_hook=unique)
        if not isinstance(obj,dict) or set(obj)!={'plates'} or not isinstance(obj['plates'],list) or len(obj['plates'])>12:raise ValueError('output contract')
        for plate in obj['plates']:
            if not isinstance(plate,dict) or set(plate)!={'text','box','vehicle'}:raise ValueError('plate contract')
            b=plate['box']
            if not isinstance(b,list) or len(b)!=4 or any(type(v)is not int for v in b) or not 0<=b[0]<b[2]<=1000 or not 0<=b[1]<b[3]<=1000:raise ValueError('box contract')
            if plate['text'] is not None and (not isinstance(plate['text'],str) or len(plate['text'])>80):raise ValueError('text contract')
            if not isinstance(plate['vehicle'],str) or len(plate['vehicle'])>200:raise ValueError('vehicle contract')
        return {'status':'candidate','plates':obj['plates'],'format_valid':True}
    except (ValueError,TypeError):
        return {'status':'abstained','plates':[],'format_valid':False}
