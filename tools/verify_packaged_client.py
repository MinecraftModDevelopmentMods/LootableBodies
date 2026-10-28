"""Run the exact production jar and isolated verifier in official Forge 36."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from audit_runtime_logs import audit
from native_runtime import project_assets,prepare_client,client_command
from release_identity import MAIN_JAR


def audit_client(root,console):
    for log in [root/console,root/'logs/latest.log',root/'logs/debug.log']:
        if not log.is_file():raise FileNotFoundError(log)
        audit(log,native=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--forge-client-home',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--hidden-agent',type=Path)
    parser.add_argument('--mode',choices=['default','config','skin','issues','names'],default='default')
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    root=args.output.resolve();main=project/'build/libs'/MAIN_JAR
    probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    candidate_hash=hashlib.sha256(main.read_bytes()).hexdigest().upper()
    prepare_client(root,main,probe);(root/'config').mkdir(exist_ok=True)
    # Inventory/death fixtures exercise gameplay, independent of live skin HTTP.
    # The dedicated skin and config modes still exercise the player-skin path.
    local='true' if args.mode in ('default','names','issues') else 'false'
    (root/'config/lootablebodies.cfg').write_text('options {\n B:local_texture='+local+'\n}\n')
    extra=['-Dlootablebodies.clientCheck=true','-Dlog4j.configurationFile='+(project/'src/integrationTest/resources/log4j2.xml').as_uri()]
    if args.hidden_agent:extra.append('-javaagent:'+str(args.hidden_agent.resolve()))
    if args.mode!='default':
        option={'config':'configUiCheck','skin':'visibilitySkinCheck','issues':'issueClientCheck','names':'namelessClientCheck'}[args.mode]
        extra.append('-Dlootablebodies.'+option+'=true')
    command=client_command(args.java.resolve(),root,args.forge_client_home.resolve(),project_assets(project),extra=extra)
    with (root/'client.log').open('w',encoding='utf-8') as log:
        result=subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    text=(root/'client.log').read_text(errors='replace')
    expected={'default':'LOOTABLE_BODIES_CLIENT_PASS','config':'LOOTABLE_BODIES_CONFIG_CLIENT_PASS',
              'skin':'LOOTABLE_BODIES_VISIBILITY_SKIN_PASS','issues':'LOOTABLE_BODIES_ISSUE5_CLIENT_PASS','names':'LOOTABLE_BODIES_NAME_TRANSLATION_PASS'}[args.mode]
    if result.returncode or not (root/'client.pass').is_file() or expected not in text:
        raise RuntimeError('Packaged client failed: '+str(root/'client.log'))
    if list((root/'crash-reports').glob('*')): raise RuntimeError('Client crash reports')
    audit_client(root,'client.log')
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
            audit_client(root,'restart.log')
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
