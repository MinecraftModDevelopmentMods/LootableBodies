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
from client_runtime import assemble_libraries
from release_identity import MAIN_JAR


def prepare_assets(folder):
    folder.mkdir(parents=True,exist_ok=True)
    (folder/'indexes').mkdir(exist_ok=True)
    data=urllib.request.urlopen('https://piston-meta.mojang.com/v1/packages/a21e1ded1a24ea1548dd8db0cf30b6acb02655a9/1.12.json').read()
    if hashlib.sha1(data).hexdigest()!='a21e1ded1a24ea1548dd8db0cf30b6acb02655a9': raise RuntimeError('Asset index hash mismatch')
    (folder/'indexes/1.12.json').write_bytes(data)
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
    parser.add_argument('--mode',choices=['default','config','skin','issues','names'],default='default')
    args=parser.parse_args()
    project=Path(__file__).resolve().parents[1]
    root=args.output.resolve()
    if root.exists(): raise RuntimeError('Preserve previous evidence; use a new output directory')
    root.mkdir(parents=True)
    (root/'mods').mkdir(); (root/'config').mkdir()
    (root/'options.txt').write_text('realmsNotifications:false\n')
    main=project/'build/libs'/MAIN_JAR
    probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    candidate_hash=hashlib.sha256(main.read_bytes()).hexdigest().upper()
    for jar in [main,probe]: shutil.copy2(jar,root/'mods'/jar.name)
    if hashlib.sha256((root/'mods'/main.name).read_bytes()).hexdigest().upper()!=candidate_hash:
        raise RuntimeError('Candidate copy hash mismatch')
    (root/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=true\n}\n')
    if args.mode not in ('default','names'):
        (root/'config/lootablebodies.cfg').write_text('options {\n B:local_texture=false\n}\n')
    cache=args.gradle_home.resolve()/'caches'
    vanilla=cache/'minecraftforge/forgegradle/mavenizer/caches/minecraft_tasks/1.12.2/client.jar'
    if hashlib.sha1(vanilla.read_bytes()).hexdigest()!='0f275bc1547d01fa5f56ba34bdc87d981ee12daf':
        raise RuntimeError('Vanilla Minecraft 1.12.2 client hash mismatch')
    # A pinned index avoids other Minecraft versions replacing a shared generic index.
    assets=project/'.gradle/packaged-assets'
    prepare_assets(assets)
    natives=cache/'minecraftforge/forgegradle/slime-launcher/cache/net/minecraftforge/forge/1.12.2-14.23.5.2859/natives'
    dependencies=[Path(p) for p in json.loads((project/'build/runtime-classpath.json').read_text())]
    # Only official runtime libraries; the mapped Forge jar and development mappings stay out.
    dependencies=assemble_libraries(dependencies,root)
    if any(any(token in str(p).lower() for token in ['junit','mockito','test-mod','slime-launcher']) for p in dependencies):
        raise RuntimeError('Testing/development library in packaged classpath')
    forge=root/args.forge_jar.name
    shutil.copy2(args.forge_jar,forge)
    command=[str(args.java.resolve()),'-Xmx2G','-Dlootablebodies.clientCheck=true','-Dlog4j.skipJansi=true',
             '-Djava.library.path='+str(natives),'-Dorg.lwjgl.librarypath='+str(natives),
             '-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri(),
             # Vanilla client precedes Forge's server-oriented manifest Class-Path.
             '-cp',os.pathsep.join(map(str,[vanilla,forge]+dependencies)),
             'net.minecraft.launchwrapper.Launch','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
             '--username','BuildClient','--uuid','00000000000040008000000000000104','--accessToken','0',
             '--version','1.12.2','--userType','legacy','--gameDir',str(root),
             '--assetsDir',str(assets),'--assetIndex','1.12','--width','854','--height','480']
    if args.mode!='default':
        option={'config':'configUiCheck','skin':'visibilitySkinCheck','issues':'issueClientCheck','names':'namelessClientCheck'}[args.mode]
        command.insert(2,'-Dlootablebodies.'+option+'=true')
    with (root/'client.log').open('w',encoding='utf-8') as log:
        result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    text=(root/'client.log').read_text(errors='replace')
    expected={'default':'LOOTABLE_BODIES_CLIENT_PASS','config':'LOOTABLE_BODIES_CONFIG_UI_PASS',
              'skin':'LOOTABLE_BODIES_VISIBILITY_SKIN_PASS','issues':'LOOTABLE_BODIES_ISSUE5_CLIENT_PASS','names':'LOOTABLE_BODIES_NAME_TRANSLATION_PASS'}[args.mode]
    if result.returncode or not (root/'client.pass').is_file() or expected not in text:
        raise RuntimeError('Packaged client failed: '+str(root/'client.log'))
    if any(token in text for token in ['AssertionError','NoSuchMethodError','NoClassDefFoundError','[Client thread/FATAL]',"Couldn't render entity",'Missing sound for event:','Patcher expecting empty class data']):
        raise RuntimeError('Client log contains a runtime failure')
    if list((root/'crash-reports').glob('*')): raise RuntimeError('Client crash reports')
    audit(root/'client.log')
    if args.mode!='default':
        reports={'config':'config-checks.txt','skin':'skin-checks.txt','issues':'issue5-client-checks.txt','names':'name-checks.txt'}
        report=root/reports[args.mode]
        if not report.is_file() or not report.read_text().strip() or any(
                not line.startswith('PASS ') for line in report.read_text().splitlines()):
            raise RuntimeError('Missing graphical mode assertions: '+str(report))
        if args.mode=='config':
            command.insert(2,'-Dlootablebodies.configUiReload=true')
            with (root/'restart.log').open('w',encoding='utf-8') as log:
                restart=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=120)
            if restart.returncode or not (root/'config-restart.pass').is_file():
                raise RuntimeError('Configuration did not survive process restart')
            audit(root/'restart.log')
        if list((root/'crash-reports').glob('*')) or hashlib.sha256(main.read_bytes()).hexdigest().upper()!=candidate_hash:
            raise RuntimeError('Crash or candidate mutation in graphical mode')
        (root/'result.json').write_text(json.dumps({'candidate_sha256':candidate_hash,'mode':args.mode,
            'checks':report.read_text().splitlines(),'screenshots':sorted(p.name for p in (root/'screenshots').glob('*.png')),
            'computer_control':'none'},indent=2)+'\n')
        print('PACKAGED_CLIENT_MODE_PASS',args.mode,flush=True)
        return
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
