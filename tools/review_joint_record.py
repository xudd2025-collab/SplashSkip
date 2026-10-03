"""Attach an offline recording review to a COPY of exported records; never changes phone labels."""
import argparse,hashlib,json
from pathlib import Path

def review(export,record_id,label,video,trace,notes,output):
    data=json.loads(export.read_text(encoding='utf-8'))
    records=[r for r in data['records'] if r['id']==record_id]
    if len(records)!=1 or records[0].get('attempted') is False:
        raise ValueError('Expected one attempted click record')
    if record_id not in trace.read_text(encoding='utf-8'):
        raise ValueError('Trace does not reference this exact record')
    if label not in ('success','no_effect','mistouch') or not notes.strip():
        raise ValueError('Review needs a supported outcome and explanation')
    r=records[0];r['label']=label;r['source']='recording_review'
    r['evidence']={'video':video.name,'video_sha256':hashlib.sha256(video.read_bytes()).hexdigest(),
                   'trace':trace.name,'trace_sha256':hashlib.sha256(trace.read_bytes()).hexdigest(),'notes':notes}
    output.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('export',type=Path);p.add_argument('--id',required=True);p.add_argument('--label',required=True)
    p.add_argument('--video',type=Path,required=True);p.add_argument('--trace',type=Path,required=True)
    p.add_argument('--notes',required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();review(a.export,a.id,a.label,a.video,a.trace,a.notes,a.output)
