"""Train/export the project's 640 -> 16 -> 1 skip-text classifier.

Dataset paths are explicit. No Downloads scans or personal screenshots are bundled.
See model_training.md. Original code: Apache-2.0.
"""
from pathlib import Path
import argparse
import json
import flatbuffers
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter
import tflite

W, H, HIDDEN = 32, 20, 16

def patch(image, box):
    return image.crop(box).resize((W,H), Image.Resampling.BILINEAR)

def vector(image):
    a=np.asarray(image,dtype=np.float32)
    return np.clip((a-a.mean())/(a.std()+20.0),-3,3).reshape(-1)

def augment(image, rng, count):
    for _ in range(count):
        dx=int(rng.integers(-2,3)); dy=int(rng.integers(-2,3))
        canvas=Image.new('L',(W,H),int(np.asarray(image).mean()))
        canvas.paste(image,(dx,dy))
        if rng.random()<.4: canvas=canvas.filter(ImageFilter.GaussianBlur(float(rng.uniform(0,.7))))
        canvas=ImageEnhance.Contrast(canvas).enhance(float(rng.uniform(.75,1.35)))
        yield vector(canvas)

def data(manifest):
    rows = json.loads(manifest.read_text(encoding='utf-8'))
    rng = np.random.default_rng(20260930)
    xs, ys, validation = [], [], []
    if not isinstance(rows, list):
        raise ValueError('Dataset must be a JSON array')
    for row in rows:
        label = int(row['label'])
        if label not in (0, 1):
            raise ValueError('label must be 0 or 1')
        path = manifest.parent / row['file']
        with Image.open(path) as opened:
            image = opened.convert('L')
        box = tuple(int(value) for value in row['box'])
        if len(box) != 4 or not (0 <= box[0] < box[2] <= image.width and 0 <= box[1] < box[3] <= image.height):
            raise ValueError('box must be inside the source image')
        crop = patch(image, box)
        split = row.get('split', 'train')
        if split == 'validation':
            validation.append((vector(crop), label))
        elif split == 'train':
            xs.extend(augment(crop, rng, 22))
            ys.extend([float(label)] * 22)
        else:
            raise ValueError('split must be train or validation')
    if not xs or set(ys) != {0.0, 1.0}:
        raise ValueError('Training requires positive and negative samples')
    return np.asarray(xs, dtype=np.float32), np.asarray(ys, dtype=np.float32)[:, None], validation

def train(x,y):
    rng=np.random.default_rng(812)
    w1=rng.normal(0,.035,(x.shape[1],HIDDEN)).astype(np.float32)
    b1=np.zeros((1,HIDDEN),np.float32)
    w2=rng.normal(0,.035,(HIDDEN,1)).astype(np.float32)
    b2=np.zeros((1,1),np.float32)
    params=[w1,b1,w2,b2]
    ms=[np.zeros_like(p) for p in params]
    vs=[np.zeros_like(p) for p in params]
    for step in range(1,1201):
        z1=x@w1+b1
        h=np.maximum(z1,0)
        z2=h@w2+b2
        pred=1/(1+np.exp(-np.clip(z2,-30,30)))
        weights=np.where(y>0, (len(y)-y.sum())/max(1.0,y.sum()), 1.0)
        dz=(pred-y)*weights/weights.sum()
        dw2=h.T@dz+.0003*w2
        db2=dz.sum(axis=0,keepdims=True)
        dh=dz@w2.T
        dz1=dh*(z1>0)
        dw1=x.T@dz1+.0003*w1
        db1=dz1.sum(axis=0,keepdims=True)
        for p,g,m,v in zip(params,[dw1,db1,dw2,db2],ms,vs):
            m[:]=.9*m+.1*g
            v[:]=.999*v+.001*g*g
            p-=.002*(m/(1-.9**step))/(np.sqrt(v/(1-.999**step))+1e-8)
    return params

def predict(x,params):
    w1,b1,w2,b2=params
    h=np.maximum(x@w1+b1,0)
    return 1/(1+np.exp(-np.clip(h@w2+b2,-30,30)))

def iv(b,start,items):
    start(b,len(items))
    for item in reversed(items): b.PrependInt32(int(item))
    return b.EndVector()
def ov(b,start,items):
    start(b,len(items))
    for item in reversed(items): b.PrependUOffsetTRelative(item)
    return b.EndVector()
def tensor(b,name,shape,buffer):
    s=iv(b,tflite.TensorStartShapeVector,shape)
    n=b.CreateString(name)
    tflite.TensorStart(b);tflite.TensorAddShape(b,s)
    tflite.TensorAddType(b,tflite.TensorType.FLOAT32)
    tflite.TensorAddBuffer(b,buffer);tflite.TensorAddName(b,n)
    return tflite.TensorEnd(b)
