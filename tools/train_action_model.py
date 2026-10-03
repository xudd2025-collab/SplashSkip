"""Train a small TFLite button classifier on explicitly annotated local crops.

Button identity is not ad intent. Production must require full scene context.
Source screenshots stay outside the repository. See model_training.md.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import train_skip_model as base
from PIL import Image

base.HIDDEN=32

def fit(x, y, steps=1800):
    rng = np.random.default_rng(20261002)
    params = [rng.normal(0, .035, (base.W*base.H, base.HIDDEN)).astype('float32'),
              np.zeros((1, base.HIDDEN), 'float32'),
              rng.normal(0, .035, (base.HIDDEN, 3)).astype('float32'),
              np.zeros((1, 3), 'float32')]
    ms = [np.zeros_like(p) for p in params]
    vs = [np.zeros_like(p) for p in params]
    negative_indices=np.flatnonzero(y.sum(axis=1)==0)
    positive_indices=[np.flatnonzero(y[:,head]>0) for head in range(y.shape[1])]
    for step in range(1, steps+1):
        batch = np.concatenate([rng.choice(negative_indices,128),
                                *[rng.choice(group,43) for group in positive_indices]])
        bx, by = x[batch], y[batch]
        w1, b1, w2, b2 = params
        z = bx@w1+b1
        h = np.maximum(z, 0)
        p = 1/(1+np.exp(-np.clip(h@w2+b2, -30, 30)))
        dz = (p-by)/len(batch)
        dh = (dz@w2.T)*(z > 0)
        gradients = [bx.T@dh+.0003*w1, dh.sum(axis=0, keepdims=True),
                     h.T@dz+.0003*w2, dz.sum(axis=0, keepdims=True)]
        for value, gradient, m, v in zip(params, gradients, ms, vs):
            m[:] = .9*m+.1*gradient
            v[:] = .999*v+.001*gradient*gradient
            value -= .002*(m/(1-.9**step))/(np.sqrt(v/(1-.999**step))+1e-8)
    return params


def data(manifest,rows):
    rng=np.random.default_rng(20260930)
    xs,ys,heads,validation=[],[],[],[]
    classes={'skip':0,'close':1,'cross':2}
    for row in rows:
        with Image.open(manifest.parent/row['file']) as opened:
            image=opened.convert('L')
        box=tuple(int(v) for v in row['box'])
        if not (0<=box[0]<box[2]<=image.width and 0<=box[1]<box[3]<=image.height):raise ValueError('Invalid box')
        crop=base.patch(image,box);index=classes[row['action']];label=int(row['label'])
        target=np.zeros(3,'float32');target[index]=label
        if row.get('split','train')=='validation':validation.append((base.vector(crop),label,index))
        else:
            # Keep the unshifted crop alongside augmentations; edge-filled shifts alone lose real detail.
            originals=4 if label else 1
            xs.extend([base.vector(crop)]*originals);ys.extend([target]*originals);heads.extend([index]*originals)
            xs.extend(base.augment(crop,rng,22));ys.extend([target]*22);heads.extend([index]*22)
    return np.asarray(xs,'float32'),np.asarray(ys,'float32'),np.asarray(heads),validation


def check_groups(rows):
    groups = {}
    for row in rows:
        source = row['source']
        split = row.get('split', 'train')
        if source in groups and groups[source] != split:
            raise ValueError('Source appears in both training and validation: '+source)
        groups[source] = split
    if set(groups.values()) != {'train', 'validation'}:
        raise ValueError('Separate training and validation screenshot sources are required')
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--hidden', type=int, default=32, choices=[32,64])
    parser.add_argument('--steps', type=int, default=1800)
    args = parser.parse_args()
    if args.steps<1:parser.error('--steps must be positive')
    base.HIDDEN=args.hidden
    rows = json.loads(args.dataset.read_text(encoding='utf-8'))
    groups = check_groups(rows)
    x, y, train_heads, validation = data(args.dataset.resolve(),rows)
    params = fit(x, y,args.steps)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Intermediate weights are for reviewing failed checks, never loaded by the APK.
    np.savez(args.output.with_suffix('.weights.npz'), *params)
    report = {'input': [1, base.W*base.H], 'hidden': base.HIDDEN, 'classes':['skip','close','cross'], 'threshold': .85,
              'seed': 20261002, 'steps': args.steps, 'sources': groups,
              'limitation': 'Held-out sources are few. Button identity alone does not establish an ad.'}
    for name, samples, labels,heads in [('train',x,(y.sum(axis=1)>0).astype(int),train_heads),
             ('validation', np.asarray([v[0] for v in validation]), np.asarray([v[1] for v in validation]),np.asarray([v[2] for v in validation]))]:
        all_values=base.predict(samples, params)
        values=all_values[np.arange(len(samples)),heads]
        statistics = {}
        for label, label_name in [(1, 'positive'), (0, 'negative')]:
            scores = values[labels == label]
            if not len(scores):
                raise ValueError(name+' requires both labels')
            statistics[label_name] = {'count': len(scores), 'min': float(scores.min()), 'max': float(scores.max())}
        statistics['false_positives'] = int(np.sum((labels == 0) & (values >= .85)))
        statistics['false_negatives'] = int(np.sum((labels == 1) & (values < .85)))
        report[name] = statistics
        print(name, json.dumps(statistics))
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    if report['validation']['false_positives'] or report['validation']['false_negatives']:
        raise RuntimeError('Held-out candidate checks failed; model was not exported')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    base.export(params, args.output, 'Computer-trained ad button identity classifier; full scene context required')
    report['sha256'] = hashlib.sha256(args.output.read_bytes()).hexdigest()
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Exported', args.output, report['sha256'])


if __name__ == '__main__':
    main()
