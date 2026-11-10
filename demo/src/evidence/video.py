"""Temporal coverage and deterministic video fixtures, never camera evidence."""
from dataclasses import dataclass
import hashlib
import math
from pathlib import Path
import shutil
import subprocess
import uuid

@dataclass(frozen=True)
class Segment:
    path: Path
    start: float
    end: float

class VideoBuffer:
    def __init__(self, retention_seconds=30):
        if retention_seconds <= 0:
            raise ValueError('positive retention required')
        self.retention = retention_seconds
        self.segments = []

    def append(self, segment):
        if not math.isfinite(segment.start) or not math.isfinite(segment.end) or segment.end <= segment.start:
            raise ValueError('valid segment interval required')
        if self.segments and segment.start < self.segments[-1].end:
            raise ValueError('overlapping or out-of-order segment')
        self.segments.append(segment)
        cutoff = segment.end - self.retention
        self.segments = [s for s in self.segments if s.end > cutoff]

    def window(self, trigger, before=5, after=5):
        if not all(math.isfinite(v) for v in (trigger,before,after)) or before < 0 or after < 0 or before+after <= 0:
            raise ValueError('invalid event window')
        start, end = trigger-before, trigger+after
        selected = [s for s in self.segments if s.end > start and s.start < end]
        cursor = start
        for segment in selected:
            if segment.start > cursor + 0.001:
                raise ValueError('video coverage gap')
            cursor = max(cursor, segment.end)
        if not selected or cursor < end:
            raise ValueError('waiting for complete pre/post coverage')
        return selected

def sha256(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as handle:
        for block in iter(lambda:handle.read(65536),b''):digest.update(block)
    return digest.hexdigest()

def generate_fixture(directory):
    ffmpeg=shutil.which('ffmpeg')
    if not ffmpeg:raise ValueError('ffmpeg required for synthetic video fixture')
    directory=Path(directory);directory.mkdir(parents=True,exist_ok=True)
    source=directory/'synthetic-source.mp4'
    if not source.exists():
        temporary=directory/(str(uuid.uuid4())+'.mp4')
        subprocess.run([ffmpeg,'-v','error','-f','lavfi','-i','testsrc2=size=640x360:rate=10:duration=30',
                        '-an','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-movflags','+faststart',str(temporary)],check=True,timeout=30)
        temporary.replace(source)
    buffer=VideoBuffer()
    buffer.append(Segment(source,0,30))
    buffer.window(10,5,5)
    clip=directory/(str(uuid.uuid4())+'.mp4')
    subprocess.run([ffmpeg,'-v','error','-ss','5','-i',str(source),'-t','10','-an','-c:v','libx264',
                    '-preset','ultrafast','-pix_fmt','yuv420p','-movflags','+faststart',str(clip)],check=True,timeout=30)
    return {'status':'synthetic_clip','source':'synthetic_test_pattern','path':str(clip.resolve()),
            'sha256':sha256(clip),'bytes':clip.stat().st_size,'mime':'video/mp4',
            'trigger_seconds':10,'start_seconds':5,'end_seconds':15,'duration_seconds':10,
            'original_sha256':sha256(source),'camera_evidence':False}
