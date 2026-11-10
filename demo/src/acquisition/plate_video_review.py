"""Bounded offline plate observations at decoded video times; no identity fusion."""
import hashlib
import json
import math
from pathlib import Path
import subprocess
import tempfile

from acquisition.plate_text_agreement import corroborate, LEGACY_PROFILE


def review(video, observations, *, work_dir, profile=LEGACY_PROFILE):
    video = Path(video).resolve(strict=True)
    work_dir = Path(work_dir).resolve(strict=True)
    if not video.is_file() or not 1 <= video.stat().st_size <= 32 * 1024 * 1024:
        raise ValueError('bounded local video required')
    if not isinstance(observations, list) or not 1 <= len(observations) <= 24:
        raise ValueError('one to twenty-four chronological observations required')
    digest = hashlib.sha256(video.read_bytes()).hexdigest()
    probe = subprocess.run([
        'ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames',
        '-show_entries', 'frame=best_effort_timestamp_time,width,height',
        '-of', 'json', str(video)], check=True, capture_output=True, timeout=20)
    frames = json.loads(probe.stdout).get('frames', [])
    if not 1 <= len(frames) <= 960:
        raise ValueError('bounded frame count required')
    times = []
    for frame in frames:
        if not 1 <= frame['width'] <= 1920 or not 1 <= frame['height'] <= 1080:
            raise ValueError('bounded frame dimensions required')
        t = float(frame['best_effort_timestamp_time'])
        if not math.isfinite(t) or not 0 <= t <= 16 or (times and t <= times[-1]):
            raise ValueError('ordered bounded presentation timestamps required')
        times.append(t)
    rows = []
    previous = -1
    with tempfile.TemporaryDirectory(prefix='plate-video-review-', dir=work_dir) as temporary:
        for entry in observations:
            index, t = entry.get('index'), entry.get('t')
            if type(index) is not int or not previous < index < len(frames):
                raise ValueError('unique increasing frame indices required')
            if type(t) not in (int, float) or not math.isfinite(t) or abs(t-times[index]) > 0.000001:
                raise ValueError('observation timestamp must match decoded presentation time')
            if entry.get('video_sha256') != digest:
                raise ValueError('observation must reference this video')
            image = entry.get('image_bytes')
            if type(image) is not bytes or not 1 <= len(image) <= 8*1024*1024:
                raise ValueError('bounded original frame bytes required')
            decoded = Path(temporary)/f'{index}.png'
            subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-i', str(video),
                            '-vf', f'select=eq(n\\,{index})', '-frames:v', '1', str(decoded)],
                           check=True, capture_output=True, timeout=20)
            if decoded.read_bytes() != image:
                raise ValueError('observation image is not the selected decoded frame')
            agreement = corroborate(image, entry['localization'], entry['ocr'], profile=profile)
            rows.append(dict(index=index, t=times[index], agreement=agreement))
            previous = index
    if hashlib.sha256(video.read_bytes()).hexdigest() != digest:
        raise ValueError('video changed during review')
    return dict(schema='road-guardian.plate-video-review.v1', video_sha256=digest,
                decoded_frame_count=len(frames), observations=rows,
                vehicle_identity_verified=False, cross_frame_text_fusion=False,
                watchlist_accessed=False, requires_review=True,
                scope='Sparse decoded-frame text observations; missing or inconclusive samples remain unverified')
