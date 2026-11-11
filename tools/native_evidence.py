"""Independently audit Forge 64 local receipts, complete logs and diagnostic bytes.

Default auditing binds the recorded cwd to the actual owned process home.
Relocated auditing is a separately tested download contract; hosted Forge 64
execution and promotion remain disabled and have not been exercised.
"""
from pathlib import Path,PurePosixPath
import hashlib,json
from native_runtime import FORGE,HISTORICAL_JVM_ARGS

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def audit_phase(home,phase,*,refusal=False,relocated=False):
    directory=home/(phase+'.diagnostics')
    receipt=json.loads((directory/'result.json').read_text())
    if receipt['timed_out'] or type(receipt['returncode']) is not int or receipt['pid']<=0:
        raise ValueError('Incomplete or timed-out native JVM receipt')
    if receipt['timeout']!=(180 if refusal else 240):raise ValueError('Changed native deadline')
    if receipt['returncode']!=(1 if refusal else 0):raise ValueError('Unexpected native JVM exit')
    command=receipt['command']
    # Forge 64 was qualified with its default worker and GC settings.
    if any(s.startswith(('-XX:ActiveProcessorCount','-XX:ParallelGCThreads','-XX:ConcGCThreads','-Dmax.bg.threads'))
            or (s.startswith(('-XX:+Use','-XX:-Use')) and s.endswith('GC')) for s in command):
        raise ValueError('Unqualified Forge 64 worker or GC override')
    if relocated:
        # Downloaded artifacts retain their original machine cwd. Require the
        # complete case/condition suffix; all bytes are read only under home.
        recorded=PurePosixPath(receipt['cwd'].replace('\\','/'))
        expected=PurePosixPath('migration-abort')/home.parent.name/home.name if refusal else PurePosixPath('migration-results')/home.name
        if tuple(recorded.parts[-len(expected.parts):])!=expected.parts:raise ValueError('Relocated receipt belongs to another case/condition')
    elif Path(receipt['cwd']).resolve()!=home.resolve():raise ValueError('Receipt belongs to another actual process home')
    prefix='@libraries/net/minecraftforge/forge/'
    arguments=[part for part in command if part.startswith(prefix)]
    version=FORGE.removeprefix('forge-')
    expected={prefix+version+'/'+name for name in ('win_args.txt','unix_args.txt')} if relocated else {
        prefix+version+'/'+('win_args.txt' if __import__('os').name=='nt' else 'unix_args.txt')}
    if len(arguments)!=1 or arguments[0] not in expected or '--nogui' not in command:raise ValueError('Receipt did not launch Forge 64 modules')
    if not any(item.startswith('-Xlog:gc*,safepoint:file=') for item in command):raise ValueError('Missing Java 25 unified GC evidence')
    if not refusal and '-Dlootablebodies.phase='+phase not in command:raise ValueError('Receipt belongs to another phase')
    for name in ('gc.txt','initial-threads.txt','initial-heap-gc.txt'):
        if name not in receipt['evidence'] or not (directory/name).is_file() or not (directory/name).stat().st_size:
            raise ValueError('Missing mandatory native diagnostics: '+name)
    for name,sha in receipt['evidence'].items():
        if Path(name).name!=name or not (directory/name).is_file() or digest(directory/name)!=sha:
            raise ValueError('Missing or changed hosted native evidence: '+name)
    for item in receipt['captures']+receipt['metrics']:
        if item['file'] not in receipt['evidence'] or item['returncode'] is None or item['started_seconds']>=receipt['timeout']:
            raise ValueError('Incomplete hosted diagnostic attempt')
    for name in (phase+'.log',phase+'.latest.log',phase+'.debug.log'):
        if not (home/name).is_file() or not (home/name).stat().st_size:raise ValueError('Missing complete native log: '+name)
    text=(home/(phase+'.log')).read_text(encoding='utf-8')
    if refusal:
        if "Lootable Bodies could not preserve this world's corpses" not in text:raise ValueError('Missing preservation refusal')
        if any(s in text for s in ('Preparing start region','Preparing spawn area','LOOTABLE_BODIES_RUNTIME_PASS','Done (')):
            raise ValueError('World loading started after unsafe preservation')
        comparison=json.loads((home/'comparison.json').read_text())
        if not comparison['sealed_source_unchanged'] or any(comparison[k] for k in ('world_added','world_removed','world_changed')):
            raise ValueError('Unsafe refusal changed source/world data')
    elif 'LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase='+phase not in text:
        raise ValueError('Missing actual native phase assertions')
    logs={str(home/name):digest(home/name) for name in (phase+'.log',phase+'.latest.log',phase+'.debug.log')}
    if refusal:logs[str(home/'comparison.json')]=digest(home/'comparison.json')
    return {'receipt':str(directory/'result.json'),'sha256':digest(directory/'result.json'),
            'native_files':len(receipt['evidence']),'returncode':receipt['returncode'],'logs':logs}

