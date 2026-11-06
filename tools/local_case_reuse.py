"""Local normal-case reuse only; original process homes and receipts stay intact.

This is not a hosted promotion contract. The sole allowed tool change is the
unexecuted patch_stamps body. Its signature and every other execution input
must match. Reused proof is always labelled and independently rehashed.
"""
import ast,json
from pathlib import Path
from native_runtime import digest,fingerprint
from native_evidence import audit_phase,audit_case_mods,verify_saved_audit

DEPENDENCIES=('gradle.properties','src/integrationTest/resources/log4j2.xml',
 'tools/native_runtime.py','tools/runtime_diagnostics.py','tools/command_logs.py',
 'tools/prepared_worlds.py','tools/historical_workers.py','tools/release_identity.py',
 'tools/nbt_regions.py','tools/native120_persistence.py','tools/audit_runtime_logs.py',
 'tools/native_evidence.py','tools/native120_server_inputs.json',
 'tools/native120_worker_budget.json','tools/native120_logs.py')

def normal_program(text):
    tree=ast.parse(text)
    matches=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='patch_stamps']
    if len(matches)!=1:raise ValueError('Missing unambiguous stamp-only function')
    matches[0].body=[ast.Pass()]
    return ast.dump(tree,include_attributes=False)

def compare_programs(old,new):
    if normal_program(old)!=normal_program(new):raise ValueError('Normal execution program changed')

def compare_inputs(old,new):
    for key in ('base_source','main_sha256','probe_sha256','sdk_files','java_files'):
        if old[key]!=new[key]:raise ValueError('Changed normal input: '+key)
    left=old['frozen_files'];right=new['frozen_files']
    for name in (*DEPENDENCIES,'prepared-worlds.json'):
        if name not in left or left[name]!=right.get(name):raise ValueError('Changed normal dependency: '+name)

def audit_normal(home,details,identity):
    from verify_world_upgrades import PHASES
    from native120_logs import audit
    if details['phases']!=PHASES or details['stamp_mutations'] or not details['source_unchanged']:
        raise ValueError('Only one complete unchanged normal case is reusable')
    mods=audit_case_mods(home,identity);receipts=[];logs=[]
    for phase in PHASES:
        record=audit_phase(home,phase);record['logs'].update(mods);receipts.append(record)
    declared={Path(r['receipt']).resolve() for r in receipts}
    if declared!={p.resolve() for p in home.glob('**/*.diagnostics/result.json')}:raise ValueError('Extra or missing normal receipts')
    for path in home.rglob('*'):
        if path.is_file() and 'crash-reports' in path.parts:raise ValueError('Normal crash report')
        if path.is_file() and path.name.endswith(('.log','.log.gz')):
            audit(path);logs.append({'path':str(path),'sha256':digest(path)})
    if not logs:raise ValueError('Missing normal complete logs')
    return {'receipts':receipts,'positive_complete_logs':logs}

def prove(old_root,new_root,origin):
    old_root=Path(old_root).resolve();new_root=Path(new_root).resolve()
    old=json.loads((old_root/'inputs.json').read_text());new=json.loads((new_root/'inputs.json').read_text())
    for root,inputs in ((old_root,old),(new_root,new)):
        if fingerprint(root/'frozen-inputs')!=inputs['frozen_files']:raise ValueError('Changed frozen inputs')
    compare_inputs(old,new)
    compare_programs((old_root/'frozen-inputs/tools/verify_world_upgrades.py').read_text(),
                     (new_root/'frozen-inputs/tools/verify_world_upgrades.py').read_text())
    from prepared_worlds import load
    from verify_world_upgrades import ORIGINS
    sources,_=load(new_root/'frozen-inputs/prepared-worlds.json',ORIGINS,[origin])
    source,seal=sources[origin]
    case=origin+'-normal';result=old_root/'origins'/origin/'migration-results/result.json'
    previous=json.loads(result.read_text());details=previous['cases'][case]
    if details['origin_jar_sha256']!=seal['jar_sha256']:raise ValueError('Changed historical writer')
    if (previous['candidate_sha256'],previous['probe_sha256'])!=(new['main_sha256'],new['probe_sha256']):raise ValueError('Changed executed candidate')
    home=result.parent/case;before=fingerprint(home)
    audit=audit_normal(home,details,previous)
    if fingerprint(home)!=before or fingerprint(source)!=seal['world']:raise ValueError('Normal proof/source changed during audit')
    return {'scope':'LOCAL normal case only; original successful receipts reused, not rerun',
      'origin':origin,'case':case,'home':str(home),'old_inputs':str(old_root/'inputs.json'),
      'old_inputs_sha256':digest(old_root/'inputs.json'),'new_inputs_sha256':digest(new_root/'inputs.json'),
      'old_result':str(result),'old_result_sha256':digest(result),'details':details,
      'home_files':before,'native_audit':audit,'reused_positive_jvms':10,'fresh_positive_jvms':0}

def validate(proof,new_root,origin):
    path=Path(proof).resolve();saved=json.loads(path.read_text())
    fresh=prove(Path(saved['old_inputs']).parent,new_root,origin)
    if saved!=fresh or not verify_saved_audit(Path(saved['home']),saved['native_audit']):raise ValueError('Changed local reuse proof')
    return saved
