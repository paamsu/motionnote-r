"""Candidate evidence workflows, excluding legacy detection/service demonstrations."""

CATEGORIES = {
    'pothole': ('road', '도로 근거 검토'),
    'diagnosis': ('maintenance', '정비 전후 비교'),
    'crash': ('incident', '충돌 근거 정리'),
}

def owner_assessment(data):
    return {'kind':'owner_record','category':data.get('category'),'title':data.get('title'),
            'status':'unassessed','automatic_assessment':False,'responsibility_determined':False,'external_submission':False}


def comparison_notification_eligible(event):
    overview=event.get('case_overview') or {}
    return (event.get('source')=='synthetic' and event.get('evidence_valid') is True
            and event.get('assessment',{}).get('kind') in ('pothole','diagnosis')
            and overview.get('state')=='comparison_available')


def case_overview(event):
    kind=event.get('assessment',{}).get('kind')
    if kind=='owner_record' and event.get('source')=='owner_reported':
        category=event.get('assessment',{}).get('category')
        if category not in ('road','maintenance','incident'):return None
        intact=event.get('evidence_valid') is True
        return {'schema':'road-guardian.case-overview.v1','category':category,
            'title':event.get('assessment',{}).get('title','직접 남긴 기록'),
            'state':'owner_recorded' if intact else 'invalid','label':'본인 작성 · 사실 확인 전' if intact else '작성 내용 확인 실패',
            'steps':[{'label':'작성 내용','status':'해시 일치' if intact else '확인 실패','state':1 if intact else 3},
                     {'label':'센서 자료','status':'별도 추가','state':0},{'label':'외부 제출','status':'미실시','state':0}],
            'vehicle':{key:event.get('data',{}).get('context',{}).get('own_vehicle',{}).get(key) if intact else None for key in ('plate','model')},
            'source':'owner_reported','product_novelty_verified':False,'external_completion_verified':False}
    if kind not in CATEGORIES:return None
    category,title=CATEGORIES[kind]
    intact=event.get('evidence_valid') is True
    original={'label':'원본','status':'확인됨' if intact else '확인 실패','state':1 if intact else 3}
    state,label='needs_review','추가 자료 확인'
    if kind=='pothole':
        review=event.get('road_evidence_review') or {}
        follow=event.get('road_followup_case') or {}
        compared=follow.get('stage') in ('repeat_signal','no_repeat_signal')
        state,label=('comparison_available','후속 관측 비교') if compared else ('awaiting_followup','후속 관측 대기')
        steps=[original,{'label':'항목 검토','status':f"{review.get('checked_count',0)}/{review.get('item_count',6)}",
                         'state':2},
               {'label':'후속 관측','status':'비교 있음' if compared else '대기','state':1 if compared else 0}]
        if follow.get('stage')=='comparison_invalid':state,label='invalid','후속 자료 확인 실패'
        if review.get('stage')=='dismissed':state,label='dismissed','운전자 제외 기록'
    elif kind=='diagnosis':
        repair=event.get('repair_case') or {}
        compared=repair.get('stage') in ('observed_signal_recovered','follow_up_required')
        state,label=('comparison_available','재측정 비교') if compared else ('awaiting_followup','재측정 대기')
        steps=[original,{'label':'작업 기록','status':'모의 접수' if repair.get('center') else '미확인',
                         'state':1 if repair.get('center') else 0},
               {'label':'재측정','status':'비교 있음' if compared else '대기','state':1 if compared else 0}]
        if event.get('diagnostic_valid') is not True or repair.get('stage') in ('follow_up_invalid','evidence_invalid'):
            state,label='invalid','측정 자료 확인 실패'
    else:
        video=event.get('video') or {}
        camera=video.get('integrity_valid') is True and video.get('camera_evidence') is True and event.get('source')!='synthetic'
        steps=[original,{'label':'현장 영상','status':'자료 있음' if camera else '미취득','state':1 if camera else 0},
               {'label':'접촉·책임','status':'미확정','state':0}]
    if not intact:state,label='invalid','원본 확인 실패'
    if state=='invalid':
        steps=[original,{'label':'추가 검토','status':'판단 보류','state':3},
               {'label':'후속 비교','status':'판단 보류','state':0}]
    context=event.get('data',{}).get('context')
    own=context.get('own_vehicle') if intact and isinstance(context,dict) else None
    if not isinstance(own,dict):own={}
    return {'schema':'road-guardian.case-overview.v1','category':category,'title':title,
            'state':state,'label':label,'steps':steps,
            'vehicle':{key:own.get(key) for key in ('plate','model')},
            'source':event.get('source'),'product_novelty_verified':False,
            'external_completion_verified':False}
