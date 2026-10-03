"""Add separately identified OCR observations; never fabricate native parent/child edges."""
import argparse,json,subprocess,os,shutil
from pathlib import Path
from inspect_capture import render


def contains(a,b):
    return a[0]<=b[0] and a[1]<=b[1] and a[2]>=b[2] and a[3]>=b[3]


def merge(frame,ocr):
    sx=frame['width']/frame.get('image_width',frame['width'])
    sy=frame['height']/frame.get('image_height',frame['height'])
    native=frame['tree_nodes']; visual=[]
    for word in ocr['words']:
        b=[round(word['bounds'][j]*(sx if j%2==0 else sy)) for j in range(4)]
        if b[2]<=b[0] or b[3]<=b[1]:continue
        # Retain native text controls as the primary representation. A large blank
        # canvas containing the word is not a duplicate native text control.
        same=[n for n in native if n.get('visible') and word['text'] in (n.get('text'),n.get('description'))
              and contains(n['bounds'],b)]
        if same:continue
        containers=[n for n in native if n.get('visible') and contains(n['bounds'],b)]
        area=lambda n:(n['bounds'][2]-n['bounds'][0])*(n['bounds'][3]-n['bounds'][1])
        anchor=min(containers,key=area)['index'] if containers else -1
        visual.append(dict(index=len(visual),parent=-1,anchor_native_index=anchor,
            relation='spatial_containment_only',source='vision',native_action_available=False,
            bounds=b,text=word['text'],confidence=word['confidence'],role=word['role'],
            description='',id='',**{'class':'VisualText'},clickable=False,enabled=True,visible=True,child_count=0))
    frame['visual_nodes']=visual
    frame['visual_inspection']={k:ocr[k] for k in ('status','boxes','recognized')}
    frame['visual_inspection']['native_edges_inferred']=False
    return frame


def main():
    project=Path(__file__).resolve().parents[2]
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('folder',type=Path)
    java_home=os.environ.get('JAVA_HOME')
    java_default=Path(java_home)/'bin'/('java.exe' if os.name=='nt' else 'java') if java_home else shutil.which('java')
    p.add_argument('--java',type=Path,default=java_default)
    p.add_argument('--runtime',type=Path,default=os.environ.get('SPLASHSKIP_DESKTOP_ONNX'))
    p.add_argument('--classes',type=Path,default=project/'.build/checks')
    p.add_argument('--assets',type=Path,default=project/'app/src/main/assets');a=p.parse_args()
    if not a.java or not a.java.is_file():p.error('Set JAVA_HOME or pass --java with the Java executable path')
    if not a.runtime or not a.runtime.is_file():p.error('Pass --runtime with the desktop ONNX Runtime 1.20.0 JAR, or set SPLASHSKIP_DESKTOP_ONNX')
    if not (a.classes/'com/codex/splashskip/DesktopVisualExport.class').is_file():p.error('Run tools/check.ps1 first, or pass --classes')
    paths=sorted(a.folder.glob('frame-*.json'));jobs=[]
    (a.folder/'vision').mkdir(exist_ok=True)
    for path in paths:
        frame=json.loads(path.read_text(encoding='utf-8'))
        jobs.extend([str((path.parent/frame['image']).resolve()),str((path.parent/'vision'/path.name).resolve())])
    if not jobs:raise ValueError('No captured frames')
    subprocess.run([str(a.java),'-Dfile.encoding=UTF-8','-cp',str(a.classes)+os.pathsep+str(a.runtime),
        'com.codex.splashskip.DesktopVisualExport',str(a.assets),*jobs],check=True)
    count=0
    for path in paths:
        frame=merge(json.loads(path.read_text(encoding='utf-8')),json.loads((path.parent/'vision'/path.name).read_text(encoding='utf-8')))
        count+=len(frame['visual_nodes']);path.write_text(json.dumps(frame,ensure_ascii=False,indent=2),encoding='utf-8')
    render(a.folder);print(json.dumps(dict(frames=len(paths),visual_observations=count,native_edges_inferred=False)))


if __name__=='__main__':main()
