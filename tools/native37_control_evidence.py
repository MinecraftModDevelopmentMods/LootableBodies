"""Classify the deliberate mod-free control separately from ordinary clean logs."""
import json,re
from pathlib import Path
from audit_runtime_logs import audit
from native_runtime import digest

def normalized_stack(text):
    # The module-loader enumeration is an incidental JVM identity. Every
    # exception, frame, native line, jar name and thread line remains exact.
    return re.sub(r'%23[0-9]+!',r'%23MODULE!',text.strip())

def residual(text):
    expected=(Path(__file__).parent/'fixtures/native37-delayed-movement-stack.txt').read_text()
    lines=text.splitlines();out=[];count=0;i=0
    while i<len(lines):
        if re.search(r'\[Server thread/FATAL\].*\]: Error executing task on Server$',lines[i]):
            end=i+1
            while end<len(lines) and not lines[end].startswith('['):end+=1
            stack='\n'.join(lines[i+1:end]).strip()
            if normalized_stack(stack)!=normalized_stack(expected):raise ValueError('Unclassified native37 control stack')
            count+=1;i=end
        else:out.append(lines[i]);i+=1
    if count not in (0,8):raise ValueError('Incomplete native37 delayed-packet baseline')
    return '\n'.join(out)+'\n',count

def classify(root,probe,exit_code):
    root=Path(root);probe=Path(probe);output=root/'classification';output.mkdir(exist_ok=False)
    client=(root/'client.log').read_text(errors='replace');server=(root/'server.log').read_text(errors='replace')
    batches=[int(x) for x in re.findall(r'NATIVE_FIFO_DELAY_RELEASE packets=([0-9]+)',client)]
    if client.count('NATIVE_RESPAWN ')!=8 or server.count('NATIVE_DEATH ')!=8 or len(batches)!=8 or sum(batches)<=0 or 'FORGE_PLATFORM_CONTROL_PASS' not in client:
        raise ValueError('Native37 control did not complete all actual actions')
    records=[]
    for side in ('server','client'):
        receipt_path=root/side/(side+'.diagnostics/result.json')
        receipt=json.loads(receipt_path.read_text())
        if receipt['timed_out'] or receipt['returncode']!=0 or receipt['timeout']!=(420 if side=='server' else 240):raise ValueError('Native37 control process failed or timed out')
        for name,sha in receipt['evidence'].items():
            if Path(name).name!=name or digest(receipt_path.parent/name)!=sha:raise ValueError('Changed native37 control diagnostic evidence')
        mods=list((root/side/'mods').glob('*.jar'))
        if len(mods)!=1 or mods[0].name!=probe.name or digest(mods[0])!=digest(probe):raise ValueError('Control is not mod-free')
        if list((root/side).glob('crash-reports/*')):raise ValueError('Native37 control crash')
        for number,path in enumerate((root/(side+'.log'),root/side/'logs/latest.log',root/side/'logs/debug.log')):
            text=path.read_text(errors='replace');clean,count=residual(text)
            if side=='client' and count:raise ValueError('Unexpected client control failure')
            if count:
                try:audit(path,native=True)
                except RuntimeError:pass
                else:raise ValueError('Strict audit unexpectedly accepted deliberate native failure')
                view=output/(side+'-'+str(number)+'-residual.txt');view.write_text(clean)
                audit(view,native=True)
            else:audit(path,native=True)
            records.append({'path':str(path),'sha256':digest(path),'strict_clean_audit_passed':count==0,'native37_baseline_exceptions':count})
    counts=[r['native37_baseline_exceptions'] for r in records[:3]]
    if len(set(counts))!=1 or (counts[0]==0 and exit_code!=0):raise ValueError('Unclassified control exit or incomplete log set')
    result={'independent_control_actions_complete':True,'lootable_bodies_installed':False,'native_respawns':8,'fifo_batches':batches,'original_strict_clean_runtime_pass':counts[0]==0,'classified_native37_negative_baseline':counts[0]==8,'ordinary_runtime_allowance_added':False,'raw_logs':records,'verifier_exit':exit_code}
    (output/'native-platform-baseline.json').write_text(json.dumps(result,indent=2)+'\n')
    return result
