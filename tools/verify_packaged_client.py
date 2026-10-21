"""Run the exact production jar and build-only verifier in a vanilla Forge client."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from audit_runtime_logs import audit
from release_identity import MAIN_JAR


def prepare_assets(folder):
    folder.mkdir(parents=True,exist_ok=True)
    (folder/'indexes').mkdir(exist_ok=True)
    data=urllib.request.urlopen('https://piston-meta.mojang.com/v1/packages/7c2800b458376b8fc0b738382fb7784328fddda9/1.10.json').read()
    if hashlib.sha1(data).hexdigest()!='7c2800b458376b8fc0b738382fb7784328fddda9': raise RuntimeError('Asset index hash mismatch')
    (folder/'indexes/1.10.json').write_bytes(data)
    def fetch(obj):
        sha=obj['hash']; path=folder/'objects'/sha[:2]/sha
        if path.is_file() and hashlib.sha1(path.read_bytes()).hexdigest()==sha: return
        payload=urllib.request.urlopen('https://resources.download.minecraft.net/'+sha[:2]+'/'+sha,timeout=45).read()
        if len(payload)!=obj['size'] or hashlib.sha1(payload).hexdigest()!=sha: raise RuntimeError('Asset hash mismatch '+sha)
        path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(payload)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(fetch,json.loads(data)['objects'].values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--gradle-home',type=Path,required=True)
    args=parser.parse_args()
    project=Path(__file__).resolve().parents[1]
    root=args.output.resolve()
    if root.exists(): raise RuntimeError('Preserve previous evidence; use a new output directory')
    root.mkdir(parents=True)
    (root/'mods').mkdir(); (root/'config').mkdir()
    main=project/'build/libs'/MAIN_JAR
    probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    candidate_hash=hashlib.sha256(main.read_bytes()).hexdigest().upper()
    for jar in [main,probe]: shutil.copy2(jar,root/'mods'/jar.name)
    if hashlib.sha256((root/'mods'/main.name).read_bytes()).hexdigest().upper()!=candidate_hash:
        raise RuntimeError('Candidate copy hash mismatch')
    (root/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=true\n}\n')
    cache=args.gradle_home.resolve()/'caches'
    vanilla=cache/'minecraftforge/forgegradle/mavenizer/caches/minecraft_tasks/1.10.2/client.jar'
    if hashlib.sha1(vanilla.read_bytes()).hexdigest()!='dc8e75ac7274ff6af462b0dcec43c307de668e40':
        raise RuntimeError('Vanilla Minecraft 1.10.2 client hash mismatch')
    # A pinned index avoids other Minecraft versions replacing a shared generic index.
    assets=project/'.gradle/packaged-assets'
    prepare_assets(assets)
    natives=cache/'minecraftforge/forgegradle/slime-launcher/cache/net/minecraftforge/forge/1.10.2-12.18.3.2511/natives'
    dependencies=[Path(p) for p in json.loads((project/'build/runtime-classpath.json').read_text())]
    # Only official runtime libraries; the mapped Forge jar and development mappings stay out.
    dependencies=[p for p in dependencies if p.suffix=='.jar' and 'forge-1.10.2-12.18.3.2511-stable' not in p.name]
    if any(any(token in str(p).lower() for token in ['junit','mockito','test-mod','slime-launcher']) for p in dependencies):
        raise RuntimeError('Testing/development library in packaged classpath')
    forge=root/args.forge_jar.name
    shutil.copy2(args.forge_jar,forge)
    command=[str(args.java.resolve()),'-Xmx2G','-Dlootablebodies.clientCheck=true','-Dlog4j.skipJansi=true',
             '-Djava.library.path='+str(natives),'-Dorg.lwjgl.librarypath='+str(natives),
             '-Dlog4j.configurationFile='+str(project/'src/integrationTest/resources/log4j2.xml'),
             # Vanilla client precedes Forge's server-oriented manifest Class-Path.
             '-cp',os.pathsep.join(map(str,[vanilla,forge]+dependencies)),
             'net.minecraft.launchwrapper.Launch','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
             '--username','BuildClient','--uuid','00000000000040008000000000000104','--accessToken','0',
             '--version','1.10.2','--userType','legacy','--gameDir',str(root),
             '--assetsDir',str(assets),'--assetIndex','1.10','--width','854','--height','480']
    with (root/'client.log').open('w',encoding='utf-8') as log:
        result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    text=(root/'client.log').read_text(errors='replace')
    if result.returncode or not (root/'client.pass').is_file() or 'LOOTABLE_BODIES_CLIENT_PASS' not in text:
        raise RuntimeError('Packaged client failed: '+str(root/'client.log'))
    if any(token in text for token in ['AssertionError','NoSuchMethodError','NoClassDefFoundError','[Client thread/FATAL]',"Couldn't render entity",'Missing sound for event:','Patcher expecting empty class data']):
        raise RuntimeError('Client log contains a runtime failure')
    if list((root/'crash-reports').glob('*')): raise RuntimeError('Client crash reports')
    audit(root/'client.log')
    checks=['connected_player_death_and_respawn','network_shift_click_exact_nbt',
            'first_save_reload_corpse_and_player','network_normal_click_exact_nbt',
            'second_save_reload_corpse_and_player']
    if (root/'connected-checks.txt').read_text().splitlines()!=['PASS '+name for name in checks]:
        raise RuntimeError('Missing connected-player client checks')
    drop_checks=['network_accepted_throw','network_cancelled_throw_source_resync',
        'network_rejected_outside_cursor_resync','network_accepted_outside_drop','network_double_click_exact_nbt',
        'network_creative_clone_and_fill','creative_cancelled_close_full_inventory_disk_reload']
    if (root/'drop-client-checks.txt').read_text().splitlines()!=['PASS '+name for name in drop_checks]:
        raise RuntimeError('Missing connected drop/creative client checks')
    nbt_checks=['malformed_disk_load_gui_exact_inventory','malformed_network_recovery_preserves_raw_records',
        'malformed_corpse_and_player_disk_reload']
    if (root/'nbt-client-checks.txt').read_text().splitlines()!=['PASS '+name for name in nbt_checks]:
        raise RuntimeError('Missing malformed NBT graphical recovery checks')
    screenshots=['nbt-recovered-corpse.png','nbt-after-recovery-reload.png','drop-cancelled-source-resync.png','drop-rejected-cursor-resync.png','drop-creative-filled.png','drop-creative-final-reload.png','corpse-gui-fresh.png','corpse-gui-reload.png','connected-player-death.png',
        'connected-corpse-before-recovery.png','connected-corpse-after-shift-recovery.png',
        'connected-corpse-first-reload.png','connected-corpse-after-normal-recovery.png',
        'connected-corpse-final-reload.png']
    for name in screenshots:
        if not (root/'screenshots'/name).is_file() or (root/'screenshots'/name).stat().st_size==0:
            raise RuntimeError('Missing client screenshot '+name)
    if hashlib.sha256(main.read_bytes()).hexdigest().upper()!=candidate_hash:
        raise RuntimeError('Candidate changed during graphical checks')
    (root/'result.json').write_text(json.dumps({'candidate_sha256':candidate_hash,
        'fresh_and_reload_gui':True,'connected_player_checks':checks,'drop_creative_checks':drop_checks,'nbt_checks':nbt_checks,'screenshots':screenshots},indent=2)+'\n')
    print('PACKAGED_CLIENT_PASS',flush=True)


if __name__=='__main__': main()
