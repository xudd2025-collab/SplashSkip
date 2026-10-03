"""Train candidate identity from reviewed desktop trees + local button pixels.

This is NOT click-outcome training. A reviewed manifest must name each target node,
matching frame and layout family. Repeated frames of a layout never cross folds.
No absolute coordinates, app identifiers, resource ids or readable labels are inputs.
"""
import argparse, hashlib, json, subprocess, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from train_joint_controls import fit, probability, evaluate


def train(args):
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    rows, evidence = [], []
    for entry in manifest['frames']:
        if entry.get('review') != 'visual_tree_match':
            raise ValueError('Each frame needs explicit visual/tree review')
        path = (args.manifest.parent / entry['path']).resolve()
        frame = json.loads(path.read_text(encoding='utf-8'))
        if not frame.get('tree_complete'):
            raise ValueError('Use complete trees for this training experiment')
        nodes = frame['tree_nodes']
        image = path.parent / frame['image']
        positives = set(entry['positive_nodes'])
        if not positives or not all(0 <= i < len(nodes) for i in positives):
            raise ValueError('Missing target review')
        stem = path.parent.name + '-' + path.stem
        tsv, feature_path = out / (stem + '.tsv'), out / (stem + '-features.json')
        # Empty roles prevent labels from leaking into the feature extractor.
        tsv.write_text('\n'.join('\t'.join(map(str, [n['parent'], n['child_count'],
            str(n['clickable'] and n['visible'] and n['enabled']).lower(), '', hashlib.sha256((n['class']+'/'+n['id']).encode()).hexdigest(),
            *n['bounds']])) for n in nodes), encoding='utf-8')
        subprocess.run([str(args.java), '-cp', str(args.classes),
            'com.codex.splashskip.CapturedTreeFeatures', str(tsv), str(image), str(feature_path),
            str(frame['width']), str(frame['height'])], check=True)
        features = json.loads(feature_path.read_text(encoding='utf-8'))
        found = set()
        for r in features:
            i = r['index']
            if not nodes[i]['visible'] or not nodes[i]['enabled']:
                continue
            f = r['features']
            f[10] = f[11] = 0  # Also masked at inference; do not learn text semantics.
            found.add(i)
            rows.append(dict(frame=stem, layout=entry['layout'], package=frame['package'],
                node=i, y=int(i in positives), features=f))
        if not positives <= found:
            raise ValueError('A target was not a visible small current control')
        evidence.append(dict(frame=stem, layout=entry['layout'], positive_nodes=sorted(positives),
            tree_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            image_sha256=hashlib.sha256(image.read_bytes()).hexdigest(),
            source='manual_visual_tree_review', objective='control_candidate'))

    layouts = sorted({r['layout'] for r in rows})
    folds = []
    for holdout in layouts:
        training = [r for r in rows if r['layout'] != holdout]
        test = [r for r in rows if r['layout'] == holdout]
        if not training or any(not any(r['y'] == c for r in part) for part in (training, test) for c in (0, 1)):
            continue
        weights = fit(training)
        metrics = evaluate(weights, test)
        frame_ranks = []
        for frame_id in sorted({r['frame'] for r in test}):
            fr = [r for r in test if r['frame'] == frame_id]
            scores = [(probability(weights, r['features']), r['y']) for r in fr]
            best_positive = max(s for s, y in scores if y)
            rank = 1 + sum(s >= best_positive for s, y in scores if not y)
            frame_ranks.append(dict(frame=frame_id, first_target_rank=rank))
        # Test removing all structural inputs; useful to detect a purely visual fit.
        visual_training = [{**r, 'features': [r['features'][0]]+[0]*11+r['features'][12:]} for r in training]
        visual_test = [{**r, 'features': [r['features'][0]]+[0]*11+r['features'][12:]} for r in test]
        visual_metrics = evaluate(fit(visual_training), visual_test)
        folds.append(dict(held_out_layout=holdout, metrics=metrics, frames=frame_ranks,
            visual_only_auc=visual_metrics['auc'], weights=weights))
    passing = len(layouts) >= 2 and len(folds) == len(layouts) and all(
        f['metrics']['auc'] >= .90 and all(r['first_target_rank'] <= 3 for r in f['frames']) for f in folds)
    # Keep one evaluated model. Do not refit on the held-out family after reporting it.
    model = out / 'tree_candidate_rank.properties'
    if passing:
        chosen = folds[-1]
        payload = 'schema=1\nfeatures=76\nvalidated=true\nobjective=control_candidate\n'
        payload += 'scope=' + ','.join(sorted({r['package'] for r in rows})) + '\n'
        payload += 'masked=10,11\nweights=' + ','.join(f'{v:.9g}' for v in chosen['weights']) + '\n'
        model.write_text(payload, encoding='ascii')
    elif model.exists():
        model.replace(out / 'tree_candidate_rank.unvalidated.properties')
    for f in folds:
        del f['weights']
    report = dict(objective='control_candidate', frames=len(evidence), layouts=len(layouts),
        samples=len(rows), positives=sum(r['y'] for r in rows), folds=folds,
        ranking_gate_passed=passing, deployed=False,
        limits='One application, two layout families. Repeated frames are correlated. No click success labels; no cross-application or latency guarantee.',
        excluded_inputs=['absolute_position','resource_id','text','app_id','semantic_role'],
        model_sha256=hashlib.sha256(model.read_bytes()).hexdigest() if passing else None)
    (out/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (out/'dataset.json').write_text(json.dumps(dict(evidence=evidence, rows=rows)), encoding='utf-8')
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--classes', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    print(json.dumps(train(p.parse_args()), ensure_ascii=False, indent=2))
