import sys,json
from pathlib import Path
from xml.etree import ElementTree as ET
root=Path(sys.argv[1])
for phase in ('baseline','mixed'):
    samples=list(ET.parse(root/f'{phase}.jtl').getroot())
    assert samples, 'No JMeter samples'
    assert all(s.get('s')=='true' for s in samples), [(s.get('lb'),s.get('rm')) for s in samples if s.get('s')!='true']
    for label in ('academic-28080','academic-28081','offering-28080','offering-28081'):
        assert sum(s.get('lb')==label for s in samples)>0, label
    journeys=[json.loads(s.findtext('responseData')) for s in samples if s.get('lb')=='journey']
    assert len(journeys)==(2 if phase=='mixed' else 0)
    for j in journeys:
        assert j['outcome']=='CONFIRMED'
        assert len(j['requests'])==4
        assert [r['status'] for r in j['requests']]==[200,200,200,201]
print('JMeter transport, labels, metadata and journeys passed')
