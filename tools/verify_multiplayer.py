"""Two real loopback Forge clients sharing a disposable packaged dedicated server."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
from audit_runtime_logs import audit
from fixture_login import LoginMonitor, start_clients, write_receipts, archive_live_logs
from native_runtime import project_assets,FORGE,digest,prepare_server as prepare,run_server as run,client_command as native_client_command,prepare_client
from release_identity import MAIN_JAR as MAIN


def client_command(project,java,distribution,root,cache,shared,port,role,hidden_agent=None,probe=None):
    prepare_client(root,project/'build/libs'/MAIN,probe or project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar')
    (root/'config').mkdir(exist_ok=True);(root/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=true\n}\n')
    extra=['-Dlootablebodies.multiplayer.role='+role,'-Dlootablebodies.multiplayer.sync='+str(shared),
           '-Dlootablebodies.multiplayer.port='+str(port),
           '-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri()]
    if hidden_agent:extra.append('-javaagent:'+str(hidden_agent.resolve()))
    return native_client_command(java,root,distribution,project_assets(project),username='Container'+role,extra=extra)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--forge-client-home',type=Path,required=True)
    parser.add_argument('--hidden-agent',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    java=args.java.resolve();distribution=args.forge_home.resolve();root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
    shared=root/'sync';shared.mkdir();server=root/'server'
    jar=project/'build/libs'/MAIN;before=digest(jar)
    prepare(server,distribution,jar,project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar')
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    properties=(server/'server.properties').read_text().replace('server-port=0','server-port='+str(port))
    (server/'server.properties').write_text(properties+'view-distance=4\n')
    commands={'server':[str(java),'-Xmx1G','-Dlootablebodies.multiplayer.server=true',
        '-Dlootablebodies.multiplayer.sync='+str(shared),'-jar',FORGE+'.jar','--nogui']}
    for role in ('A','B'):
        commands['client'+role]=client_command(project,java,args.forge_client_home.resolve(),root/('client'+role),None,shared,port,role,args.hidden_agent)
    processes={};logs=[]
    def start(name):
        log=(root/(name+'.log')).open('w',encoding='utf-8');logs.append(log)
        processes[name]=subprocess.Popen(commands[name],cwd=server if name=='server' else root/name,stdout=log,stderr=subprocess.STDOUT)
    try:
        start('server');deadline=time.monotonic()+60
        while not (shared/'server.ready').is_file():
            if processes['server'].poll() is not None or time.monotonic()>deadline: raise RuntimeError('Disposable server did not start')
            time.sleep(.2)
        monitor=LoginMonitor(root)
        start_clients(start);deadline=time.monotonic()+180
        while any(p.poll() is None for p in processes.values()):
            monitor.poll()
            if any(p.poll() not in (None,0) for p in processes.values()): raise RuntimeError('Multiplayer process failed')
            if time.monotonic()>deadline: raise RuntimeError('Multiplayer timed out')
            time.sleep(.2)
        if any(p.returncode for p in processes.values()): raise RuntimeError('Multiplayer exit code')
    finally:
        (shared/'abort').touch()
        # Only the three exact processes created above are eligible for termination.
        for process in processes.values():
            if process.poll() is None: process.terminate()
        for process in processes.values():
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill();process.wait()
        for log in logs: log.close()
    if not (shared/'multiplayer.pass').is_file(): raise RuntimeError('Missing multiplayer marker')
    checks=['two_tcp_clients_contested_shift_click','two_tcp_clients_hotbar_interleaved_with_drag',
            'two_tcp_clients_final_gui_and_saved_nbt']
    if (shared/'checks.txt').read_text().splitlines()!=['PASS '+name for name in checks]:
        raise RuntimeError('Missing multiplayer check result')
    write_receipts(root)
    for name in ('server','clientA','clientB'):
        audit(root/(name+'.log'),native=True);audit(root/name/'logs/latest.log',native=True);audit(root/name/'logs/debug.log',native=True)
        if list((root/name).rglob('crash-reports/*')): raise RuntimeError('Crash report '+name)
        if digest(root/name/'mods'/MAIN)!=before: raise RuntimeError('Candidate mismatch '+name)
    for role in ('A','B'):
        home=root/('client'+role)
        if not (home/'multiplayer-client.pass').is_file(): raise RuntimeError('Client did not finish '+role)
        if 'LOOTABLE_BODIES_MULTIPLAYER_CLIENT_PASS role='+role not in (root/('client'+role+'.log')).read_text(errors='replace'):
            raise RuntimeError('Missing client log marker '+role)
        for shot in ('shared-ready.png','shared-final.png'):
            if not (home/'screenshots'/shot).is_file(): raise RuntimeError('Missing screenshot '+role+' '+shot)
    (server/'server.properties').write_text(properties.replace('server-port='+str(port),'server-port=0')+'view-distance=4\n')
    archive_live_logs(root)
    run(java,server,'multiplayer-reload',message='LOOTABLE_BODIES_RUNTIME_PASS phase=multiplayer-reload')
    if digest(jar)!=before: raise RuntimeError('Candidate changed')
    (root/'result.json').write_text(json.dumps({'candidate_sha256':before,'two_real_tcp_clients':True,
        'checks':(shared/'checks.txt').read_text().splitlines(),'corpse_and_real_player_disk_reload':True},indent=2)+'\n')
    print('MULTIPLAYER_PACKAGED_PASS',flush=True)


if __name__=='__main__':main()
