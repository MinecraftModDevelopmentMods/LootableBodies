"""Run real two-client corpse visibility and death/respawn packet checks."""
import argparse
import json
import socket
import subprocess
import time
from pathlib import Path
import shutil
from audit_runtime_logs import audit
from fixture_login import LoginMonitor, start_clients, write_receipts, archive_live_logs
from verify_multiplayer import client_command
from verify_packaged import prepare, FORGE, MAIN, digest, run
from verify_packaged_client import prepare_assets


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--gradle-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--mode',choices=['visibility','respawn'],required=True)
    parser.add_argument('--source-server',type=Path)
    parser.add_argument('--cases',type=int,default=16)
    parser.add_argument('--simultaneous',action='store_true')
    parser.add_argument('--drop-spawn',action='store_true')
    parser.add_argument('--counter-collision',action='store_true')
    args=parser.parse_args()
    project=Path(__file__).resolve().parents[1]
    java=args.java.resolve(); distribution=args.forge_home.resolve(); home=args.output.resolve()
    home.mkdir(parents=True,exist_ok=False); shared=home/'sync'; shared.mkdir()
    jar=project/'build/libs'/MAIN; before=digest(jar)
    probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    prepare(home/'server',distribution,jar,probe)
    source_before=None
    if args.source_server:
        from verify_world_upgrades import fingerprint
        source_before=fingerprint(args.source_server/'world')
        shutil.copytree(args.source_server/'world',home/'server/world')
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0)); port=reservation.getsockname()[1]
    props=(home/'server/server.properties').read_text().replace('server-port=0','server-port='+str(port))+'view-distance=4\n'
    (home/'server/server.properties').write_text(props)
    commands={'server':[str(java),'-Xmx1G','-Dlootablebodies.'+args.mode+'=true',
        '-Dlootablebodies.multiplayer.sync='+str(shared),'-Dlootablebodies.respawn.cases='+str(args.cases),
        '-jar',FORGE+'-universal.jar','nogui']}
    if args.source_server: commands['server'].insert(2,'-Dlootablebodies.visibility.reload=true')
    if args.simultaneous: commands['server'].insert(2,'-Dlootablebodies.respawn.simultaneous=true')
    prepare_assets(project/'.gradle/packaged-assets')
    for role in ['A','B']:
        command=client_command(project,java,distribution,home/('client'+role),args.gradle_home.resolve()/'caches',shared,port,role)
        command.insert(2,'-Dlootablebodies.'+args.mode+'=true')
        if args.simultaneous: command.insert(2,'-Dlootablebodies.respawn.simultaneous=true')
        if args.counter_collision: command.insert(2,'-Dlootablebodies.respawn.counterCollision=true')
        if args.drop_spawn and role=='A': command.insert(2,'-Dlootablebodies.respawn.dropSpawn=true')
        commands['client'+role]=command
        (home/('client'+role)/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=false\n}\n')
    processes={}; logs=[]
    def start(name):
        log=(home/(name+'.log')).open('w',encoding='utf-8');logs.append(log)
        processes[name]=subprocess.Popen(commands[name],cwd=home/name,stdout=log,stderr=subprocess.STDOUT)
    try:
        start('server');deadline=time.monotonic()+60
        while not (shared/'server.ready').is_file():
            if processes['server'].poll() is not None or time.monotonic()>deadline: raise RuntimeError('Server did not start')
            time.sleep(.2)
        monitor=LoginMonitor(home)
        start_clients(start);deadline=time.monotonic()+650
        while any(process.poll() is None for process in processes.values()):
            monitor.poll()
            if processes['server'].poll() is not None and not (shared/(args.mode+'.pass')).is_file():
                raise RuntimeError('Server failed: '+str(home/'server.log'))
            if any(process.poll() not in (None,0) for process in processes.values()): raise RuntimeError('Owned client failed')
            if time.monotonic()>deadline: raise RuntimeError('Visibility/respawn check timed out')
            time.sleep(.2)
        if any(process.returncode for process in processes.values()): raise RuntimeError('Process exit code')
    finally:
        (shared/'abort').touch()
        for process in processes.values():
            if process.poll() is None: process.terminate()
        for process in processes.values():
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill();process.wait()
        for log in logs: log.close()
    if not (shared/(args.mode+'.pass')).is_file(): raise RuntimeError('Missing server result')
    write_receipts(home)
    for name in ['server','clientA','clientB']:
        if digest(home/name/'mods'/MAIN)!=before: raise RuntimeError('Candidate copy changed')
        audit(home/(name+'.log'),fixture_logins=True);audit(home/name/'logs/latest.log',fixture_logins=True)
        if list((home/name).rglob('crash-reports/*.txt')): raise RuntimeError('Crash report')
    for role in ['A','B']:
        if not (home/('client'+role)/(args.mode+'-client.pass')).is_file(): raise RuntimeError('Client did not finish '+role)
    if args.mode=='visibility' and not args.source_server:
        (home/'server/server.properties').write_text(props.replace('server-port='+str(port),'server-port=0'))
        archive_live_logs(home)
        run(java,home/'server','visibility-reload')
    if args.source_server and fingerprint(args.source_server/'world')!=source_before:
        raise RuntimeError('Source world changed')
    if digest(jar)!=before: raise RuntimeError('Candidate changed')
    checks=(shared/'checks.txt').read_text().splitlines()
    if not checks or (args.mode=='respawn' and len(checks)!=args.cases): raise RuntimeError('Incomplete case matrix')
    (home/'result.json').write_text(json.dumps({'candidate_sha256':before,'mode':args.mode,
        'checks':checks,'two_tcp_clients':True,'simultaneous':args.simultaneous,'drop_spawn':args.drop_spawn,
        'counter_collision':args.counter_collision,'connected_restart':bool(args.source_server),
        'computer_control':'none'},indent=2)+'\n')
    print('VISIBILITY_RESPAWN_PASS',args.mode,flush=True)


if __name__=='__main__':main()