def verify_saved_audit(root,report):
    def exact(path,sha):
        path=Path(path).resolve()
        return path.is_relative_to(root.resolve()) and path.is_file() and digest(path)==sha
    try:
        for item in report['receipts']:
            path=Path(item['receipt'])
            if not exact(path,item['sha256']):return False
            receipt=json.loads(path.read_text())
            for name,sha in receipt['evidence'].items():
                if Path(name).name!=name or not exact(path.parent/name,sha):return False
            for log,sha in item['logs'].items():
                if not exact(log,sha):return False
        return all(exact(item['path'],item['sha256']) for item in report['positive_complete_logs'])
    except (OSError,KeyError,TypeError,ValueError):
        return False

def audit_case_mods(home,report):
    from release_identity import MAIN_JAR
    expected={MAIN_JAR:report['candidate_sha256'],'lootablebodies-build-tests.jar':report['probe_sha256']}
    mods=home/'mods'
    if mods.is_symlink() or not mods.is_dir():raise ValueError('Missing or aliased native mods directory')
    archives=[p for p in mods.iterdir() if p.suffix.lower()=='.jar']
    if any(p.is_symlink() or not p.is_file() for p in archives):raise ValueError('Aliased or invalid native mod archive')
    actual={p.name:digest(p) for p in archives}
    if actual!=expected:raise ValueError('Actual native process mods differ from qualified candidate/probe: '+str(home))
    return {str(home/'mods'/name):sha for name,sha in expected.items()}


def audit_shard(folder,report,*,relocated=False):
    positive=folder/'migration-results';aborts=folder/'migration-abort'
    records=[];logs=[]
    from native120_logs import audit
    for case,details in report['cases'].items():
        home=positive/case
        mods=audit_case_mods(home,report)
        for phase in details['phases']:
            record=audit_phase(home,phase,relocated=relocated);record['logs'].update(mods);records.append(record)
    for case,details in report['aborts'].items():
        for condition in details['conditions']:
            home=aborts/case/condition;mods=audit_case_mods(home,report)
            record=audit_phase(home,'abort',refusal=True,relocated=relocated);record['logs'].update(mods);records.append(record)
    declared={Path(item['receipt']).resolve() for item in records}
    actual={path.resolve() for root in (positive,aborts) for path in root.glob('**/*.diagnostics/result.json')}
    if len(declared)!=len(records) or actual!=declared:raise ValueError('Missing, duplicate or extra native receipt')
    for path in positive.rglob('*'):
        if path.is_file() and path.name.endswith(('.log','.log.gz')):
            audit(path);logs.append({'path':str(path),'sha256':digest(path)})
        if path.is_file() and 'crash-reports' in path.parts:raise ValueError('Unexpected hosted crash report')
    if not logs:raise ValueError('Missing hosted positive complete logs')
    return {'receipts':records,'positive_complete_logs':logs}
