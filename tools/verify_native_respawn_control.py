"""Reproduce the pinned Forge 31 delayed-movement error without Lootable Bodies."""
import argparse,json,socket,subprocess,time
from pathlib import Path
from audit_runtime_logs import audit
from native_runtime import prepare_server,prepare_client,client_command,project_assets,FORGE,digest


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--forge-client-home',type=Path,required=True)
    parser.add_argument('--hidden-agent',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
    probe=project/'build/control-test-mod/forge-platform-control-reobf.jar'
    prepare_server(root/'server',args.forge_home.resolve(),probe=probe)
    prepare_client(root/'client',probe=probe)
    for home in (root/'server',root/'client'):
        if list((home/'mods').glob('*.jar'))!=[home/'mods'/probe.name]:
            raise RuntimeError('Independent control must contain only its own verifier')
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    props=root/'server/server.properties'
    props.write_text(props.read_text().replace('server-port=0','server-port='+str(port))+'view-distance=4\n')
    extra=['-Dlootablebodies.control.deaths=true','-Dlootablebodies.control.port='+str(port),
           '-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri()]
    if args.hidden_agent:extra.append('-javaagent:'+str(args.hidden_agent.resolve()))
    commands={'server':[str(args.java.resolve()),'-Xmx1G','-Dlootablebodies.control.tcpServer=true',
        '-Dlootablebodies.control.deaths=true','-jar',FORGE+'.jar','--nogui'],
        'client':client_command(args.java.resolve(),root/'client',args.forge_client_home.resolve(),
                                project_assets(project),username='ControlOnly',extra=extra)}
    processes={};logs=[]
    def start(name):
        log=(root/(name+'.log')).open('w');logs.append(log)
        processes[name]=subprocess.Popen(commands[name],cwd=root/name,stdin=subprocess.PIPE,
            stdout=log,stderr=subprocess.STDOUT,text=True)
    try:
        start('server');deadline=time.monotonic()+120
        while 'FORGE_PLATFORM_CONTROL_READY' not in (root/'server.log').read_text(errors='replace'):
            if processes['server'].poll() is not None or time.monotonic()>deadline:raise RuntimeError('Native control server did not start')
            time.sleep(.2)
        start('client');processes['client'].wait(timeout=240)
        client=(root/'client.log').read_text(errors='replace')
        if processes['client'].returncode or 'FORGE_PLATFORM_CONTROL_PASS' not in client or client.count('NATIVE_RESPAWN ')!=8:
            raise RuntimeError('Native control did not complete eight delayed respawns')
        processes['server'].stdin.write('stop\n');processes['server'].stdin.flush();processes['server'].wait(timeout=60)
        if processes['server'].returncode:raise RuntimeError('Native control server exit failure')
    finally:
        for process in processes.values():
            if process.poll() is None:process.terminate()
        for process in processes.values():
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for log in logs:log.close()
    reports=[]
    for name in ('server','client'):
        if list((root/name).rglob('crash-reports/*.txt')):raise RuntimeError('Unexpected native control crash')
        for path in (root/(name+'.log'),root/name/'logs/latest.log',root/name/'logs/debug.log'):
            if not path.is_file():raise FileNotFoundError(path)
            reports.append(audit(path,native=True))
    count=reports[0]['known_forge31_dead_player_movement_failures']
    if count<1 or 'NATIVE_FIFO_DELAY_RELEASE packets=' not in client:
        raise RuntimeError('Native control did not reproduce the expected upstream error')
    result={'lootable_bodies_installed':False,'respawns':8,'native_error_reproductions':count,
        'control_jar_sha256':digest(probe),'native_outbound_packets_delayed_fifo':True,'log_audits':reports}
    (root/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print('NATIVE_RESPAWN_CONTROL_PASS',json.dumps(result),flush=True)


if __name__=='__main__':main()
