"""Execute explicit native117 CI stages once, retaining every failed command."""
import argparse,hashlib,json,os,shutil,subprocess,sys,tempfile,time
from pathlib import Path
sys.dont_write_bytecode=True
from command_logs import run_build_input_logged
from release_identity import MAIN_JAR,MOD_VERSION
from native_runtime import digest
PROJECT=Path(__file__).resolve().parents[1]

def check_bundle(bundle):
    expected=json.loads((PROJECT/'.github/release117.json').read_text())
    if expected['version']!=MOD_VERSION:raise ValueError('Wrong approved release version')
    actual={p.name:digest(p) for p in bundle.iterdir() if p.is_file()}
    if actual!=expected['files']:raise ValueError('Built six-file bundle differs from accepted release')
    return actual

def run(name,args,*,timeout=1800):
    path=PROJECT/'build/ci117-commands'/name;path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():raise FileExistsError(path)
    with path.open('x') as output:
        result=subprocess.run([str(x) for x in args],cwd=PROJECT,stdout=output,stderr=subprocess.STDOUT,timeout=timeout,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'))
    if result.returncode:raise RuntimeError('Failed command; retained '+str(path))

def python(name,tool,*args,timeout=1800):run(name,[sys.executable,'-B',str(PROJECT/'tools'/tool),*args],timeout=timeout)

def gradle(name,*tasks,offline=False):
    args=[str(PROJECT/('gradlew.bat' if os.name=='nt' else 'gradlew')),*tasks,'-PminecraftAssetsDirectory=build/native-assets','--no-daemon','--console=plain','--stacktrace','--no-build-cache']
    if offline:args.append('--offline')
    # Only exact pre-task input transport failures receive the established
    # bounded sequential retry. Runtime and test failures are never retried.
    run_build_input_logged(args,cwd=PROJECT,path=PROJECT/'build/ci117-commands'/name,timeout=3600)

def build():
    cache=Path(os.environ['GRADLE_USER_HOME'])
    if (cache/'caches').exists() or (PROJECT/'.gradle').exists():raise ValueError('The authoritative first build requires a fresh Gradle cache and checkout')
    first=Path(os.environ.get('RUNNER_TEMP',tempfile.gettempdir()))/'lootablebodies117-first-build'
    first.mkdir(exist_ok=False)
    # Commands survive clean; no previous evidence directory is overwritten.
    tasks=('clean','build','javadoc','preparePackagedIntegrationProbe','preparePackagedForgeControl','prepareReleaseBundle')
    for number in (1,2):
        log=first/('build-'+str(number)+'.log')
        args=[str(PROJECT/('gradlew.bat' if os.name=='nt' else 'gradlew')),*tasks,'--no-daemon','--console=plain','--stacktrace','--no-build-cache']
        if number==2:args.append('--offline')
        run_build_input_logged(args,cwd=PROJECT,path=log,timeout=3600)
        bundle=PROJECT/'build/release/bundle';current=check_bundle(bundle)
        target=first/str(number);shutil.copytree(PROJECT/'build/test-results',target/'test-results')
        shutil.copytree(bundle,target/'bundle')
        import xml.etree.ElementTree as ET
        xml=list((target/'test-results/test').glob('TEST-*.xml'));assert xml
        tests=sum(int(ET.parse(f).getroot().attrib['tests']) for f in xml)
        assert tests>=223 and all(int(ET.parse(f).getroot().attrib.get(k,0))==0 for f in xml for k in ('failures','errors','skipped'))
        if number==1:expected=current
        else:assert current==expected,'Two clean uncached six-file builds differ'
    target=PROJECT/'build/ci117-build-proof';shutil.copytree(first,target)
    (target/'result.json').write_text(json.dumps({'two_clean_uncached_builds':True,'second_build_offline':True,'junit_each':tests,'files':expected},indent=2)+'\n')

def native_sdk_and_eclipse(java):
    python('native-server-install.log','native_runtime.py','--java',java,'--output','build/native-server-distribution')
    python('native-client-install.log','native_runtime.py','--java',java,'--output','build/native-client-distribution','--client')
    gradle('eclipse-and-assets.log','prepareMinecraftAssets','verifyEclipseProductionClasspath',
           '-PminecraftNativesDirectory=build/native-client-distribution/prepared-inputs/natives')

def prepare():
    java=Path(os.environ['JAVA_HOME_16_X64'])/'bin/java'
    java8=Path(os.environ['JAVA_HOME_8_X64'])/'bin/java'
    native_sdk_and_eclipse(java)
    from native_runtime import prepare_server,run_java,HISTORICAL_JVM_ARGS
    from audit_runtime_logs import audit
    control=PROJECT/'build/control-test-mod/forge-platform-control-reobf.jar'
    resource_proof=[]
    for number in range(4):
        home=PROJECT/'build/native37-resource-budget'/str(number)
        prepare_server(home,PROJECT/'build/native-server-distribution',probe=control)
        result=run_java(java,home,'resource-50',jvm_args=[*HISTORICAL_JVM_ARGS,'-Dlootablebodies.control.resourceReloads=50','-Dlog4j.configurationFile='+(PROJECT/'src/integrationTest/resources/log4j2.xml').as_uri()])
        assert result.returncode==0 and (home/'resource-control.pass').is_file() and not (home/'world').exists()
        logs=[home/('resource-50'+suffix) for suffix in ('.log','.latest.log','.debug.log')]
        resource_proof.append({'rounds':50,'no_world':True,'logs':[audit(f,native=True) for f in logs],'receipt_sha256':digest(home/'resource-50.diagnostics/result.json')})
    (PROJECT/'build/native37-resource-budget/result.json').write_text(json.dumps({'actual_native_reloads':200,'jvms':4,'platform':sys.platform,'control_jar':digest(control),'proof':resource_proof},indent=2)+'\n')
    gradle('assets-and-mapped.log','prepareMinecraftAssets','integrationTest','upgradeContractTest','clientIntegrationTest')
    python('ordinary-development.log','verify_normal_server.py','--java',java,'--forge-home','build/native-server-distribution','--output','build/ordinary-development','--development',*flat())
    python('native116-writer.log','prepare_native116_tools.py','--java17-home',os.environ['JAVA_HOME'],'--java8-home',os.environ['JAVA_HOME_8_X64'],'--java25-home',os.environ['JAVA_HOME_25_X64'])
    python('native116-originals.log','prepare_native117_history.py','--download','build/prior116-download','--java8',java8)
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=PROJECT,text=True).strip()
    python('seal-history.log','migration_ci.py','seal','--commit',commit)
    from migration_ci import seal
    roots=['build/libs','build/release/bundle','build/integration-test-mod','build/control-test-mod','build/native-server-distribution','build/native-client-distribution','build/native-assets']
    paths=[p.relative_to(PROJECT).as_posix() for root in roots for p in (PROJECT/root).rglob('*') if p.is_file()]
    seal(PROJECT,paths,PROJECT/'build/ci117-platform-inputs',commit)

