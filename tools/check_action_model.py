"""Check exported TFLite weights on externally saved production button crops."""
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
import tflite
import train_skip_model as base

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--model',type=Path,required=True)
parser.add_argument('--crops',type=Path,required=True)
args=parser.parse_args()
model=tflite.Model.GetRootAsModel(args.model.read_bytes(),0)
graph=model.Subgraphs(0)
def weights(index,shape):
    return np.frombuffer(model.Buffers(graph.Tensors(index).Buffer()).DataAsNumpy().tobytes(),dtype='<f4').reshape(shape)
hidden=graph.Tensors(1).Shape(0)
outputs=graph.Tensors(4).Shape(0)
params=[weights(1,(hidden,640)).T,weights(2,(1,hidden)),weights(4,(outputs,hidden)).T,weights(5,(1,outputs))]
failed=[]
for path in sorted(args.crops.glob('*.png')):
    image=Image.open(path).convert('L').resize((base.W,base.H),Image.Resampling.BILINEAR)
    index=int(path.name.split('_')[0])
    score=float(base.predict(base.vector(image),params).reshape(-1)[index])
    print(f'{path.name} probability={score:.4f}')
    if score<.85:failed.append(path.name)
if failed:raise AssertionError('Production candidates rejected: '+', '.join(failed))
if not list(args.crops.glob('*.png')):raise AssertionError('No crops')
print('PASS exported model accepts all production-located multi-resolution candidates')
