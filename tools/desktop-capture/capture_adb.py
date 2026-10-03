"""Desktop capture through the assistant's ADB-only read bridge; no phone UI export."""
from pathlib import Path
import argparse,base64,concurrent.futures,gzip,io,json,re,struct,subprocess,time
from inspect_capture import render

def decode_reply(raw):
    text=raw.decode('utf-8',errors='replace')
    found=re.search(r'\bdata=([A-Za-z0-9+/=]+)',text)
    if not found:raise RuntimeError(text[:500])
    data=base64.b64decode(found[1],validate=True)
    if re.search(r'\bencoding=gzip\b',text):
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:data=stream.read(2*1024*1024+1)
    if len(data)>2*1024*1024:raise ValueError('Oversize diagnostic JSON')
    return json.loads(data)

def read_records(run,package):
    records=[];seen=set();offset=0
    while offset>=0:
        if offset in seen:raise RuntimeError('Invalid repeated records cursor')
        seen.add(offset)
        page=decode_reply(run('shell','content','call','--uri','content://com.codex.splashskip.capture',
            '--method','records','--arg',package,'--extra','offset:i:'+str(offset)))
        records.extend(page['records']);offset=page.get('next_offset',-1)
        if len(seen)>257:raise RuntimeError('Too many record pages')
    return dict(schema=1,feature_count=76,records=records,pages=len(seen))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',type=Path,required=True);p.add_argument('--serial',required=True)
    p.add_argument('--package',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--seconds',type=int,default=12);p.add_argument('--launch',action='store_true')
    args=p.parse_args()
    if not re.fullmatch(r'[A-Za-z][\w]*(?:\.[\w]+)+',args.package) or not 1<=args.seconds<=300:p.error('Invalid scope')
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    adb=[str(args.adb.resolve()),'-s',args.serial]
    def run(*a):return subprocess.run(adb+list(a),capture_output=True,check=True,timeout=12).stdout
    def tree():
        start=time.time();meta=decode_reply(run('shell','content','call','--uri','content://com.codex.splashskip.capture','--method','tree','--arg',args.package))
        meta['host_tree_request']=start;meta['host_tree_return']=time.time();return meta
    def screenshot():
        start=time.time();data=run('exec-out','screencap','-p');return data,start,time.time()
    # Check the bridge before touching the requested app.
    initial=tree()
    if initial['kind']=='service-unavailable':raise RuntimeError('Accessibility service is not connected')
    if args.launch:
        run('shell','am','force-stop',args.package)
        activity=run('shell','cmd','package','resolve-activity','--brief',args.package).decode().strip().splitlines()[-1]
        if not activity.startswith(args.package+'/'):raise RuntimeError('Unexpected launcher')
        run('shell','am','start','-n',activity)
    frames=[];waiting=0;start=time.monotonic()
    original=run('shell','settings','get','secure','enabled_accessibility_services').decode().strip()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            while time.monotonic()-start<args.seconds:
                # Discard the screenshot if the target app does not own the tree, or if
                # focus changed. Pair timing remains explicit; these APIs are not atomic.
                ft=workers.submit(tree);fp=workers.submit(screenshot)
                meta=ft.result();image,request,returned=fp.result()
                if meta['kind']!='frame':waiting+=1;time.sleep(.15);continue
                focus=run('shell','dumpsys','window').decode('utf-8',errors='replace')
                if not any('mCurrentFocus=' in s and args.package+'/' in s for s in focus.splitlines()):waiting+=1;continue
                if not image.startswith(b'\x89PNG\r\n\x1a\n'):raise RuntimeError('Invalid screenshot')
                w,h=struct.unpack('>II',image[16:24])
                if (w,h)!=(meta['width'],meta['height']):waiting+=1;continue
                name='frame-'+str(len(frames)).zfill(4)
                meta.update(image=name+'.png',file=name+'.json',host_screenshot_request=request,host_screenshot_return=returned,
                    pair_duration_ms=round(1000*(max(returned,meta['host_tree_return'])-min(request,meta['host_tree_request']))),
                    transport='authorized-adb-read-bridge',aligned=False)
                (out/meta['image']).write_bytes(image);(out/meta['file']).write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8');frames.append(meta)
                close=[n for n in meta['tree_nodes'] if (n['text'] or n['description']) in ('关闭','跳过','关闭广告')]
                print(f"frame={len(frames)} nodes={len(meta['tree_nodes'])} controls={len(close)} complete={meta['tree_complete']} pair_ms={meta['pair_duration_ms']}",flush=True)
        records=read_records(run,args.package)
        (out/'joint-records.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
    finally:
        current=run('shell','settings','get','secure','enabled_accessibility_services').decode().strip()
        summary={'package':args.package,'frame_count':len(frames),'discarded_or_waiting':waiting,'elapsed_seconds':round(time.monotonic()-start,2),
            'accessibility_before':original,'accessibility_after':current,'accessibility_preserved':original==current,
            'limitations':'separate screenshot/tree APIs; review alignment before training; no screenshot uploads'}
        (out/'session.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');render(out,frames)
    if not frames:raise RuntimeError('No target app frames captured')
    print(json.dumps(summary,ensure_ascii=True),flush=True)

if __name__=='__main__':main()
