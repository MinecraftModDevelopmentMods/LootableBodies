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
from verify_packaged import MAIN, FORGE, digest, prepare, run
from verify_packaged_client import prepare_assets


def client_command(project, java, distribution, root, cache, shared, port, role):
    root.mkdir()
    (root/'mods').mkdir(); (root/'config').mkdir()
    for path in (project/'build/libs'/MAIN, project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'):
        shutil.copy2(path,root/'mods'/path.name)
    (root/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=true\n}\n')
    forge=distribution/(FORGE+'-universal.jar');shutil.copy2(forge,root/forge.name)
    vanilla=cache/'minecraftforge/forgegradle/mavenizer/caches/minecraft_tasks/1.10.2/client.jar'
    if hashlib.sha1(vanilla.read_bytes()).hexdigest()!='dc8e75ac7274ff6af462b0dcec43c307de668e40': raise RuntimeError('Vanilla client hash')
    natives=cache/'minecraftforge/forgegradle/slime-launcher/cache/net/minecraftforge/forge/1.10.2-12.18.3.2511/natives'
    deps=[Path(p) for p in json.loads((project/'build/runtime-classpath.json').read_text())]
    deps=[p for p in deps if p.suffix=='.jar' and 'forge-1.10.2-12.18.3.2511-stable' not in p.name]
    if any(any(s in str(p).lower() for s in ('junit','mockito','test-mod','slime-launcher')) for p in deps): raise RuntimeError('Foreign packaged runtime library')
    return [str(java),'-Xmx1G','-Dlog4j.skipJansi=true','-Dlootablebodies.multiplayer.role='+role,
        '-Dlootablebodies.multiplayer.sync='+str(shared),'-Dlootablebodies.multiplayer.port='+str(port),
        '-Djava.library.path='+str(natives),'-Dorg.lwjgl.librarypath='+str(natives),
        '-Dlog4j.configurationFile='+str(project/'src/integrationTest/resources/log4j2.xml'),
        '-cp',os.pathsep.join(map(str,[vanilla,root/forge.name]+deps)),
        'net.minecraft.launchwrapper.Launch','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
        '--username','Container'+role,'--uuid','0000000000004000800000000000010'+('5' if role=='A' else '6'),
        '--accessToken','0','--version','1.10.2','--userType','legacy','--gameDir',str(root),
        '--assetsDir',str(project/'.gradle/packaged-assets'),'--assetIndex','1.10','--width','854','--height','480']


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--gradle-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    java=args.java.resolve();distribution=args.forge_home.resolve();root=args.output.resolve();root.mkdir(parents=True,exist_ok=False)
    if digest(distribution/(FORGE+'-universal.jar'))!='809C2EA5B4D4F3E5A294D3A326744F20FEC412A1263E708297D00F512E377542': raise RuntimeError('Forge hash')
    shared=root/'sync';shared.mkdir();server=root/'server'
    jar=project/'build/libs'/MAIN;before=digest(jar)
    prepare(server,distribution,jar,project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar')
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    properties=(server/'server.properties').read_text().replace('server-port=0','server-port='+str(port))
    (server/'server.properties').write_text(properties+'view-distance=4\n')
    prepare_assets(project/'.gradle/packaged-assets')
    commands={'server':[str(java),'-Xmx1G','-Dlootablebodies.multiplayer.server=true',
        '-Dlootablebodies.multiplayer.sync='+str(shared),'-jar',FORGE+'-universal.jar','nogui']}
    for role in ('A','B'):
        commands['client'+role]=client_command(project,java,distribution,root/('client'+role),args.gradle_home.resolve()/'caches',shared,port,role)
    processes={};logs=[]
    def start(name):
        log=(root/(name+'.log')).open('w',encoding='utf-8');logs.append(log)
        processes[name]=subprocess.Popen(commands[name],cwd=server if name=='server' else root/name,stdout=log,stderr=subprocess.STDOUT)
    try:
        start('server');deadline=time.monotonic()+60
        while not (shared/'server.ready').is_file():
            if processes['server'].poll() is not None or time.monotonic()>deadline: raise RuntimeError('Disposable server did not start')
            time.sleep(.2)
        start('clientA');start('clientB');deadline=time.monotonic()+180
        while any(p.poll() is None for p in processes.values()):
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
    for name in ('server','clientA','clientB'):
        audit(root/(name+'.log'));audit(root/name/'logs/latest.log')
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
    run(java,server,'multiplayer-reload')
    if digest(jar)!=before: raise RuntimeError('Candidate changed')
    (root/'result.json').write_text(json.dumps({'candidate_sha256':before,'two_real_tcp_clients':True,
        'checks':(shared/'checks.txt').read_text().splitlines(),'corpse_and_real_player_disk_reload':True},indent=2)+'\n')
    print('MULTIPLAYER_PACKAGED_PASS',flush=True)


if __name__=='__main__':main()
