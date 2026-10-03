"""Generate an offline Chinese inspector. Raw UI content is escaped, never interpreted."""
import json
from pathlib import Path
def render(folder,frames=None):
    folder=Path(folder)
    if frames is None:frames=[json.loads(p.read_text(encoding='utf-8')) for p in sorted(folder.glob('frame-*.json'))]
    template=(Path(__file__).parent/'inspector.html').read_text(encoding='utf-8')
    data=json.dumps(frames,ensure_ascii=False).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026')
    (folder/'inspect.html').write_text(template.replace('/*CAPTURE_DATA*/[]',data),encoding='utf-8')
if __name__=='__main__':
    import sys
    render(sys.argv[1])
