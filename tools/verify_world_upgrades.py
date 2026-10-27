"""Sealed historical-writer fixtures and exact packaged forward-upgrade matrix."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import gzip
import struct
from audit_runtime_logs import audit, FORGE_UPGRADE_RENAME
from release_identity import MAIN_JAR
from nbt_regions import Tag, decode, encode, read_region, write_disposable_region

ORIGINS = {
    'original-110': ('1.10.2', '2.2.0', '688AFBBF4485D1F3746C68903C9E0885D57F42CCFBFA0CB4A0FC42982C0B3F2B'),
    'released-110': ('1.10.2', '2.4.1.110021', '7F9F0EE7BF09F943ED85398982EB7C2207F3DD55B75E9E92A7531C393BE1045B'),
    'original-111': ('1.11.2', '2.3.0', 'C82CC329BA53A218CADB681D646F64A9C457341577BC4F9AA07E2F9926434CCF'),
    'recovery-110': ('1.10.2', '2.4.1.110021', '7F9F0EE7BF09F943ED85398982EB7C2207F3DD55B75E9E92A7531C393BE1045B'),
}
ORIGINS.update({
    'released-114': ('1.14.4', '2.4.5.114041', '1C4C153A2F67B9F0BC85913A33FBBC9575C4C6EF8EF49DF901120DF811484E7D'),
    'recovery-114': ('1.14.4', '2.4.5.114041', '1C4C153A2F67B9F0BC85913A33FBBC9575C4C6EF8EF49DF901120DF811484E7D'),
    'via114-110': ('1.10.2', '2.2.0', '688AFBBF4485D1F3746C68903C9E0885D57F42CCFBFA0CB4A0FC42982C0B3F2B'),
    'via114-111': ('1.11.2', '2.4.2.111021', '59ACF5652505F08A7F011307B3CD68F3754FCFC14C4C330BD952475351AEC932'),
    'via114-112': ('1.12.2', '2.4.3.112021', 'A9F4B115828834F60C5905526A8A99794B1DFE4802AB4D48858F2D597F3FCBCA'),
    'via114-113': ('1.13.2', '2.4.4.113021', '464CC1CDDA6C04CD72F5F1A2A504EB8A665E6E48AC255918437262DB2A22A4C1'),
    'via113-110': ('1.10.2', '2.2.0', '688AFBBF4485D1F3746C68903C9E0885D57F42CCFBFA0CB4A0FC42982C0B3F2B'),
    'via113-111': ('1.11.2', '2.4.2.111021', '59ACF5652505F08A7F011307B3CD68F3754FCFC14C4C330BD952475351AEC932'),
    'via113-112': ('1.12.2', '2.4.3.112021', 'A9F4B115828834F60C5905526A8A99794B1DFE4802AB4D48858F2D597F3FCBCA'),
    'released-113': ('1.13.2', '2.4.4.113021', '464CC1CDDA6C04CD72F5F1A2A504EB8A665E6E48AC255918437262DB2A22A4C1'),
    'recovery-113': ('1.13.2', '2.4.4.113021', '464CC1CDDA6C04CD72F5F1A2A504EB8A665E6E48AC255918437262DB2A22A4C1'),
    'via112-110': ('1.10.2','2.2.0','688AFBBF4485D1F3746C68903C9E0885D57F42CCFBFA0CB4A0FC42982C0B3F2B'),
    'via112-111': ('1.11.2','2.4.2.111021','59ACF5652505F08A7F011307B3CD68F3754FCFC14C4C330BD952475351AEC932'),
    'released-112': ('1.12.2','2.4.3.112021','A9F4B115828834F60C5905526A8A99794B1DFE4802AB4D48858F2D597F3FCBCA'),
    'recovery-112': ('1.12.2','2.4.3.112021','A9F4B115828834F60C5905526A8A99794B1DFE4802AB4D48858F2D597F3FCBCA'),
    'via111-110': ('1.10.2','2.2.0','688AFBBF4485D1F3746C68903C9E0885D57F42CCFBFA0CB4A0FC42982C0B3F2B'),
    'released-111': ('1.11.2', '2.4.2.111021', '59ACF5652505F08A7F011307B3CD68F3754FCFC14C4C330BD952475351AEC932'),
    'original-112': ('1.12.2', '2.4.0', 'DE701F657A03BFFE08D6F9FEB1516B37B23B0171B91716087C7C84D9C19B9B5C'),
    'recovery-111': ('1.11.2', '2.4.2.111021', '59ACF5652505F08A7F011307B3CD68F3754FCFC14C4C330BD952475351AEC932'),
})
FORGE = {'1.12.2': 'forge-1.12.2-14.23.5.2859','1.10.2': 'forge-1.10.2-12.18.3.2511', '1.11.2': 'forge-1.11.2-13.20.1.2588'}
FORGE_SHA = {'1.12.2': 'BD8314729D787E35164476D75AFB337AFB9783917E4F6FA1F77F049ADBA22F2D','1.10.2': '809C2EA5B4D4F3E5A294D3A326744F20FEC412A1263E708297D00F512E377542',
             '1.11.2': 'B6DFF35B5CCB0E6353FB89B5330FE930DB8FBE8B63787C9FC2F70196BD108998'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fingerprint(path):
    return {p.relative_to(path).as_posix(): digest(p) for p in sorted(path.rglob('*')) if p.is_file()}


def prepare(home, distribution, minecraft, mod, probe):
    home.mkdir(parents=True, exist_ok=False)
    (home / 'mods').mkdir()
    forge = FORGE[minecraft] + '-universal.jar'
    if digest(distribution / forge) != FORGE_SHA[minecraft]:
        raise RuntimeError('Incorrect Forge distribution')
    for name in [forge, f'minecraft_server.{minecraft}.jar']:
        shutil.copy2(distribution / name, home / name)
    shutil.copytree(distribution / 'libraries', home / 'libraries')
    shutil.copy2(mod, home / 'mods' / mod.name)
    shutil.copy2(probe, home / 'mods' / probe.name)
    (home / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (home / 'server.properties').write_text('level-name=world\nlevel-seed=4267321\nlevel-type=FLAT\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=0\ngenerate-structures=false\nspawn-animals=false\nspawn-monsters=false\nspawn-protection=0\nmax-tick-time=120000\n', encoding='utf-8')
    for name in ['ops', 'whitelist', 'banned-players', 'banned-ips']:
        (home / (name + '.json')).write_text('[]\n', encoding='utf-8')


def seed(java, home, minecraft, version, recovery=False):
    with (home / 'seed.log').open('w', encoding='utf-8') as log:
        completed = subprocess.run([str(java), '-Xmx2G', '-Dlootablebodies.fixture.version=' + version,
                                    '-Dlootablebodies.fixture.recovery=' + str(recovery).lower(),
                                    '-jar', FORGE[minecraft] + '-universal.jar', 'nogui'], cwd=home,
                                   stdout=log, stderr=subprocess.STDOUT, timeout=240)
    log = home / 'seed.log'
    text = log.read_text(encoding='utf-8', errors='replace')
    expected = f'LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft={minecraft} mod={version} corpses=9 dimensions=3'
    if completed.returncode or expected not in text or not (home / 'world/legacy-seed.pass').is_file():
        raise RuntimeError(f'Historical writer failed: {log}')
    if list((home / 'crash-reports').glob('*')):
        raise RuntimeError(f'Historical writer crashed: {home}')
    audit(log)
    return fingerprint(home / 'world')


def seed_native113(java, home, recovery=False):
    from prepare_native113_tools import historical_runtime
    runtime = historical_runtime(Path(__file__).resolve().parents[1])
    with (home/'seed.log').open('w', encoding='utf-8') as log:
        result = subprocess.run([str(java), '-Xmx2G',
            '-Dlootablebodies.fixture.version=2.4.4.113021',
            '-Dlootablebodies.fixture.recovery='+str(recovery).lower(),
            '-jar', runtime.FORGE+'.jar', '--nogui'], cwd=home,
            stdout=log, stderr=subprocess.STDOUT, timeout=240)
    expected = 'LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft=1.13.2 mod=2.4.4.113021 corpses=9 dimensions=3'
    if result.returncode or expected not in (home/'seed.log').read_text(errors='replace') or not (home/'world/legacy-seed.pass').is_file():
        raise RuntimeError('Released 1.13 writer failed: '+str(home/'seed.log'))
    if list((home/'crash-reports').glob('*')):
        raise RuntimeError('Released 1.13 writer crashed')
    for path in [home/'seed.log', home/'logs/latest.log', home/'logs/debug.log']:
        audit(path, native=True)
    return fingerprint(home/'world')


def seed_native114(java, home, recovery=False):
    from prepare_native114_tools import historical_runtime
    runtime=historical_runtime(Path(__file__).resolve().parents[1])
    with (home/'seed.log').open('w', encoding='utf-8') as log:
        result=subprocess.run([str(java), '-Xmx2G',
            '-Dlootablebodies.fixture.version=2.4.5.114041',
            '-Dlootablebodies.fixture.recovery='+str(recovery).lower(),
            '-jar', runtime.FORGE+'.jar', '--nogui'], cwd=home,
            stdout=log, stderr=subprocess.STDOUT, timeout=240)
    expected='LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft=1.14.4 mod=2.4.5.114041 corpses=9 dimensions=3'
    if result.returncode or expected not in (home/'seed.log').read_text(errors='replace') or not (home/'world/legacy-seed.pass').is_file():
        raise RuntimeError('Released 1.14 writer failed: '+str(home/'seed.log'))
    if list((home/'crash-reports').glob('*')):
        raise RuntimeError('Released 1.14 writer crashed')
    for path in [home/'seed.log', home/'logs/latest.log', home/'logs/debug.log']:
        audit(path, native=True)
    return fingerprint(home/'world')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--forge110-home', type=Path, required=True)
    parser.add_argument('--forge111-home', type=Path, required=True)
    parser.add_argument('--forge112-home', type=Path, required=True)
    parser.add_argument('--forge113-home', type=Path)
    parser.add_argument('--forge114-home', type=Path)
    parser.add_argument('--forge115-home', type=Path)
    for origin in ORIGINS:
        if origin.startswith('recovery-') or origin.startswith('via'):
            continue
        parser.add_argument('--' + origin, type=Path, required=True)
    parser.add_argument('--origins', nargs='+', choices=list(ORIGINS), default=list(ORIGINS))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seed-only', action='store_true')
    parser.add_argument('--seeds', nargs='+', type=Path)
    parser.add_argument('--variants', nargs='+', choices=['normal', 'stamps'], default=['normal', 'stamps'])
    parser.add_argument('--control-report', type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    if not args.seed_only:
        verify(args, project)
        return
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    seeder = project / 'build/legacy-seed/lootablebodies-world-fixtures-reobf.jar'
    report = {'seeder_sha256': digest(seeder), 'origins': {}}
    for origin, (minecraft, version, expected) in ORIGINS.items():
        if origin not in args.origins:
            continue
        if origin.startswith('via114-'):
            original=getattr(args, 'original_110' if minecraft=='1.10.2' else 'released_'+origin[-3:]).resolve()
            if digest(original)!=expected:
                raise RuntimeError('Incorrect historical source jar for '+origin)
            source_home=root/(origin+'-source')
            if minecraft=='1.13.2':
                from prepare_native113_tools import historical_runtime
                runtime=historical_runtime(project)
                if not args.forge113_home: raise ValueError('--forge113-home is required')
                runtime.prepare_server(source_home,args.forge113_home.resolve(),original,
                    project/'build/native113-seed/lootablebodies-native113-world-fixtures-reobf.jar')
                sealed=seed_native113(args.java.resolve(),source_home)
            else:
                distribution={'1.10.2':args.forge110_home,'1.11.2':args.forge111_home,'1.12.2':args.forge112_home}[minecraft]
                prepare(source_home,distribution.resolve(),minecraft,original,seeder)
                sealed=seed(args.java.resolve(),source_home,minecraft,version)
            details=transition114(args.java.resolve(),project,source_home/'world',sealed,
                root/origin,args.forge114_home,args.released_114.resolve(),minecraft,version,expected)
            report['origins'][origin]=details
            (root/'seed-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
            print('LEGACY_WORLD_SEALED',origin,len(details['world']),'files',flush=True)
            continue
        if origin.startswith('via113-'):
            from prepare_native113_tools import historical_runtime
            original = args.original_110 if origin.endswith('110') else args.released_111 if origin.endswith('111') else args.released_112
            if digest(original.resolve()) != expected:
                raise RuntimeError('Incorrect historical source jar for '+origin)
            source_home = root/(origin+'-source')
            distribution = {'1.10.2':args.forge110_home,'1.11.2':args.forge111_home,'1.12.2':args.forge112_home}[minecraft]
            prepare(source_home, distribution.resolve(), minecraft, original.resolve(), seeder)
            seed(args.java.resolve(), source_home, minecraft, version)
            details = transition113(args.java.resolve(), project, source_home/'world',
                fingerprint(source_home/'world'), root/origin, args.forge113_home,
                args.released_113.resolve(), origin, minecraft, version, expected)
            report['origins'][origin] = details
            (root/'seed-report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
            print('LEGACY_WORLD_SEALED', origin, len(details['world']), 'files', flush=True)
            continue
        if origin.startswith('via112-'):
            details=seed_transition112(args,project,root,seeder,origin)
            report['origins'][origin]=details
            (root/'seed-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
            print('LEGACY_WORLD_SEALED',origin,len(details['world']),'files',flush=True)
            continue
        if origin=='via111-110':
            details=seed_transition(args,project,root,seeder)
            report['origins'][origin]=details
            (root/'seed-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
            print('LEGACY_WORLD_SEALED',origin,len(details['world']),'files',flush=True)
            continue
        source = getattr(args, origin.replace('recovery-', 'released-').replace('-', '_')).resolve()
        if digest(source) != expected:
            raise RuntimeError('Historical jar checksum mismatch: ' + origin)
        if minecraft=='1.14.4':
            from prepare_native114_tools import historical_runtime, SOURCE
            runtime=historical_runtime(project)
            if not args.forge114_home: raise ValueError('--forge114-home is required for the released 1.14 writer')
            home=root/origin
            native_seeder=project/'build/native114-seed/lootablebodies-native114-world-fixtures-reobf.jar'
            record=json.loads((project/'.gradle/legacy-tools/released-114/tools.json').read_text())
            if record['source']!=SOURCE or digest(native_seeder)!=record['sha256']:
                raise RuntimeError('Released 1.14 writer source or artifact changed')
            runtime.prepare_server(home,args.forge114_home.resolve(),source,native_seeder)
            hashes=seed_native114(args.java.resolve(),home,origin.startswith('recovery-'))
            report['origins'][origin]={'minecraft':minecraft,'mod_version':version,
                'jar_sha256':expected,'seeder_sha256':digest(native_seeder),
                'writer_source_commit':SOURCE,'world':hashes}
            (root/'seed-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
            print('LEGACY_WORLD_SEALED',origin,len(hashes),'files',flush=True)
            continue
        if minecraft == '1.13.2':
            from prepare_native113_tools import historical_runtime
            runtime = historical_runtime(project)
            if not args.forge113_home:
                raise ValueError('--forge113-home is required for the released 1.13 writer')
            home = root/origin
            native_seeder = project/'build/native113-seed/lootablebodies-native113-world-fixtures-reobf.jar'
            runtime.prepare_server(home, args.forge113_home.resolve(), source, native_seeder)
            hashes = seed_native113(args.java.resolve(), home, origin.startswith('recovery-'))
            report['origins'][origin] = {'minecraft': minecraft, 'mod_version': version,
                'jar_sha256': expected, 'seeder_sha256': digest(native_seeder),
                'writer_source_commit': '8219190bab22600475542d20ed5df74fea15c27b', 'world': hashes}
            (root/'seed-report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
            print('LEGACY_WORLD_SEALED', origin, len(hashes), 'files', flush=True)
            continue
        distribution = {'1.10.2':args.forge110_home,'1.11.2':args.forge111_home,'1.12.2':args.forge112_home}[minecraft]
        home = root / origin
        prepare(home, distribution.resolve(), minecraft, source, seeder)
        hashes = seed(args.java.resolve(), home, minecraft, version, origin.startswith('recovery-'))
        report['origins'][origin] = {'minecraft': minecraft, 'mod_version': version, 'jar_sha256': expected, 'world': hashes}
        (root / 'seed-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print('LEGACY_WORLD_SEALED', origin, len(hashes), 'files', flush=True)


def seed_transition(args,project,root,seeder):
    original=args.original_110.resolve();released=args.released_111.resolve()
    if digest(original)!=ORIGINS['original-110'][2] or digest(released)!=ORIGINS['released-111'][2]:
        raise RuntimeError('Transition production jar checksum mismatch')
    source=root/'via111-110-source'
    prepare(source,args.forge110_home.resolve(),'1.10.2',original,seeder)
    sealed=seed(args.java.resolve(),source,'1.10.2','2.2.0')
    target=root/'via111-110'
    probe=project/'build/transition-fixture/lootablebodies-111-transition-reobf.jar'
    prepare(target,args.forge111_home.resolve(),'1.11.2',released,probe)
    shutil.copytree(source/'world',target/'world')
    distant={str(p.relative_to(target/'world')):digest(p) for p in (target/'world').rglob('region/r.*.mca') if p.name in ('r.8.8.mca','r.16.16.mca')}
    with (target/'transition.log').open('w',encoding='utf-8') as log:
        result=subprocess.run([str(args.java.resolve()),'-Xmx2G','-jar',FORGE['1.11.2']+'-universal.jar','nogui'],cwd=target,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    text=(target/'transition.log').read_text(encoding='utf-8',errors='replace')
    if result.returncode or 'LOOTABLE_BODIES_TRANSITION_PASS loaded=3 unopened=6' not in text or not (target/'world/transition.pass').is_file():
        raise RuntimeError('Real released 1.11 intermediate writer failed: '+str(target/'transition.log'))
    audit(target/'transition.log',upgrade=True)
    if fingerprint(source/'world')!=sealed or len(distant)!=6 or any(digest(target/'world'/p)!=value for p,value in distant.items()):
        raise RuntimeError('Transition changed its sealed source or unopened distant regions')
    if not (target/'world/data/lootablebodies-upgrade/index.nbt').is_file() or list(target.rglob('crash-reports/*.txt')):
        raise RuntimeError('Missing intermediate journal or unexpected crash')
    return {'minecraft':'1.10.2 via 1.11.2','mod_version':'2.2.0 via 2.4.2.111021',
            'jar_sha256':digest(original),'intermediate_jar_sha256':digest(released),'transition_probe_sha256':digest(probe),
            'source_world_unchanged':True,'distant_regions_unchanged':distant,'world':fingerprint(target/'world')}


def transition113(java, project, source, sealed, target, distribution, released, origin,
                  minecraft, version, expected):
    """Use the released 1.13 writer on a copy, leaving six distant regions old."""
    from prepare_native113_tools import historical_runtime, SOURCE
    runtime = historical_runtime(project)
    record = json.loads((project/'.gradle/legacy-tools/released-113/tools.json').read_text())
    probe = project/'.gradle/legacy-tools/released-113/source/build'/record['probe']
    if record['source'] != SOURCE or digest(probe) != record['probe_sha256'] or digest(released) != ORIGINS['released-113'][2]:
        raise RuntimeError('Released 1.13 transition tool or artifact changed')
    if fingerprint(source) != sealed or not distribution:
        raise RuntimeError('Missing or changed historical transition source')
    runtime.prepare_server(target, distribution.resolve(), released, probe)
    shutil.copytree(source, target/'world')
    world = target/'world'
    shutil.copy2(next((world/'playerdata').glob('*.dat')), world/'upgrade-player-source.nbt')
    distant = {str(p.relative_to(world)):digest(p) for p in world.rglob('region/r.*.mca')
               if p.name in ('r.8.8.mca','r.16.16.mca')}
    runtime.run_server(java, target, 'upgrade-near',
        message='LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase=upgrade-near')
    if fingerprint(source) != sealed or len(distant) != 6 or any(digest(world/p) != sha for p,sha in distant.items()):
        raise RuntimeError('Released 1.13 transition changed its source or unopened regions')
    return {'minecraft':minecraft+' via 1.13.2','mod_version':version+' via 2.4.4.113021',
            'jar_sha256':expected,'intermediate_jar_sha256':digest(released),
            'transition_probe_sha256':digest(probe),'source_world_unchanged':True,
            'distant_regions_unchanged':distant,'world':fingerprint(world)}


def transition114(java, project, source, sealed, target, distribution, released,
                  minecraft, version, expected):
    """Run the released 1.14 verifier, retaining six unopened old regions."""
    from prepare_native114_tools import historical_runtime, SOURCE
    runtime=historical_runtime(project)
    record=json.loads((project/'.gradle/legacy-tools/released-114/tools.json').read_text())
    probe=project/'.gradle/legacy-tools/released-114/source/build'/record['probe']
    if record['source']!=SOURCE or digest(probe)!=record['probe_sha256'] or digest(released)!=ORIGINS['released-114'][2]:
        raise RuntimeError('Released 1.14 transition tool or artifact changed')
    if fingerprint(source)!=sealed or not distribution:
        raise RuntimeError('Missing or changed historical transition source')
    runtime.prepare_server(target,distribution.resolve(),released,probe)
    shutil.copytree(source,target/'world'); world=target/'world'
    shutil.copy2(next((world/'playerdata').glob('*.dat')),world/'upgrade-player-source.nbt')
    distant={str(p.relative_to(world)):digest(p) for p in world.rglob('region/r.*.mca')
             if p.name in ('r.8.8.mca','r.16.16.mca')}
    runtime.run_server(java,target,'upgrade-near',message='LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase=upgrade-near')
    if fingerprint(source)!=sealed or len(distant)!=6 or any(digest(world/p)!=sha for p,sha in distant.items()):
        raise RuntimeError('Released 1.14 transition changed its source or unopened regions')
    return {'minecraft':minecraft+' via 1.14.4','mod_version':version+' via 2.4.5.114041',
            'jar_sha256':expected,'intermediate_jar_sha256':digest(released),
            'transition_probe_sha256':digest(probe),'source_world_unchanged':True,
            'distant_regions_unchanged':distant,'world':fingerprint(world)}


def seed_transition112(args,project,root,seeder,origin):
    original=args.original_110.resolve() if origin=='via112-110' else args.released_111.resolve()
    minecraft,version,expected=ORIGINS[origin];released=args.released_112.resolve()
    if digest(original)!=expected or digest(released)!=ORIGINS['released-112'][2]:raise RuntimeError('Incorrect intermediate production jar')
    source=root/(origin+'-source');distribution=args.forge110_home if minecraft=='1.10.2' else args.forge111_home
    prepare(source,distribution.resolve(),minecraft,original,seeder);sealed=seed(args.java.resolve(),source,minecraft,version)
    historical=project/'.gradle/legacy-tools/released-112'
    record=json.loads((historical/'tools.json').read_text());name='integration-test-mod/lootablebodies-build-tests-reobf.jar'
    probe=historical/'source/build'/name
    if record['source']!='ee8ee733fa091e961f27812ad780d56c35813f30' or digest(probe)!=record['artifacts'][name]:raise RuntimeError('Historical 1.12 verifier source or checksum changed')
    target=root/origin;prepare(target,args.forge112_home.resolve(),'1.12.2',released,probe)
    shutil.copytree(source/'world',target/'world');world=target/'world'
    shutil.copy2(next((world/'playerdata').glob('*.dat')),world/'upgrade-player-source.nbt')
    distant={str(p.relative_to(world)):digest(p) for p in world.rglob('region/r.*.mca') if p.name in ('r.8.8.mca','r.16.16.mca')}
    with (target/'transition.log').open('w',encoding='utf-8') as log:
        result=subprocess.run([str(args.java.resolve()),'-Xmx2G','-Dlootablebodies.phase=upgrade-near','-jar',FORGE['1.12.2']+'-universal.jar','nogui'],cwd=target,stdout=log,stderr=subprocess.STDOUT,timeout=240)
    text=(target/'transition.log').read_text(encoding='utf-8',errors='replace')
    if result.returncode or 'LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase=upgrade-near' not in text or not (world/'upgrade-near.pass').is_file():raise RuntimeError('Actual released 1.12 transition failed: '+str(target/'transition.log'))
    audit(target/'transition.log',upgrade=True)
    if fingerprint(source/'world')!=sealed or len(distant)!=6 or any(digest(world/p)!=value for p,value in distant.items()):raise RuntimeError('Intermediate writer changed source or unopened regions')
    if list(target.glob('crash-reports/*')):raise RuntimeError('Historical transition crash report')
    (world/'upgrade-near.pass').rename(world/'transition112.pass')
    return {'minecraft':minecraft+' via 1.12.2','mod_version':version+' via 2.4.3.112021','jar_sha256':expected,'intermediate_jar_sha256':digest(released),'transition_probe_sha256':digest(probe),'source_world_unchanged':True,'distant_regions_unchanged':distant,'world':fingerprint(world)}


PHASES = ['upgrade-near', 'upgrade-near-reload', 'upgrade-near-recover', 'upgrade-near-recovered',
          'upgrade-far', 'upgrade-far-reload', 'upgrade-late', 'upgrade-return', 'upgrade-final', 'upgrade-final-reload']


def run_upgrade(java, home, phase):
    from native_runtime import run_server
    run_server(java,home,phase,message='LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase='+phase)



def patch_stamps(world, disposable):
    if not world.resolve().is_relative_to(disposable.resolve()):
        raise RuntimeError('Fixture mutation outside disposable output')
    changes = []
    for path in sorted(world.rglob('region/*.mca')):
        chunks = read_region(path)
        changed = False
        for chunk in chunks.values():
            root = chunk['root'].value
            level = root['Level'].value
            bodies = [e for e in level.get('Entities', Tag(9, (10, []))).value[1]
                      if e.value.get('id', Tag(8, '')).value in ('lootablebodies.Corpse', 'lootablebodies:corpse')]
            if not bodies:
                continue
            dimension = -1 if 'DIM-1' in path.parts else 1 if 'DIM1' in path.parts else 0
            wave = 0 if level['xPos'].value == 8 else 1 if level['xPos'].value == 256 else 2
            kind = wave * 3 + {0: 0, -1: 1, 1: 2}[dimension]
            root.pop('DataVersion', None)
            root.pop('ForgeDataVersion', None)
            if kind == 1:
                root['DataVersion'] = Tag(8, 'damaged chunk stamp')
            elif kind == 2:
                root['DataVersion'] = Tag(6, struct.pack('>d', float('nan')))
            elif kind == 3:
                root['DataVersion'] = Tag(3, -1)
            elif kind == 4:
                root['ForgeDataVersion'] = Tag(8, 'damaged Forge stamp')
            elif kind == 5:
                root['DataVersion'] = Tag(3, 1343)
                root['ForgeDataVersion'] = Tag(10, {'minecraft': Tag(3, 1343), 'lootablebodies': Tag(3, 2)})
            elif kind == 6:
                root['DataVersion'] = Tag(3, 2147483647)
            elif kind == 7:
                root['DataVersion'] = Tag(3, 0)
            elif kind == 8:
                root['DataVersion'] = Tag(2, 512)
            changed = True
            changes.append({'region': path.relative_to(world).as_posix(), 'chunk': [level['xPos'].value, level['zPos'].value], 'stamp_case': kind})
        if changed:
            write_disposable_region(path, chunks)
    path = world / 'level.dat'
    name, level = decode(gzip.decompress(path.read_bytes()))
    level.value['Data'].value['DataVersion'] = Tag(8, 'damaged world stamp')
    path.write_bytes(gzip.compress(encode(name, level), mtime=0))
    return changes


def preserve_historical_markers(home):
    home=home.resolve();world=home/'world'
    markers=[world/(phase+'.pass') for phase in PHASES if (world/(phase+'.pass')).exists()]
    if not markers:return []
    archive=home/'historical-run-markers'
    if archive.exists():raise RuntimeError('Historical runner evidence already exists')
    for marker in markers:
        if marker.is_symlink() or not marker.is_file() or not marker.resolve().is_relative_to(world):
            raise RuntimeError('Historical marker is outside the disposable world')
    archive.mkdir()
    for marker in markers:marker.rename(archive/marker.name)
    return [marker.name for marker in markers]


def verify(args, project):
    if not args.seeds:
        raise ValueError('--seeds is required for the upgrade matrix')
    if not args.forge115_home:
        raise ValueError('--forge115-home is required for the native upgrade matrix')
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    candidate = project / 'build/libs' / MAIN_JAR
    probe = project / 'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    candidate_hash = digest(candidate)
    probe_hash = digest(probe)
    sources = {}
    for seed_root in args.seeds:
        seed_root = seed_root.resolve()
        report = json.loads((seed_root / 'seed-report.json').read_text(encoding='utf-8'))
        for name, details in report['origins'].items():
            if name not in args.origins:
                continue
            if name in sources:
                raise ValueError('Ambiguous duplicate origin ' + name)
            source = seed_root / name / 'world'
            if fingerprint(source) != details['world']:
                raise RuntimeError('Sealed source fixture changed: ' + name)
            sources[name] = (source, details)
    if set(sources) != set(args.origins):
        raise RuntimeError('Missing requested historical fixture')
    report = {'candidate_sha256': candidate_hash, 'probe_sha256':probe_hash, 'cases': {}}
    for origin in args.origins:
        source,details=sources[origin]
        for variant in args.variants:
            name = origin + '-' + variant
            home = root / name
            from native_runtime import prepare_server
            prepare_server(home,args.forge115_home.resolve(),candidate,probe)
            shutil.copytree(source, home / 'world')
            world = home / 'world'
            historical_markers=preserve_historical_markers(home)
            player_files = list((world / 'playerdata').glob('*.dat'))
            if len(player_files) != 1:
                raise RuntimeError('Expected exactly one sealed fixture player')
            shutil.copy2(player_files[0], world / 'upgrade-player-source.nbt')
            changes = patch_stamps(world, root) if variant == 'stamps' else []
            distant = {str(path.relative_to(world)): digest(path) for path in world.rglob('region/r.*.mca')
                       if path.name in ('r.8.8.mca', 'r.16.16.mca')}
            if len(distant) != 6:
                raise RuntimeError('Missing distant region fixtures')
            phases = []
            for phase in PHASES:
                run_upgrade(args.java.resolve(), home, phase)
                wave = 0 if phase.startswith('upgrade-near') else 1 if phase.startswith('upgrade-far') else 2 if phase == 'upgrade-late' else -1
                persisted = 0
                for region in world.rglob('region/r.*.mca'):
                    if wave >= 0 and region.name != ['r.0.0.mca', 'r.8.8.mca', 'r.16.16.mca'][wave]:
                        continue
                    for chunk in read_region(region).values():
                        for entity in chunk['root'].value['Level'].value.get('Entities', Tag(9, (10, []))).value[1]:
                            if entity.value.get('id', Tag(8, '')).value not in ('lootablebodies.Corpse', 'lootablebodies:corpse'):
                                continue
                            if wave >= 0 or phase in ('upgrade-return', 'upgrade-final', 'upgrade-final-reload'):
                                if entity.value['id'].value != 'lootablebodies:corpse' or entity.value.get('LootableBodiesDataVersion') != Tag(3, 5):
                                    raise RuntimeError('A loaded corpse was not actually saved in the new format')
                                persisted += 1
                if persisted < (3 if wave >= 0 else 8):
                    raise RuntimeError('Missing persisted corpses after world save')
                for relative, original in distant.items():
                    unopened = (relative.endswith('r.8.8.mca') and phase.startswith('upgrade-near')) or (
                        relative.endswith('r.16.16.mca') and phase in PHASES[:6])
                    if unopened and digest(world / relative) != original:
                        raise RuntimeError('Unopened distant region was modified: ' + relative)
                phases.append(phase)
                if fingerprint(source) != details['world'] or digest(candidate) != candidate_hash or digest(probe) != probe_hash:
                    raise RuntimeError('Sealed source/candidate changed')
                report['cases'][name] = {'phases': phases, 'distant_regions': distant, 'stamp_mutations': changes,
                                        'source_unchanged': True, 'origin_jar_sha256': details['jar_sha256'],
                                        'preserved_historical_markers':historical_markers}
                (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
                print('WORLD_UPGRADE_PASS', name, phase, flush=True)
    print('WORLD_UPGRADE_MATRIX_PASS cases=' + str(len(report['cases'])), flush=True)


if __name__ == '__main__':
    main()
