"""Bounded controlled-marker dwell experiment, not general road vision.

Two explicitly authored fixed world markers compensate translation. A declared
zone is separate input: neither zone legality nor plate identity is recognized.
"""
import hashlib
import json
import math
from pathlib import Path
import subprocess

from PIL import Image, ImageChops

PROFILE = 'color-coded-stationary-world-v1'
SIZE = (320, 180)
HZ = 2
TOLERANCE_M = .15


def _center(frame, role):
    r, g, b = frame.split()
    conditions = {
        'target': ((r, 0, 80), (g, 70, 170), (b, 170, 255)),
        'origin': ((r, 170, 255), (g, 0, 80), (b, 0, 80)),
        'reference': ((r, 170, 255), (g, 170, 255), (b, 0, 80)),
    }
    mask = Image.new('L', frame.size, 255)
    for channel, low, high in conditions[role]:
        mask = ImageChops.multiply(mask, channel.point(lambda v: 255 if low <= v <= high else 0))
    box = mask.getbbox()
    if box is None:
        return None
    left, top, right, bottom = box
    area = mask.histogram()[255]
    if not (40 <= area <= 1600 and 6 <= right-left <= 40 and 6 <= bottom-top <= 50
            and area / ((right-left)*(bottom-top)) >= .7
            and left > 0 and top > 0 and right < frame.width and bottom < frame.height):
        return None
    return ((left+right)/2, (top+bottom)/2)


def _duration(points):
    # Anchor each run to its first position, never sum disjoint stopped intervals.
    best = 0.0
    start = 0
    for end, point in enumerate(points):
        if math.hypot(point['x_m']-points[start]['x_m'], point['y_m']-points[start]['y_m']) > TOLERANCE_M:
            start = end
        best = max(best, point['t']-points[start]['t'])
    return round(best, 3)


def analyze(video, *, profile, pixels_per_metre):
    if profile != PROFILE or type(pixels_per_metre) not in (int, float) or not math.isfinite(pixels_per_metre) or not 8 <= pixels_per_metre <= 20:
        raise ValueError('explicit controlled profile and bounded calibration required')
    path = Path(video).resolve(strict=True)
    if not path.is_file() or not 1 <= path.stat().st_size <= 64*1024*1024:
        raise ValueError('bounded local video required')
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    meta = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
        'format=duration:stream=codec_name,codec_type,width,height,start_time,avg_frame_rate', '-of', 'json', str(path)], timeout=15))
    streams = meta['streams']
    duration = float(meta['format']['duration'])
    if len(streams) != 1:
        raise ValueError('single video stream required')
    stream = streams[0]
    numerator, denominator = (float(v) for v in stream.get('avg_frame_rate','0/1').split('/'))
    if (stream.get('codec_type') != 'video' or stream.get('codec_name') != 'h264'
            or (stream.get('width'),stream.get('height')) != SIZE or not 2 <= duration <= 150
            or denominator <= 0 or abs(numerator/denominator-HZ) > .000001
            or abs(float(stream.get('start_time',0))) > .001):
        raise ValueError('bounded 320x180 H264 synthetic stream starting at zero required')
    timeline = json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
        '-show_entries','frame=best_effort_timestamp_time','-of','json',str(path)],timeout=30))['frames']
    if not 4 <= len(timeline) <= 300 or any(
            abs(float(frame.get('best_effort_timestamp_time',-1))-index/HZ) > .000001
            for index,frame in enumerate(timeline)):
        raise ValueError('complete regular two-Hz frame timestamps required')
    raw = subprocess.check_output(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-i', str(path),
        '-fps_mode', 'passthrough', '-frames:v', '301', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], timeout=45)
    frame_bytes = SIZE[0]*SIZE[1]*3
    count = len(raw)//frame_bytes
    if len(raw)%frame_bytes or count != len(timeline) or abs(count/HZ-duration) > .001:
        raise ValueError('complete bounded decoded sequence required')
    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        raise ValueError('video changed during analysis')
    points, missing, unstable = [], [], []
    initial_reference = None
    for index in range(count):
        frame = Image.frombytes('RGB',SIZE,raw[index*frame_bytes:(index+1)*frame_bytes])
        target, origin, reference = (_center(frame, role) for role in ('target','origin','reference'))
        if None in (target,origin,reference):
            missing.append(index)
            continue
        vector = (reference[0]-origin[0],reference[1]-origin[1])
        if initial_reference is None:
            initial_reference = vector
        if math.hypot(*vector) < 60 or math.dist(vector,initial_reference) > 1:
            unstable.append(index)
        points.append({'t': index/HZ, 'x_m': round((target[0]-origin[0])/pixels_per_metre,4),
                       'y_m': round((target[1]-origin[1])/pixels_per_metre,4)})
    measured = not missing and not unstable
    seconds = _duration(points) if measured else None
    return {'schema':'road-guardian.synthetic-stationary.v1','source':'synthetic_video_tracking',
        'profile':PROFILE,'video_sha256':digest,'pixels_per_metre':pixels_per_metre,
        'sample_hz':HZ,'decoded_frames':count,'missing_frames':missing,'unstable_reference_frames':unstable,
        'status':'measured' if measured else 'inconclusive','stationary_seconds':seconds,
        'candidate':seconds >= 120 if measured else None,'points':points,
        'tolerance_m':TOLERANCE_M,'reference':'two_authored_fixed_world_markers',
        'scope':'controlled synthetic color markers; sampled dwell only; no general vehicle, plate, zone recognition or legal verdict'}