def flat():
    p=PROJECT/'tools/fixtures/native117-flat-level.dat'
    return ['--flat-header',str(p),'--flat-header-sha256',digest(p)]

def scenario(group):
    java=Path(os.environ['JAVA_HOME_16_X64'])/'bin/java'
    server=['--java',java,'--forge-home','build/native-server-distribution']
    client=['--forge-client-home','build/native-client-distribution','--client-inputs','build/native-client-distribution/prepared-inputs','--assets','build/native-assets']
    root=Path('build/ci117-scenarios')/group
    if root.exists():raise FileExistsError(root)
    root.mkdir(parents=True)
    if group=='servers':
        python('packaged.log','verify_packaged.py',*server,*flat(),'--output',root/'packaged')
        python('ordinary-packaged.log','verify_normal_server.py',*server,*flat(),'--output',root/'ordinary')
        python('malformed-recovery.log','verify_nbt_recovery.py',*server,*flat(),'--output',root/'nbt')
    elif group in ('default','config','skin','issues','names','rejected'):
        mode='issues' if group=='rejected' else group
        extra=['--rejected-displaced-viewer'] if group=='rejected' else []
        python(group+'.log','verify_packaged_client.py','--java',java,*client,'--mode',mode,'--output',root/'client',*extra)
    elif group=='multiplayer':python('multiplayer.log','verify_multiplayer.py',*server,*client,*flat(),'--output',root/'run')
    elif group=='visibility':
        python('visibility.log','verify_visibility.py',*server,*client,*flat(),'--output',root/'live','--mode','visibility',timeout=1800)
        python('visibility-restart.log','verify_visibility.py',*server,*client,*flat(),'--output',root/'restart','--mode','visibility','--source-server',root/'live/server',timeout=1800)
    elif group=='control':
        log=PROJECT/'build/ci117-commands/native-platform-control.log';log.parent.mkdir(parents=True,exist_ok=True)
        with log.open('x') as output:
            result=subprocess.run([sys.executable,'-B',str(PROJECT/'tools/verify_native_respawn_control.py'),*[str(x) for x in server+client+flat()], '--output',str(root/'run')],cwd=PROJECT,stdout=output,stderr=subprocess.STDOUT,timeout=900)
        from native37_control_evidence import classify
        classify(PROJECT/root/'run',PROJECT/'build/control-test-mod/forge-platform-control-reobf.jar',result.returncode)
    elif group=='respawn':
        for variant,extra in [('ordinary',[]),('simultaneous',['--simultaneous']),('collision',['--counter-collision']),('restart',['--simultaneous','--counter-collision','--source-server',root/'ordinary/server'])]:
            python('respawn-'+variant+'.log','verify_visibility.py',*server,*client,*flat(),'--output',root/variant,'--mode','respawn','--cases','16',*extra,timeout=1800)
    else:raise ValueError(group)
    (root/'complete.json').write_text(json.dumps({'group':group,'candidate':digest(PROJECT/'build/libs'/MAIN_JAR),'complete':True})+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['build','prepare','scenario']);p.add_argument('--group');a=p.parse_args()
    if a.mode=='build':build()
    elif a.mode=='prepare':prepare()
    else:scenario(a.group)

if __name__=='__main__':main()