def buffer(b,values=None):
    data=b.CreateByteVector(np.asarray(values,dtype='<f4').tobytes()) if values is not None else None
    tflite.BufferStart(b)
    if data is not None: tflite.BufferAddData(b,data)
    return tflite.BufferEnd(b)
def operator(b,opcode,inputs,outputs,activation=None):
    ins=iv(b,tflite.OperatorStartInputsVector,inputs)
    outs=iv(b,tflite.OperatorStartOutputsVector,outputs)
    opt=None
    if activation is not None:
        tflite.FullyConnectedOptionsStart(b)
        tflite.FullyConnectedOptionsAddFusedActivationFunction(b,activation)
        opt=tflite.FullyConnectedOptionsEnd(b)
    tflite.OperatorStart(b); tflite.OperatorAddOpcodeIndex(b,opcode)
    tflite.OperatorAddInputs(b,ins);tflite.OperatorAddOutputs(b,outs)
    if opt is not None:
        tflite.OperatorAddBuiltinOptionsType(b,tflite.BuiltinOptions.FullyConnectedOptions)
        tflite.OperatorAddBuiltinOptions(b,opt)
    return tflite.OperatorEnd(b)

def export(params,path):
    w1,b1,w2,b2=params
    b=flatbuffers.Builder(80000)
    # TFLite fully connected weights have shape [out, in].
    bufs=[buffer(b),buffer(b,w1.T),buffer(b,b1),buffer(b,w2.T),buffer(b,b2)]
    ts=[tensor(b,'input',[1,W*H],0),tensor(b,'w1',[HIDDEN,W*H],1),
        tensor(b,'b1',[HIDDEN],2),tensor(b,'hidden',[1,HIDDEN],0),
        tensor(b,'w2',[1,HIDDEN],3),tensor(b,'b2',[1],4),
        tensor(b,'logit',[1,1],0),tensor(b,'probability',[1,1],0)]
    ops=[operator(b,0,[0,1,2],[3],tflite.ActivationFunctionType.RELU),
         operator(b,0,[3,4,5],[6],tflite.ActivationFunctionType.NONE),
         operator(b,1,[6],[7])]
    tv=ov(b,tflite.SubGraphStartTensorsVector,ts)
    oi=iv(b,tflite.SubGraphStartInputsVector,[0])
    oo=iv(b,tflite.SubGraphStartOutputsVector,[7])
    opv=ov(b,tflite.SubGraphStartOperatorsVector,ops)
    n=b.CreateString('skip_text_classifier')
    tflite.SubGraphStart(b);tflite.SubGraphAddTensors(b,tv)
    tflite.SubGraphAddInputs(b,oi);tflite.SubGraphAddOutputs(b,oo)
    tflite.SubGraphAddOperators(b,opv);tflite.SubGraphAddName(b,n)
    graph=tflite.SubGraphEnd(b)
    codes=[]
    for code in [tflite.BuiltinOperator.FULLY_CONNECTED,tflite.BuiltinOperator.LOGISTIC]:
        tflite.OperatorCodeStart(b);tflite.OperatorCodeAddBuiltinCode(b,code)
        tflite.OperatorCodeAddVersion(b,1);codes.append(tflite.OperatorCodeEnd(b))
    cvs=ov(b,tflite.ModelStartOperatorCodesVector,codes)
    graphs=ov(b,tflite.ModelStartSubgraphsVector,[graph])
    bvs=ov(b,tflite.ModelStartBuffersVector,bufs)
    desc=b.CreateString('Computer-trained skip text classifier')
    tflite.ModelStart(b);tflite.ModelAddVersion(b,3)
    tflite.ModelAddOperatorCodes(b,cvs);tflite.ModelAddSubgraphs(b,graphs)
    tflite.ModelAddBuffers(b,bvs);tflite.ModelAddDescription(b,desc)
    model=tflite.ModelEnd(b);b.Finish(model,file_identifier=b'TFL3')
    path.write_bytes(b.Output())

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    x, y, validation = data(args.dataset.resolve())
    model = train(x, y)
    probabilities = predict(x, model).reshape(-1)
    positive = probabilities[y.reshape(-1) == 1]
    negative = probabilities[y.reshape(-1) == 0]
    print(f'train positive={len(positive)} negative={len(negative)}')
    print(f'train positive min={positive.min():.3f} negative max={negative.max():.3f}')
    if validation:
        values = predict(np.asarray([item[0] for item in validation], dtype=np.float32), model).reshape(-1)
        labels = np.asarray([item[1] for item in validation])
        for label, name in [(1, 'positive'), (0, 'negative')]:
            scores = values[labels == label]
            if len(scores):
                print(f'validation {name} count={len(scores)} min={scores.min():.3f} max={scores.max():.3f}')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    export(model, args.output)
    print(f'Exported {args.output}')
