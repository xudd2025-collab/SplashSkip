"""Offline structural + 8x8 visual candidate ranking. Python stdlib only; no uploads.

Usage: python tools/train_joint_controls.py exported*.json --output joint-training
Only a model passing held-out APPLICATION validation is written as deployable weights.
"""
import argparse
import hashlib
import json
import math
import random
import re
from pathlib import Path

SIZE = 76

def load(paths):
    rows, seen = [], set()
    labels_by_id, conflicts = {}, set()
    rejected = {"unknown": 0, "invalid": 0, "duplicate": 0}
    for path in paths:
        if path.stat().st_size > 2 * 1024 * 1024:
            raise ValueError(f"oversized export: {path}")
        root = json.loads(path.read_text(encoding="utf-8"))
        if root.get("schema") != 1 or root.get("feature_count") != SIZE:
            raise ValueError(f"unsupported schema: {path}")
        for r in root.get("records", []):
            label = r.get("label")
            if label == "unknown" or r.get("attempted") is False:
                rejected["unknown"] += 1
                continue
            f = r.get("features", [])
            source = r.get("source")
            source_ok = source == "user" or label == "success" and source == "two_clear_frames" or label == "no_effect" and source == "two_present_frames"
            evidence=r.get('evidence',{})
            if source=='recording_review' and isinstance(evidence,dict):
                source_ok=all(re.fullmatch(r'[a-f0-9]{64}',str(evidence.get(k,''))) for k in ('video_sha256','trace_sha256')) and bool(evidence.get('notes'))
            if label not in ("success", "no_effect", "mistouch") or not source_ok or len(f) != SIZE or not all(type(v) in (int, float) and math.isfinite(v) and 0 <= v <= 1 for v in f) or not r.get("package"):
                rejected["invalid"] += 1
                continue
            # Re-exported rows are not new training observations. Conflicting corrections
            # to the same record are rejected and must be resolved by exporting current data.
            key = r.get("id")
            if not isinstance(key,str) or not key:
                rejected["invalid"] += 1
                continue
            if key in labels_by_id and labels_by_id[key] != label:
                conflicts.add(key)
            labels_by_id[key] = label
            signature = (r["package"], tuple(round(v, 5) for v in f), label)
            if key in seen or signature in seen:
                rejected["duplicate"] += 1
                continue
            seen.add(key); seen.add(signature)
            rows.append({**r, "y": int(label == "success"), "features": f})
    rejected["conflicting_labels"] = len(conflicts)
    return [r for r in rows if r["id"] not in conflicts], rejected

def probability(w, f):
    z = max(-30, min(30, sum(a*b for a, b in zip(w, f))))
    return 1 / (1 + math.exp(-z))

def fit(rows):
    w = [0.0] * SIZE
    counts = [sum(r["y"] == c for r in rows) for c in (0, 1)]
    for _ in range(500):
        gradient = [0.0] * SIZE
        for r in rows:
            weight = len(rows) / (2 * max(1, counts[r["y"]]))
            error = (probability(w, r["features"]) - r["y"]) * weight
            for i, value in enumerate(r["features"]):
                gradient[i] += error * value
        for i in range(SIZE):
            w[i] -= 0.15 * (gradient[i] / len(rows) + 0.01 * w[i])
    return w

def evaluate(w, rows):
    positives = [probability(w, r["features"]) for r in rows if r["y"]]
    negatives = [probability(w, r["features"]) for r in rows if not r["y"]]
    auc = sum((p > n) + .5*(p == n) for p in positives for n in negatives) / max(1, len(positives)*len(negatives))
    return {"auc": auc, "positive": len(positives), "negative": len(negatives)}

def train(paths, output):
    output.mkdir(parents=True, exist_ok=True)
    rows, rejected = load(paths)
    apps = sorted({r["package"] for r in rows})
    report = {"schema": 1, "feature_count": SIZE, "samples": len(rows), "applications": len(apps), "excluded": rejected, "deployed": False}
    # Adequate counterexamples are necessary: learning only successes is not classification.
    if len(rows) < 40 or len(apps) < 4 or min(sum(r["y"] == c for r in rows) for c in (0,1)) < 10:
        report["status"] = "waiting_for_labels"
        report["reason"] = "Need at least 40 labeled records, 4 applications and 10 examples of each outcome class."
    else:
        random.Random(623).shuffle(apps)
        test_apps = set(apps[:max(1, len(apps)//4)])
        train_rows = [r for r in rows if r["package"] not in test_apps]
        test_rows = [r for r in rows if r["package"] in test_apps]
        if any(not any(r["y"] == c for r in part) for part in (train_rows,test_rows) for c in (0,1)):
            report.update(status="waiting_for_grouped_labels", reason="Both outcomes must occur in training and held-out applications.")
        else:
            w = fit(train_rows)
            metrics = evaluate(w, test_rows)
            report.update(status="evaluated", train_applications=sorted(set(apps)-test_apps), test_applications=sorted(test_apps), held_out=metrics)
            # Keep the evaluated model, without refitting on the validation applications.
            if metrics["positive"] >= 5 and metrics["negative"] >= 5 and metrics["auc"] >= .8:
                payload = "schema=1\nfeatures=76\nvalidated=true\nweights=" + ",".join(f"{v:.9g}" for v in w) + "\n"
                model = output / "joint_control.properties"
                model.write_text(payload, encoding="ascii")
                report.update(deployed=True, model=str(model), sha256=hashlib.sha256(payload.encode("ascii")).hexdigest())
            else:
                report["reason"] = "Held-out sample counts or ranking AUC did not meet the promotion gate."
    # A rejected run must not leave a previous deployable model masquerading as this result.
    if not report["deployed"]:
        model = output / "joint_control.properties"
        if model.exists():
            model.replace(output / "joint_control.previous-unvalidated.properties")
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("exports", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(train(args.exports, args.output), ensure_ascii=False, indent=2))
