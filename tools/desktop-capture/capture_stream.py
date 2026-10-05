"""Fast desktop acquisition: temporary screenshot stream + ADB read-only parent/child bridge."""
from pathlib import Path
import argparse,collections,json,re,subprocess,threading,time
from capture import read_packet,REMOTE
from capture_adb import decode_reply,read_records
from inspect_capture import render

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',type=Path,required=True);p.add_argument('--serial',required=True)
    p.add_argument('--package',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--collector',type=Path,default=Path(__file__).parent/'.build/collector.jar')
    p.add_argument('--seconds',type=int,default=12);p.add_argument('--launch',action='store_true')
    p.add_argument('--resume',action='store_true',help='Bring the app to foreground without force-stopping, for warm launch ads')
    p.add_argument('--shallow',action='store_true',help='Use the shorter native tree budget instead of deep collection')
    p.add_argument('--tree-only',action='store_true',help='Collect current native bounds and parent/child nodes without screenshots or a collector')
    p.add_argument('--screens-only',action='store_true',help='Keep continuous screenshots without additional accessibility tree reads during automatic clicks')
    p.add_argument('--image-short-side',type=int,default=1600)
    p.add_argument('--keep-screens',action='store_true',help='Keep every screen with its timestamp to review fast click outcomes')
    args=p.parse_args()
    if args.tree_only and args.screens_only:p.error('Choose tree inspection or screenshots')
    if args.screens_only:args.keep_screens=True
    if args.launch and args.resume:p.error('Choose cold launch or warm resume')
    if not re.fullmatch(r'[A-Za-z][\w]*(?:\.[\w]+)+',args.package) or not 1<=args.seconds<=60:p.error('Invalid scope')
    if not 400<=args.image_short_side<=2048:p.error('Invalid screenshot size')
    out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    if args.keep_screens:(out/'screens').mkdir()
    adb=[str(args.adb.resolve()),'-s',args.serial]
    def run(*a):return subprocess.run(adb+list(a),capture_output=True,check=True,timeout=15).stdout
    def bridge(method):return decode_reply(run('shell','content','call','--uri','content://com.codex.splashskip.capture','--method',method,'--arg',args.package))
    tree_method='tree' if args.shallow else 'tree_deep'
    if args.screens_only:
        initial=decode_reply(run('shell','content','call','--uri','content://com.codex.splashskip.capture',
            '--method','status','--arg','com.codex.splashskip'))
        if not initial.get('service_running'):raise RuntimeError('Assistant accessibility is not connected')
    else:initial=bridge(tree_method)
    if initial['kind']=='service-unavailable':raise RuntimeError('Assistant accessibility is not connected')
    original=run('shell','settings','get','secure','enabled_accessibility_services').decode().strip()
    if args.tree_only:
        if args.launch or args.resume:p.error('Tree-only inspection reads the already-open page; omit launch/resume')
        frames=[];start=time.monotonic();meta=initial
        while time.monotonic()-start<args.seconds:
            if meta.get('kind')=='frame':
                name='frame-'+str(len(frames)).zfill(4)
                meta.update(file=name+'.json',screen_capture=False,transport='desktop-inspector-native-tree',image=None)
                (out/meta['file']).write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8');frames.append(meta)
                print(f"frame={len(frames)} nodes={len(meta['tree_nodes'])} complete={meta['tree_complete']} screenshots=0",flush=True)
            time.sleep(.2);meta=bridge(tree_method)
        current=run('shell','settings','get','secure','enabled_accessibility_services').decode().strip()
        summary={'package':args.package,'frame_count':len(frames),'screen_capture':False,'screenshot_requests':0,
            'clicks_from_collector':0,'accessibility_preserved':current==original,'tree_source':'desktop inspector over authorized native read bridge'}
        (out/'session.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');render(out,frames)
        if not frames:raise RuntimeError('No current native frame returned')
        print(json.dumps(summary,ensure_ascii=False),flush=True);return
    run('push',str(args.collector.resolve()),REMOTE)
    proc=subprocess.Popen(adb+['exec-out','env','CLASSPATH='+REMOTE,'app_process','/system/bin','com.codex.splashskip.capture.DeviceScreens',str(args.seconds+4),str(args.image_short_side)],stdout=subprocess.PIPE,stderr=(out/'stderr.txt').open('wb'))
    screens=collections.deque(maxlen=24);lock=threading.Lock();ready=threading.Event();done=threading.Event();errors=[];warnings=[];pid=[None]
    def reader():
        sequence=0
        try:
            while True:
                meta,data=read_packet(proc.stdout)
                if meta['kind']=='ready':pid[0]=meta.get('pid');ready.set()
                elif meta['kind']=='screen':
                    meta['sequence']=sequence;sequence+=1
                    if args.keep_screens:
                        name=f"screen-{meta['sequence']:04d}"
                        (out/'screens'/(name+'.jpg')).write_bytes(data)
                        (out/'screens'/(name+'.json')).write_text(json.dumps(meta),encoding='utf-8')
                    with lock:screens.append((meta,data))
                elif meta['kind']=='done':break
                elif meta['kind']=='error':errors.append(meta['error']);break
        except Exception as e:errors.append(str(e))
        finally:done.set()
    threading.Thread(target=reader,daemon=True).start();frames=[];used=set();start=time.monotonic();discarded=collections.Counter();last_pair={}
    try:
        if not ready.wait(8):raise RuntimeError('Screenshot stream unavailable: '+str(errors))
        if args.launch or args.resume:
            if args.launch:run('shell','am','force-stop',args.package)
            activity=run('shell','cmd','package','resolve-activity','--brief',args.package).decode().strip().splitlines()[-1]
            if not activity.startswith(args.package+'/'):raise RuntimeError('Unexpected launcher')
            launch=run('shell','am','start','-W','-n',activity)
            (out/'launch.txt').write_bytes(launch)
        start=time.monotonic()
        while time.monotonic()-start<args.seconds and not done.is_set():
            if args.screens_only:time.sleep(.1);continue
            meta=bridge(tree_method)
            if meta['kind']!='frame':discarded[meta['kind']]+=1;time.sleep(.1);continue
            # Wait at most one stream period for an image near the tree's capture interval.
            midpoint=(meta['started_uptime']+meta['tree_uptime'])/2
            for _ in range(3):
                with lock:available=list(screens)
                if available and available[-1][0]['screenshot_uptime']>=midpoint:break
                time.sleep(.06)
            available=[x for x in available if x[0]['sequence'] not in used and (x[0]['width'],x[0]['height'])==(meta['width'],meta['height'])]
            if not available:discarded['no-near-image']+=1;continue
            screen,image=min(available,key=lambda x:abs(x[0]['screenshot_uptime']-midpoint))
            skew=abs(screen['screenshot_uptime']-midpoint)
            last_pair={'tree_midpoint':midpoint,'screen_time':screen['screenshot_uptime'],'skew':skew}
            if skew>350:discarded['image-too-old']+=1;continue
            used.add(screen['sequence']);name='frame-'+str(len(frames)).zfill(4)
            meta.update(image=name+'.jpg',file=name+'.json',screenshot_uptime=screen['screenshot_uptime'],image_width=screen['image_width'],image_height=screen['image_height'],
                pair_skew_ms=round(skew,1),pair_duration_ms=round(max(meta['tree_uptime'],screen['screenshot_uptime'])-min(meta['started_uptime'],screen['screenshot_uptime'])),transport='desktop-stream-and-adb-tree',aligned=False)
            (out/meta['image']).write_bytes(image);(out/meta['file']).write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8');frames.append(meta)
            close=sum((n['text'] or n['description']) in ('关闭','跳过','关闭广告') for n in meta['tree_nodes'])
            print(f"frame={len(frames)} nodes={len(meta['tree_nodes'])} controls={close} complete={meta['tree_complete']} reasons={meta.get('truncation_reasons',[])} skew_ms={skew:.0f}",flush=True)
        try:
            (out/'joint-records.json').write_text(json.dumps(read_records(run,args.package),ensure_ascii=False,indent=2),encoding='utf-8')
        except Exception as error:
            warnings.append('Historical records were not exported: '+str(error))
        done.wait(6);proc.wait(timeout=4)
    finally:
        if proc.poll() is None:
            if isinstance(pid[0],int):
                command=run('exec-out','cat',f'/proc/{pid[0]}/cmdline')
                if b'com.codex.splashskip.capture.DeviceScreens' in command:run('shell','kill',str(pid[0]))
            proc.terminate()
        run('shell','rm','-f',REMOTE)
        current=run('shell','settings','get','secure','enabled_accessibility_services').decode().strip()
        summary={'package':args.package,'frame_count':len(frames),'errors':errors,'warnings':warnings,'capture_seconds':args.seconds,'discarded':dict(discarded),'last_pair':last_pair,
            'accessibility_preserved':current==original,'tree_source':'none; runtime traces are collected separately' if args.screens_only else 'authorized assistant accessibility service over ADB',
            'capture_mode':'screens-only' if args.screens_only else 'screens-and-tree',
            'screen_count':len(list((out/'screens').glob('screen-*.jpg'))) if args.keep_screens else len(screens),
            'image_source':'temporary shell screenshot stream, accessibility registration disabled','clicks_from_collector':0,
            'limitation':'screenshot/tree API timing differs; manually review before labels or training'}
        (out/'session.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');render(out,frames)
    if (not frames and not args.screens_only) or errors or args.screens_only and not summary['screen_count']:
        raise RuntimeError('Incomplete desktop capture: '+str(summary))
    print(json.dumps(summary,ensure_ascii=True),flush=True)
if __name__=='__main__':main()
