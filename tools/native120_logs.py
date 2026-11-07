"""Current-target log contract, separate from retained legacy controls."""
import gzip,re
from audit_runtime_logs import audit as _legacy_audit

def audit(path,development=False,upgrade=False,fixture_logins=False,native=True):
    if fixture_logins:
        raise RuntimeError('Legacy login-retry classification is not Forge 52 qualification')
    if path.name.endswith('.log.gz'):
        with gzip.open(path,'rt',encoding='utf-8') as stream:text=stream.read()
    else:text=path.read_text(encoding='utf-8')
    lines=re.sub(r'\x1b\[[0-9;]*m','',text).splitlines()
    failures=[line for line in lines if re.search(
        r'/ERROR\]|/FATAL\]|\bERROR (?:Error processing|Unable to locate)|^AL lib: \(EE\)|Caused by:',line)]
    if failures:raise RuntimeError(str(path)+'\n'+'\n'.join(failures))
    # Retain all generic exception/assertion/missing-resource checks, without
    # importing older platform/development/upgrade waivers into this target.
    result=_legacy_audit(path)
    result['target_minecraft']='1.21.1'
    result['legacy_native_controls_used']=False
    return result