def event_input(report, *, restricted_zone):
    """Convert measured pixels, plus separately authored zone, to demo policy input."""
    if not isinstance(report,dict):
        raise ValueError('measured report object required')
    points = report.get('points',[])
    count = report.get('decoded_frames')
    valid_points = (type(count) is int and 4 <= count <= 300 and isinstance(points,list) and len(points)==count
        and all(isinstance(p,dict) and p.get('t')==i/HZ
                and all(type(p.get(k)) in (int,float) and math.isfinite(p[k]) for k in ('t','x_m','y_m'))
                for i,p in enumerate(points)))
    digest = report.get('video_sha256','')
    if (type(restricted_zone) is not bool or report.get('schema')!='road-guardian.synthetic-stationary.v1'
            or report.get('profile')!=PROFILE or report.get('source')!='synthetic_video_tracking'
            or report.get('status')!='measured' or report.get('missing_frames')!=[]
            or report.get('unstable_reference_frames')!=[] or report.get('sample_hz')!=HZ
            or report.get('tolerance_m')!=TOLERANCE_M
            or report.get('reference')!='two_authored_fixed_world_markers' or not valid_points
            or not isinstance(digest,str) or len(digest)!=64 or any(c not in '0123456789abcdef' for c in digest)):
        raise ValueError('complete measured controlled report and explicit boolean zone required')
    seconds = _duration(points)
    if report.get('stationary_seconds')!=seconds or report.get('candidate') is not (seconds>=120):
        raise ValueError('dwell report must match decoded points')
    from acquisition.scenarios import generate
    data = generate('healthy')
    data['scenario'] = 'violation' if restricted_zone and seconds >= 120 else 'healthy'
    data['observations'].update(restricted_zone=restricted_zone,stationary_seconds=seconds,
        synthetic_stationary_analysis=report,
        stationary_motion={'source':'synthetic_video_tracking','video_sha256':digest,'profile':PROFILE,
                           'reference':report['reference'],'points':points,'zone_source':'authored_fixture'})
    data['context'].update({'source':'synthetic_color_tracking',
        'actor_status':'합성 영상 색 표식 · 차량 인식 아님','actors':[{'id':'vehicle-1','color':'파랑','source':'synthetic_color_tracking',
        'recognition_status':'controlled_color_only','relationship':'합성 영상의 색 표식 차량'}],
        'facts':[{'label':'연속 정지','value':str(seconds)+'초 · 합성 영상 계산'}],
        'unknowns':['제한 구역은 시험 입력','실제 차량·번호판 인식 미실행','위반 여부 미판정']})
    return data
