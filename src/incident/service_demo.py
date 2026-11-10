"""Authored warranty routing for the contest; no live warranty or booking lookup."""
from integrations.repair import select_center

def recommendation(brand, warranty):
    return {'schema':'road-guardian.service-demo.v1', 'source':'authored_contest_scenario',
            'brand':brand, 'warranty':warranty, 'external_submission':False,
            'live_warranty_lookup':False, 'offer':select_center(brand,warranty)}

def checked(event):
    data=event.get('data',{}); value=data.get('context',{}).get('service_demo')
    if (event.get('source')!='synthetic' or data.get('source')!='synthetic'
        or event.get('evidence_valid') is not True or event.get('diagnostic_valid') is not True
        or event.get('assessment',{}).get('kind')!='diagnosis' or not isinstance(value,dict)
        or value.get('brand')!=data.get('brand')):
        raise ValueError('valid same-vehicle authored diagnostic recommendation required')
    if value!=recommendation(value.get('brand'),value.get('warranty')):
        raise ValueError('authored recommendation differs from catalogue')
    if value['offer']['status']!='accepted_mock':
        raise ValueError('warranty or center availability not established')
    return value
