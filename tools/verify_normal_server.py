"""Start and cleanly stop ordinary servers with no build-test mod installed."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import re
import shutil
from audit_runtime_logs import audit
from verify_packaged import prepare, digest, FORGE, MAIN
from native_runtime import seed_loader_defaults,seed_flat_fixture
from owned_process import native_process


def prepare_development_home(home):
    home.mkdir(exist_ok=True);(home/'eula.txt').write_text('eula=true\n')
    seed_loader_defaults(home)
    (home/'server.properties').write_text('level-name=world\nlevel-type=DEFAULT\nserver-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n')
    for name in ['ops','whitelist','banned-players','banned-ips']: (home/(name+'.json')).write_text('[]\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--flat-header',type=Path,required=True)
    parser.add_argument('--flat-header-sha256',required=True)
    parser.add_argument('--development',action='store_true')
    args=parser.parse_args(); project=Path(__file__).resolve().parents[1]
    root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
    home=root/'server'; jar=project/'build/libs'/MAIN
    if args.development:
        prepare_development_home(home)
        init=root/'ordinary.init.gradle'
        path=home.as_posix().replace("'","\\'")
        logging=(project/'src/integrationTest/resources/log4j2.xml').as_uri()
        init.write_text("gradle.projectsEvaluated { def p=gradle.rootProject; p.minecraft.runs.getByName('server').workingDir.set(new File('"+path+"')); p.tasks.named('runServer') { workingDir new File('"+path+"'); systemProperty 'log4j.configurationFile','"+logging+"'; jvmArgs '-Xlog:gc*,safepoint:file=ordinary-game.gc.txt:time,uptime,pid,level,tags' } }\n")
        command=[str(project/('gradlew.bat' if os.name=='nt' else 'gradlew')),'runServer','--init-script',str(init),'-PgameRunDirectory='+str(home),'--no-daemon','--console=plain']
        cwd=project
    else:
        prepare(home,args.forge_home.resolve(),jar)
        command=[str(args.java.resolve()),'-Xmx1G',
            '-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri(),
            '@libraries/net/minecraftforge/forge/1.17.1-37.1.1/'+('win_args.txt' if os.name=='nt' else 'unix_args.txt'),'--nogui'];cwd=home
    seed_flat_fixture(home,args.flat_header,args.flat_header_sha256)
    results=[]
    for phase in ['fresh','reload']:
        started=time.monotonic();whole_deadline=started+210
        log=root/(phase+'.log')
        output=None
        if args.development:
            output=log.open('x',encoding='utf-8')
            process=subprocess.Popen(command,cwd=cwd,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True,start_new_session=os.name!='nt')
        else:process=native_process(command,cwd=home,log=log,timeout=210,console_input=True)
        try:
            deadline=started+150
            while 'Done (' not in log.read_text(encoding='utf-8',errors='replace'):
                if process.poll() is not None or time.monotonic()>deadline: raise RuntimeError('Ordinary server did not start: '+str(log))
                time.sleep(.2)
            if args.development:
                gc=home/'ordinary-game.gc.txt'
                matches=re.findall(r'\[([0-9]+)\]\[(?:info|debug|trace|warning|error)\]',gc.read_text())
                if not matches or len(set(matches))!=1:raise RuntimeError('Cannot identify the actual owned development game JVM from its new GC log')
                pid=int(matches[0])
            else:pid=json.loads((home/(phase+'.diagnostics/result.json')).read_text())['pid']
            diagnostics={}
            for label,tool,option in [('threads','jstack','-l'),('heap-gc','jstat','-gc')]:
                suffix='.exe' if os.name=='nt' else '';binary=args.java.resolve().with_name(tool+suffix);target=root/(phase+'.'+label+'.txt')
                with target.open('x') as stream:
                    remaining=whole_deadline-time.monotonic()
                    if remaining<=0:raise subprocess.TimeoutExpired(command,210)
                    receipt=subprocess.run([str(binary),option,str(pid)],stdout=stream,stderr=subprocess.STDOUT,timeout=min(10,remaining))
                if receipt.returncode or not target.stat().st_size:raise RuntimeError('Actual native16 diagnostic failed: '+str(target))
                diagnostics[target.name]=digest(target)
            process.stdin.write('stop\n');process.stdin.flush()
            remaining=whole_deadline-time.monotonic()
            if remaining<=0:raise subprocess.TimeoutExpired(command,210)
            process.wait(timeout=min(60,remaining))
        finally:
            if process.poll() is None:
                if args.development and os.name=='nt':
                    # This exact Popen-created launcher tree is owned by this run.
                    subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.STDOUT,timeout=10)
                elif args.development:
                    import signal
                    os.killpg(process.pid,signal.SIGKILL)
                else:process.terminate()
                process.wait(timeout=15)
            if output is not None:output.close()
        text=log.read_text(encoding='utf-8',errors='replace')
        if process.returncode or 'Stopping server' not in text:
            raise RuntimeError('Ordinary server failed clean startup/stop: '+str(log))
        if any(name in text for name in ['lootablebodiesbuildtest','forgeplatformcontrol']) or list((home/'crash-reports').glob('*')): raise RuntimeError('Probe leak or crash')
        audit(log,development=args.development,native=True)
        for name in ('latest.log','debug.log'):
            complete=home/'logs'/name
            if not complete.is_file():raise FileNotFoundError(complete)
            audit(complete,development=args.development,native=True)
            if any(name in complete.read_text() for name in ['lootablebodiesbuildtest','forgeplatformcontrol']):raise RuntimeError('Probe in ordinary runtime')
            shutil.copy2(complete,root/(phase+'.'+name))
        if args.development:
            shutil.copy2(home/'ordinary-game.gc.txt',root/(phase+'.game-gc.txt'))
            (root/(phase+'.native-game-receipt.json')).write_text(json.dumps({'actual_game_pid':pid,'pinned_java':str(args.java.resolve()),'game_gc':phase+'.game-gc.txt','diagnostics':diagnostics,'gradle_launcher_pid':process.pid,'startup_timeout':150,'shutdown_timeout':60,'source':'Actual native16 GC PID decorator from this newly owned home; no global process enumeration'},indent=2)+'\n')
        if not args.development and (len(list((home/'mods').glob('*.jar')))!=1 or digest(home/'mods'/MAIN)!=digest(jar)):
            raise RuntimeError('Ordinary packaged mod set changed')
        results.append(phase);print('ORDINARY_SERVER_PASS',phase,'development='+str(args.development),flush=True)
    (root/'result.json').write_text(json.dumps({'candidate_sha256':digest(jar),'development':args.development,
        'phases':results,'build_test_mod_installed':False},indent=2)+'\n')


if __name__=='__main__':main()
