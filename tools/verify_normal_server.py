"""Start and cleanly stop ordinary servers with no build-test mod installed."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
from audit_runtime_logs import audit
from verify_packaged import prepare, digest, FORGE, MAIN


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--development',action='store_true')
    args=parser.parse_args(); project=Path(__file__).resolve().parents[1]
    root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
    home=root/'server'; jar=project/'build/libs'/MAIN
    if args.development:
        home.mkdir();(home/'eula.txt').write_text('eula=true\n')
        (home/'server.properties').write_text('level-name=world\nlevel-type=FLAT\nserver-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n')
        for name in ['ops','whitelist','banned-players','banned-ips']: (home/(name+'.json')).write_text('[]\n')
        init=root/'ordinary.init.gradle'
        path=home.as_posix().replace("'","\\'")
        init.write_text("gradle.projectsEvaluated { def p=gradle.rootProject; p.minecraft.runs.getByName('server').workingDir.set(new File('"+path+"')); p.tasks.named('runServer') { workingDir new File('"+path+"') } }\n")
        command=[str(project/('gradlew.bat' if os.name=='nt' else 'gradlew')),'runServer','--init-script',str(init),'--no-daemon','--console=plain']
        cwd=project
    else:
        prepare(home,args.forge_home.resolve(),jar)
        command=[str(args.java.resolve()),'-Xmx1G','-jar',FORGE+'.jar','--nogui'];cwd=home
    results=[]
    for phase in ['fresh','reload']:
        log=root/(phase+'.log')
        with log.open('w',encoding='utf-8') as output:
            process=subprocess.Popen(command,cwd=cwd,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
            try:
                deadline=time.monotonic()+150
                while 'Done (' not in log.read_text(encoding='utf-8',errors='replace'):
                    if process.poll() is not None or time.monotonic()>deadline: raise RuntimeError('Ordinary server did not start: '+str(log))
                    time.sleep(.2)
                process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=60)
            finally:
                if process.poll() is None: process.terminate();process.wait(timeout=15)
        text=log.read_text(encoding='utf-8',errors='replace')
        if process.returncode or 'Stopping server' not in text:
            raise RuntimeError('Ordinary server failed clean startup/stop: '+str(log))
        if 'lootablebodiesbuildtest' in text or list((home/'crash-reports').glob('*')): raise RuntimeError('Probe leak or crash')
        audit(log,development=args.development,native=True)
        for name in ('latest.log','debug.log'):
            complete=home/'logs'/name
            if not complete.is_file():raise FileNotFoundError(complete)
            audit(complete,development=args.development,native=True)
            if 'lootablebodiesbuildtest' in complete.read_text():raise RuntimeError('Probe in ordinary runtime')
        if not args.development and (len(list((home/'mods').glob('*.jar')))!=1 or digest(home/'mods'/MAIN)!=digest(jar)):
            raise RuntimeError('Ordinary packaged mod set changed')
        results.append(phase);print('ORDINARY_SERVER_PASS',phase,'development='+str(args.development),flush=True)
    (root/'result.json').write_text(json.dumps({'candidate_sha256':digest(jar),'development':args.development,
        'phases':results,'build_test_mod_installed':False},indent=2)+'\n')


if __name__=='__main__':main()
