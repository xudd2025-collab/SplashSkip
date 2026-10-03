"""Capture Android screenshots and parent/child trees straight to a desktop directory.

Uses a temporary read-only UiAutomation shell bridge, preserving existing accessibility.
No app UI export, root, Internet or persistent device installation is involved.
"""
from pathlib import Path
import argparse,json,queue,re,struct,subprocess,threading,time

MAGIC=0x53534350
REMOTE='/data/local/tmp/splashskip-desktop-collector.jar'

def exact(stream,n):
    chunks=[]
    while n:
        b=stream.read(n)
        if not b:raise EOFError('Collector stream ended')
        chunks.append(b);n-=len(b)
    return b''.join(chunks)

def read_packet(stream):
    # Some GPU drivers print a bounded initialization message to shell stdout.
    # Resynchronize only at a packet boundary, never within an image payload.
    prefix=exact(stream,4);skipped=0
    while prefix!=b'SSCP':
        if skipped>=4096:raise ValueError('Collector header not found')
        prefix=prefix[1:]+exact(stream,1);skipped+=1
    length=struct.unpack('>I',exact(stream,4))[0]
    if not 1<=length<=4*1024*1024:raise ValueError('Invalid packet size')
    meta=json.loads(exact(stream,length))
    size=struct.unpack('>I',exact(stream,4))[0]
    if size>24*1024*1024:raise ValueError('Oversize frame')
    image=exact(stream,size)
    if image and not (image.startswith(b'\x89PNG\r\n\x1a\n') or image.startswith(b'\xff\xd8')):raise ValueError('Not an image frame')
    return meta,image

def collect(args):
    dest=args.output.resolve();dest.mkdir(parents=True,exist_ok=False)
    adb=[str(args.adb.resolve()),'-s',args.serial]
    def run(*command):return subprocess.run(adb+list(command),capture_output=True,check=True,timeout=20)
    original=run('shell','settings','get','secure','enabled_accessibility_services').stdout.decode().strip()
    run('push',str(args.collector.resolve()),REMOTE)
    if args.launch:run('shell','am','force-stop',args.package)
    proc=None;pid=None;frames=[];errors=[];ready=None;complete=False
    try:
        proc=subprocess.Popen(adb+['exec-out','env','CLASSPATH='+REMOTE,'app_process','/system/bin',
            'com.codex.splashskip.capture.DeviceCollector',args.package,str(args.seconds),str(args.interval)],
            stdout=subprocess.PIPE,stderr=(dest/'collector-stderr.txt').open('wb'))
        events=queue.Queue(maxsize=4)
        def reader():
            try:
                while True:
                    item=read_packet(proc.stdout);events.put(item)
                    if item[0].get('kind') in ('done','error'):break
            except Exception as error:events.put(({'kind':'reader-error','error':str(error)},b''))
        threading.Thread(target=reader,daemon=True).start()
        until=time.monotonic()+args.seconds+30
        while time.monotonic()<until:
            meta,image=events.get(timeout=15);kind=meta.get('kind')
            if kind=='initializing':pid=meta.get('pid');print('Collector process',pid,flush=True)
            elif kind=='connected':print('UiAutomation connected',flush=True)
            elif kind=='ready':
                ready=meta;pid=meta.get('pid')
                if args.launch:
                    activity=run('shell','cmd','package','resolve-activity','--brief',args.package).stdout.decode().strip().splitlines()[-1]
                    if not activity.startswith(args.package+'/'):raise RuntimeError('Cannot resolve requested app')
                    run('shell','am','start','-n',activity)
                print('Desktop collector connected; existing accessibility preserved',flush=True)
            elif kind=='frame':
                name='frame-'+str(len(frames)).zfill(4)
                meta['image']=name+'.png';meta['file']=name+'.json';meta['received_time']=time.time()
                (dest/meta['image']).write_bytes(image)
                (dest/meta['file']).write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
                frames.append(meta)
                labels=[n['text'] or n['description'] for n in meta['tree_nodes'] if (n['text'] or n['description']) in ('关闭','跳过','关闭广告')]
                print(f"frame={len(frames)} nodes={len(meta['tree_nodes'])} complete={meta['tree_complete']} controls={len(labels)} tree_ms={meta['tree_uptime']-meta['screenshot_uptime']}",flush=True)
            elif kind=='done':complete=True;break
            elif kind in ('error','reader-error'):errors.append(meta);break
        if proc.poll() is None:proc.wait(timeout=8)
    finally:
        if proc is not None and proc.poll() is None:
            if isinstance(pid,int):
                command=run('exec-out','cat',f'/proc/{pid}/cmdline').stdout
                if b'com.codex.splashskip.capture.DeviceCollector' in command:run('shell','kill',str(pid))
            proc.terminate()
        current=run('shell','settings','get','secure','enabled_accessibility_services').stdout.decode().strip()
        run('shell','rm','-f',REMOTE)
        summary={'package':args.package,'complete':complete,'frame_count':len(frames),'ready':ready,'errors':errors,
            'accessibility_before':original,'accessibility_after':current,'accessibility_preserved':original==current,
            'scope':'read-only desktop capture; screenshot and tree are sequential, not atomic'}
        (dest/'session.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
        from inspect_capture import render
        render(dest,frames)
    if errors or not complete:raise RuntimeError('Capture did not complete: '+str(errors))
    print(json.dumps(summary,ensure_ascii=True),flush=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',type=Path,required=True);p.add_argument('--serial',required=True)
    p.add_argument('--collector',type=Path,default=Path(__file__).parent/'.build/collector.jar')
    p.add_argument('--package',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--seconds',type=int,default=12);p.add_argument('--interval',type=int,default=200)
    p.add_argument('--launch',action='store_true',help='Cold-start only the requested app after connecting')
    args=p.parse_args()
    if not re.fullmatch(r'[a-zA-Z][\w]*(?:\.[\w]+)+',args.package):p.error('Invalid package')
    if not 1<=args.seconds<=300 or not 120<=args.interval<=5000:p.error('Capture bounds invalid')
    collect(args)

if __name__=='__main__':main()
