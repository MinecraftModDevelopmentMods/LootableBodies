"""Explicit native45 client diagnostics, separate from strict server/error audits."""
from pathlib import Path
import gzip,hashlib,json,re,tempfile
from native119_logs import audit as strict_audit

def validated_baseline(policy_path=None,proof_path=None):
    project=Path(__file__).resolve().parents[1]
    policy_path=Path(policy_path) if policy_path else Path(__file__).with_name('native119_client_diagnostics.json')
    proof_path=Path(proof_path) if proof_path else project/'.gradle/native119-client-sound-qualification-64/proof.json'
    if not policy_path.is_file() or not proof_path.is_file():raise RuntimeError('Missing native client diagnostic qualification')
    policy=json.loads(policy_path.read_text(encoding='utf-8'))
    raw=proof_path.read_bytes()
    if hashlib.sha256(raw).hexdigest().upper()!=policy.get('proof_sha256'):raise RuntimeError('Changed native client diagnostic qualification')
    proof=json.loads(raw)
    if tuple(policy.get(k) for k in ('minecraft','forge','platform'))!=('1.19.4','45.4.5','Windows amd64') or policy.get('qualified') is not True:raise RuntimeError('Wrong native client diagnostic target')
    if any(proof.get(k)!=policy.get(k) for k in ('minecraft','forge','platform','events','control_sha256')):raise RuntimeError('Native client proof identity mismatch')
    if proof.get('qualified') is not True or proof.get('no_candidate') is not True or proof.get('no_world') is not True or type(proof.get('native_client_exit')) is not int or proof.get('native_client_exit')!=0 or proof.get('original_strict_audit_passed') is not False:raise RuntimeError('Incomplete independent native client control')
    for group in ('critical_files','raw_control_logs'):
        if not proof.get(group):raise RuntimeError('Missing native client diagnostic evidence')
        for name,expected in proof[group].items():
            path=Path(name)
            if not path.is_absolute() or not path.is_file() or path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest().upper()!=expected:raise RuntimeError('Changed native client diagnostic input: '+name)
    definition=Path(proof['sound_definition']);data=definition.read_bytes()
    if hashlib.sha256(data).hexdigest().upper()!=policy.get('sound_definition_sha256'):raise RuntimeError('Changed official sound definitions')
    definitions=json.loads(data)
    if len(set(policy['events']))!=5 or any(not name.startswith('minecraft:') or name.removeprefix('minecraft:') in definitions for name in policy['events']):raise RuntimeError('Native diagnostic is no longer an unbound vanilla event')
    return policy

def audit_client(path,**options):
    path=Path(path);raw=path.read_bytes()
    text=(gzip.decompress(raw) if path.name.endswith('.gz') else raw).decode('utf-8')
    lines=text.splitlines(keepends=True)
    missing=[line for line in text.splitlines() if re.search(r'(?i)\bMissing (?:texture|model|sound file)\s*:|\bFile .+ does not exist, cannot add it to event',line)]
    if missing:raise RuntimeError(str(path)+'\n'+'\n'.join(missing))
    selected=[i for i,line in enumerate(lines) if 'Missing sound for event:' in line]
    if not selected:
        result=strict_audit(path,**options)
        result.update(classified_native_sound_warnings=0,raw_strict_audit_passed=True,residual_strict_audit_passed=True)
        return result
    policy=validated_baseline();events=policy['events'];observed=[]
    pattern=r'^\[\d{2}:\d{2}:\d{2}(?:\.\d{3})?\] \[Render thread/WARN\] \[net\.minecraft\.client\.sounds\.SoundEngine\]: Missing sound for event: (minecraft:[a-z0-9_./]+)$'
    for index in selected:
        match=re.fullmatch(pattern,lines[index].rstrip('\r\n'))
        if match is None:raise RuntimeError('Unclassified native client sound diagnostic: '+lines[index].rstrip())
        observed.append(match[1])
    if len(observed)%len(events) or observed!=events*(len(observed)//len(events)):
        raise RuntimeError('Incomplete, changed or unknown native client sound diagnostic batch')
    chosen=set(selected);residual=''.join(line for i,line in enumerate(lines) if i not in chosen)
    # Preserve original bytes and their strict failure. Only a derived audit
    # view excludes these proved native WARN records; every generic error and
    # referenced missing-resource check still runs unchanged on that view.
    with tempfile.TemporaryDirectory(prefix='native119-diagnostic-audit-') as directory:
        view=Path(directory)/'client.log';view.write_text(residual,encoding='utf-8',newline='')
        try:result=strict_audit(view,**options)
        except RuntimeError as error:raise RuntimeError(str(path)+'\n'+str(error)) from error
    result.update(path=str(path),lines=len(lines),classified_native_sound_warnings=len(observed),classified_native_sound_events=observed,
                  raw_strict_audit_passed=False,residual_strict_audit_passed=True,native_sound_control_proof_sha256=policy['proof_sha256'],raw_sha256=hashlib.sha256(raw).hexdigest().upper())
    return result
