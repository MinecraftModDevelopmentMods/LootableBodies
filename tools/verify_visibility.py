"""Run real two-client corpse visibility and death/respawn packet checks."""
import argparse
import json
import socket
import subprocess
import time
from pathlib import Path
import shutil
import os
from native_runtime import seed_flat_fixture
from owned_process import native_process
from audit_runtime_logs import audit
from fixture_login import LoginMonitor, start_clients, write_receipts, archive_live_logs
from verify_multiplayer import client_command
from verify_packaged import prepare, FORGE, MAIN, digest, run


def death_render_proof(home):
    shared=home/'sync';packets=(home/'clientA/client-packets.txt').read_text();proof=[]
    for phase,name in [(2,'first'),(14,'second')]:
        receipt=json.loads((shared/('A.death-render-'+str(phase)+'.json')).read_text())
        identity=(shared/(name+'.uuid')).read_text().strip()
        if (receipt!={'phase':phase,'uuid':identity,'death_screen_drawn':True,'live_corpse_rendered':True}
                or type(receipt.get('death_screen_drawn')) is not bool or type(receipt.get('live_corpse_rendered')) is not bool):
            raise RuntimeError('Missing actual rendered death-screen corpse proof')
        if not any(' IN MOD_SPAWN ' in line and 'mod=lootablebodies ' in line and 'uuid='+identity in line for line in packets.splitlines()):
            raise RuntimeError('Rendered corpse lacks its actual receiving TCP spawn packet')
        if any('FAULT_DROPPED ' in line and 'uuid='+identity in line for line in packets.splitlines()):
            raise RuntimeError('Dropped corpse spawn cannot qualify a render receipt')
        proof.append(receipt)
    return proof


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--forge-client-home',type=Path,required=True)
    parser.add_argument('--client-inputs',type=Path,required=True)
    parser.add_argument('--assets',type=Path)
    parser.add_argument('--flat-header',type=Path,required=True)
    parser.add_argument('--flat-header-sha256',required=True)
    parser.add_argument('--hidden-agent',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--mode',choices=['visibility','respawn'],required=True)
    parser.add_argument('--source-server',type=Path)
    parser.add_argument('--probe-jar',type=Path,help='Isolated verifier jar; production jar is unchanged')
    parser.add_argument('--cases',type=int,default=16)
    parser.add_argument('--simultaneous',action='store_true')
    parser.add_argument('--drop-spawn',action='store_true',help='Destructive negative control: discards every owner corpse spawn; expected to fail')
    parser.add_argument('--counter-collision',action='store_true')
    args=parser.parse_args()
    project=Path(__file__).resolve().parents[1]
    java=args.java.resolve(); distribution=args.forge_home.resolve(); home=args.output.resolve()
    home.mkdir(parents=True,exist_ok=False); shared=home/'sync'; shared.mkdir()
    jar=project/'build/libs'/MAIN; before=digest(jar)
    probe=(args.probe_jar or project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar').resolve()
    prepare(home/'server',distribution,jar,probe)
    source_before=None
    if args.source_server:
        from verify_world_upgrades import fingerprint
        source_before=fingerprint(args.source_server/'world')
        shutil.copytree(args.source_server/'world',home/'server/world')
    else:
        seed_flat_fixture(home/'server',args.flat_header,args.flat_header_sha256)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0)); port=reservation.getsockname()[1]
    props=(home/'server/server.properties').read_text().replace('server-port=0','server-port='+str(port))+'view-distance=4\n'
    (home/'server/server.properties').write_text(props)
    commands={'server':[str(java),'-Xmx1G','-Dlootablebodies.'+args.mode+'=true',
        '-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri(),
        '-Dlootablebodies.multiplayer.sync='+str(shared),'-Dlootablebodies.respawn.cases='+str(args.cases),
        '@libraries/net/minecraftforge/forge/1.17.1-37.1.1/'+('win_args.txt' if os.name=='nt' else 'unix_args.txt'),'--nogui']}
    if args.source_server: commands['server'].insert(2,'-Dlootablebodies.visibility.reload=true')
    if args.simultaneous: commands['server'].insert(2,'-Dlootablebodies.respawn.simultaneous=true')
    for role in ['A','B']:
        command=client_command(project,java,args.forge_client_home.resolve(),home/('client'+role),None,shared,port,role,args.hidden_agent,probe,prepared=args.client_inputs.resolve(),assets=args.assets.resolve() if args.assets else None)
        command.insert(2,'-Dlootablebodies.'+args.mode+'=true')
        if args.simultaneous: command.insert(2,'-Dlootablebodies.respawn.simultaneous=true')
        if args.counter_collision: command.insert(2,'-Dlootablebodies.respawn.counterCollision=true')
        if args.drop_spawn and role=='A': command.insert(2,'-Dlootablebodies.respawn.dropSpawn=true')
        commands['client'+role]=command
        (home/('client'+role)/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=false\n}\n')
    processes={}; logs=[]
    def start(name):
        processes[name]=native_process(commands[name],cwd=home/name,log=home/(name+'.log'),timeout=710 if name=='server' else 650)
    try:
        start('server');deadline=time.monotonic()+60
        while not (shared/'server.ready').is_file():
            if processes['server'].poll() is not None or time.monotonic()>deadline: raise RuntimeError('Server did not start')
            time.sleep(.2)
        monitor=LoginMonitor(home,legacy_recovery=False)
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
        audit(home/(name+'.log'),native=True);audit(home/name/'logs/latest.log',native=True);audit(home/name/'logs/debug.log',native=True)
        if list((home/name).rglob('crash-reports/*.txt')): raise RuntimeError('Crash report')
    for role in ['A','B']:
        if not (home/('client'+role)/(args.mode+'-client.pass')).is_file(): raise RuntimeError('Client did not finish '+role)
    death_proof=[]
    if args.mode=='visibility' and not args.source_server:
        death_proof=death_render_proof(home)
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
        'rendered_death_screen_proof':death_proof,
        'computer_control':'none'},indent=2)+'\n')
    print('VISIBILITY_RESPAWN_PASS',args.mode,flush=True)


if __name__=='__main__':main()
