"""Export the shipped TFLite action classifier tensors for AdButtonModelCheck."""
from pathlib import Path
import argparse, hashlib, json, sys

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('model',type=Path)
p.add_argument('output',type=Path)
p.add_argument('--library-path',type=Path,help='Optional directory containing installed tflite/flatbuffers packages')
a=p.parse_args()
if a.library_path:sys.path.append(str(a.library_path))
import tflite
data=a.model.read_bytes()
model=tflite.Model.GetRootAsModel(data,0)
graph=model.Subgraphs(0)
hidden=graph.Tensors(1).Shape(0)
if hidden not in (32,64) or graph.Tensors(1).Shape(1)!=640 or graph.Tensors(4).Shape(0)!=3:
    raise ValueError('Expected the 640 -> 32/64 -> 3 action classifier')
parts=[model.Buffers(graph.Tensors(i).Buffer()).DataAsNumpy().tobytes() for i in (1,2,4,5)]
if [len(b) for b in parts]!=[hidden*640*4,hidden*4,3*hidden*4,3*4]:
    raise ValueError('Unexpected tensor buffers')
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_bytes(b''.join(parts))
record={'model_sha256':hashlib.sha256(data).hexdigest(),'weights_sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'tensor_indices':[1,2,4,5],'sizes':[len(b) for b in parts]}
a.output.with_suffix('.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
print(json.dumps(record))
