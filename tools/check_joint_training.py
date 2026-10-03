"""Contract tests use synthetic vectors, never produce deployable app training data."""
import json
import tempfile
from pathlib import Path
from train_joint_controls import load, train

def write(path, rows):
    path.write_text(json.dumps({"schema": 1, "feature_count": 76, "records": rows}), encoding="utf-8")

def row(i, label, pkg="test.app", source="user"):
    return {"id":str(i), "label":label, "source":source, "package":pkg, "features":[1.0]+[i/1000]*75}

def run():
    with tempfile.TemporaryDirectory(prefix="joint-contract-") as folder:
        p=Path(folder); a=p/"a.json"; b=p/"b.json"
        write(a,[row(1,"unknown"),row(2,"success",source="manual"),row(3,"no_effect")])
        rows,excluded=load([a]);assert len(rows)==1 and excluded["unknown"]==1 and excluded["invalid"]==1
        print("PASS unknown and unsupported automatic positives excluded")
        write(a,[row(4,"success")]);write(b,[row(4,"mistouch")])
        rows,excluded=load([a,b]);assert not rows and excluded["conflicting_labels"]==1
        print("PASS conflicting corrections excluded across exports")
        write(b,[row(4,"success")]);rows,excluded=load([a,b]);assert len(rows)==1
        print("PASS repeated export not counted twice")
        reviewed=row(5,"success",source="recording_review");write(b,[reviewed]);assert not load([b])[0]
        reviewed['evidence']={'video_sha256':'a'*64,'trace_sha256':'b'*64,'notes':'Explicit offline review'}
        write(b,[reviewed]);assert len(load([b])[0])==1
        print("PASS recording review requires linked evidence metadata")
        reviewed['attempted']=False;write(b,[reviewed]);assert not load([b])[0]
        print("PASS unattempted candidate cannot become a trained success")
        output=p/"out";output.mkdir();(output/"joint_control.properties").write_text("old")
        report=train([a],output);assert report["status"]=="waiting_for_labels" and not (output/"joint_control.properties").exists()
        print("PASS insufficient real labels cannot produce or leave active model")
        samples=[]
        for app in range(4):
            for i in range(20):
                r=row(app*20+i+10,"success" if i%2 else "no_effect",f"test.app{app}")
                r["features"][1]=1 if i%2 else 0;samples.append(r)
        write(a,samples);report=train([a],output)
        assert set(report["train_applications"]).isdisjoint(report["test_applications"])
        assert report["held_out"]["positive"]==10 and report["held_out"]["negative"]==10
        print("PASS application grouped validation has no cross-split application leak")
        assert report["deployed"] and "validated=true" in (output/"joint_control.properties").read_text()
        print("PASS separable synthetic contract data validates export format; discarded after test")

if __name__=="__main__":run()
