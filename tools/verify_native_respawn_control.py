"""Exercise native37 delayed respawns/FIFO packets without Lootable Bodies."""
import argparse,json,socket,subprocess,time,os,re
from pathlib import Path
from audit_runtime_logs import audit, forge36_dead_player_movement_blocks
from native_runtime import prepare_server,prepare_client,client_command,project_assets,FORGE,digest,seed_flat_fixture
from owned_process import native_process

def control_error_counts(path):
    """Retained native36 log-fixture contract; never used to qualify native37."""
    report=audit(path,native=True)
    _,count=forge36_dead_player_movement_blocks(path.read_text(errors='replace').splitlines(),position_only=True)
    if count!=8:raise RuntimeError('Native control did not reproduce eight delayed position packets')
    return count,report['known_forge36_dead_player_movement_failures']-count


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
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
    probe=project/'build/control-test-mod/forge-platform-control-reobf.jar'
    prepare_server(root/'server',args.forge_home.resolve(),probe=probe)
    seed_flat_fixture(root/'server',args.flat_header,args.flat_header_sha256)
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
        '-Dlootablebodies.control.deaths=true',
        '-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri(),
        '@libraries/net/minecraftforge/forge/1.17.1-37.1.1/'+('win_args.txt' if os.name=='nt' else 'unix_args.txt'),'--nogui'],
        'client':client_command(args.java.resolve(),root/'client',args.forge_client_home.resolve(),
                                args.assets.resolve() if args.assets else project_assets(project),username='ControlOnly',extra=extra,prepared=args.client_inputs.resolve())}
    processes={};logs=[]
    def start(name):
        processes[name]=native_process(commands[name],cwd=root/name,log=root/(name+'.log'),
            timeout=420 if name=='server' else 240,console_input=name=='server')
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
    server=(root/'server.log').read_text(errors='replace')
    batches=[int(value) for value in re.findall(r'NATIVE_FIFO_DELAY_RELEASE packets=([0-9]+)',client)]
    if server.count('NATIVE_DEATH ')!=8 or len(batches)!=8 or sum(batches)<=0:
        raise RuntimeError('Native37 control did not exercise all eight real delayed respawn/FIFO actions')
    result={'lootable_bodies_installed':False,'respawns':8,'native37_delayed_fifo_batches':batches,
        'legacy36_error_matcher_used':False,'native37_complete_logs_clean':True,
        'control_jar_sha256':digest(probe),'native_outbound_packets_delayed_fifo':True,'log_audits':reports}
    (root/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print('NATIVE_RESPAWN_CONTROL_PASS',json.dumps(result),flush=True)


if __name__=='__main__':main()
