"""Prove packaged servers stop before world loading when preservation is unsafe."""
import argparse
import json
from pathlib import Path
import shutil
import tomllib
from native_runtime import prepare_server as prepare, fingerprint, digest
from release_identity import MAIN_JAR


def same_typed_values(expected, actual):
    if type(expected) is not type(actual):
        return False
    if isinstance(expected, dict):
        return expected.keys() == actual.keys() and all(
            same_typed_values(value, actual[name]) for name, value in expected.items())
    if isinstance(expected, list):
        return len(expected) == len(actual) and all(
            same_typed_values(left, right) for left, right in zip(expected, actual))
    return expected == actual


def allowed_platform_additions(before, after, world, original_config=None):
    # Forge writes its stock server config before the about-to-start event.
    # Require every default value and its type; Forge's entry order varies by JVM.
    name = 'serverconfig/forge-server.toml'
    if name not in after:
        return []
    expected = (Path(__file__).parent / 'fixtures/forge36-server-default.toml').read_bytes()
    actual = (world / name).read_bytes()
    if name in before:
        if original_config is None or before[name] != __import__('hashlib').sha256(original_config).hexdigest().upper():
            return []
        # The pinned Forge itself upgrades its own config before our preflight.
        # Native Forge36 removes its retired packet clumping option and adds
        # these two defaults for older configs. Independent Forge-only controls
        # prove this correction, including a nondefault choice. Every supported
        # value/type must survive; original bytes remain in the sealed source.
        try:
            previous = tomllib.loads(original_config.decode('utf-8'))
            upgraded = tomllib.loads(actual.decode('utf-8'))
            if 'clumpingThreshold' in previous['server']:
                retired=previous['server'].pop('clumpingThreshold')
                if type(retired) is not int or not 64 <= retired <= 1024:return []
            previous['server'].setdefault('treatEmptyTagsAsAir', False)
            previous['server'].setdefault('fixAdvancementLoading', True)
            return [name] if same_typed_values(previous, upgraded) else []
        except (ValueError, KeyError, UnicodeError):
            return []
    try:
        return [name] if same_typed_values(tomllib.loads(expected.decode('utf-8')),
                                          tomllib.loads(actual.decode('utf-8'))) else []
    except (ValueError, UnicodeError):
        return []


def allowed_native_session_lock(before, after, world, original=None):
    # Native16 writes this exact UTF-8 marker while acquiring its file lock,
    # before any mod world-read hook. It does not truncate an old 8-byte lock.
    # A native-API control proves every trailing byte remains unchanged.
    name='session.lock';marker=b'\xe2\x98\x83'
    if name not in after:return []
    actual=(world/name).read_bytes()
    if after[name]!=__import__('hashlib').sha256(actual).hexdigest().upper():return []
    if name not in before:
        return [name] if original is None and actual==marker else []
    if original is None or before[name]!=__import__('hashlib').sha256(original).hexdigest().upper():return []
    if len(original)!=8 and original!=marker:return []
    return [name] if actual==marker+original[3:] else []


def allowed_native40_platform_additions(before,after,world,original_config=None):
    """Exact pinned Forge40 spec corrections, proved with the actual native spec."""
    name='serverconfig/forge-server.toml'
    if name not in after:return []
    actual=(world/name).read_bytes()
    if digest(world/name)!=after[name]:return []
    try:
        defaults=tomllib.loads((Path(__file__).parent/'fixtures/forge40-server-default.toml').read_text())
        upgraded=tomllib.loads(actual.decode('utf-8'))
        if name not in before:return [name] if same_typed_values(defaults,upgraded) else []
        if original_config is None or before[name]!=__import__('hashlib').sha256(original_config).hexdigest().upper():return []
        previous=tomllib.loads(original_config.decode('utf-8'));server=previous['server']
        retired={'removeErroringTileEntities':bool,
                 'logCascadingWorldGeneration':bool,'fixVanillaCascading':bool,
                 'dimensionUnloadQueueDelay':int,'clumpingThreshold':int}
        for key,kind in retired.items():
            if key not in server:continue
            value=server.pop(key)
            if type(value) is not kind:return []
            if key=='dimensionUnloadQueueDelay' and value<0:return []
            if key=='clumpingThreshold' and not 64<=value<=1024:return []
        for key,value in defaults['server'].items():server.setdefault(key,value)
        return [name] if same_typed_values(previous,upgraded) else []
    except (ValueError,KeyError,UnicodeError):return []



def allowed_forge_platform_additions(before,after,world,original_config=None,*,forge):
    """Exact Forge config corrections; retain all supported choices."""
    if forge not in (47,50):raise ValueError('Unsupported Forge config target')
    name='serverconfig/forge-server.toml'
    if name not in after:return []
    actual=(world/name).read_bytes()
    if digest(world/name)!=after[name]:return []
    try:
        defaults=tomllib.loads((Path(__file__).parent/f'fixtures/forge{forge}-server-default.toml').read_text())
        upgraded=tomllib.loads(actual.decode('utf-8'))
        if name not in before:return [name] if same_typed_values(defaults,upgraded) else []
        if original_config is None or before[name]!=__import__('hashlib').sha256(original_config).hexdigest().upper():return []
        previous=tomllib.loads(original_config.decode('utf-8'));server=previous['server']
        retired={'removeErroringTileEntities':bool,'logCascadingWorldGeneration':bool,
                 'fixVanillaCascading':bool,'dimensionUnloadQueueDelay':int,'clumpingThreshold':int,
                 'fixAdvancementLoading':bool,'treatEmptyTagsAsAir':bool,'skipEmptyShapelessCheck':bool}
        if forge==50:retired.update(zombieBaseSummonChance=float,zombieBabyChance=float)
        for key,kind in retired.items():
            if key not in server:continue
            value=server.pop(key)
            if type(value) is not kind:return []
            if key=='dimensionUnloadQueueDelay' and value<0:return []
            if key=='clumpingThreshold' and not 64<=value<=1024:return []
            if key in ('zombieBaseSummonChance','zombieBabyChance') and not 0<=value<=1:return []
        for key,value in defaults['server'].items():server.setdefault(key,value)
        return [name] if same_typed_values(previous,upgraded) else []
    except (ValueError,KeyError,UnicodeError):return []


def inject_unsafe_journal(world, case):
    if case not in ('damaged-index', 'blocked-journal'):
        raise ValueError('Unknown abort fixture: ' + case)
    world = world.resolve()
    journal = world / 'data/lootablebodies-upgrade'
    journal.parent.mkdir(exist_ok=True)
    if journal.exists():
        # This is the disposable world copy; retain its prior journal outside
        # the world before injecting the deliberate fault. Never touch source.
        saved = world.parent / 'original-journal'
        if journal.is_symlink() or not journal.resolve().is_relative_to(world):
            raise RuntimeError('Abort fixture journal is outside its disposable world')
        if saved.parent.resolve() != world.parent or saved.exists():
            raise RuntimeError('Abort fixture journal backup is not a new sibling')
        journal.rename(saved)
    if case == 'damaged-index':
        journal.mkdir()
        (journal / 'index.nbt').write_bytes(b'interrupted invalid index')
    else:
        journal.write_bytes(b'cannot create a journal directory here')


def inject_future_entity_version(world):
    """One copied genuine public18 chunk, valid future Int; never touch source."""
    from nbt_regions import Tag,read_region,write_disposable_region
    import struct
    path=world/'entities/r.0.0.mca'
    chunks=read_region(path)
    candidates=[]
    for index,chunk in chunks.items():
        root=chunk['root'].value
        entries=root.get('Entities')
        if entries is None or entries.kind!=9 or entries.value[0] not in (0,10):raise ValueError('Malformed genuine entity list')
        bodies=[e for e in entries.value[1] if e.value.get('id',Tag(8,'')).value=='lootablebodies:corpse']
        if bodies:candidates.append((index,root,bodies))
    if len(candidates)!=1:raise ValueError('Expected one genuine near public18 corpse chunk')
    index,root,bodies=candidates[0]
    if root.get('DataVersion')!=Tag(3,2975) or any(e.value.get('LootableBodiesDataVersion')!=Tag(3,8) for e in bodies):
        raise ValueError('Future-version control requires genuine native18 writer data')
    before=digest(path);original=world.parent/'future-entity-original.mca'
    if original.exists():raise ValueError('Future fixture original already exists')
    shutil.copy2(path,original)
    future_version = 4791  # Immediately beyond Minecraft 26.1.2's 4790.
    root['DataVersion'] = Tag(3, future_version)
    write_disposable_region(path, chunks)
    return {'region':path.relative_to(world).as_posix(),'chunk_index':index,'source_version':2975,
            'injected_version':future_version,'nbt_type':'Int','original_sha256':before,'mutated_sha256':digest(path),
            'original_copy':str(original),'corpse_marker':8}


def run_abort(java, home):
    from native_runtime import run_java, HISTORICAL_JVM_ARGS
    logging=Path(__file__).resolve().parents[1]/'src/integrationTest/resources/log4j2.xml'
    return run_java(java, home, 'abort', timeout=180,
                    jvm_args=['-Xmx1G', *HISTORICAL_JVM_ARGS,
                              '-Dlog4j.configurationFile='+logging.as_uri()])


def verified_future_journal(world, before, after):
    """The future-version check runs after the immutable backup is written."""
    from gzip import decompress
    from hashlib import sha256
    import re
    from nbt_regions import Tag, decode

    prefix = 'data/lootablebodies-upgrade/'
    index = prefix + 'index.nbt'
    added = set(after) - set(before)
    journal = {name for name in added if name.startswith(prefix)}
    for name in journal - {index}:
        match = re.fullmatch(prefix + r'(chunks|snapshots)/([a-f0-9]{64})\.(bin|nbt)', name)
        if not match or (match[1], match[3]) not in {('chunks', 'bin'), ('snapshots', 'nbt')}:
            raise ValueError('Unexpected recovery file after refusal: ' + name)
        if after[name].lower() != match[2]:
            raise ValueError('Recovery content hash mismatch after refusal: ' + name)
    if index not in after or not journal:
        raise ValueError('Future-version refusal did not retain its recovery backup')
    envelope = decode(decompress((world / index).read_bytes()))[1].value
    payload = envelope['Payload']
    if (envelope.get('Format') != Tag(3, 2) or payload.kind != 7
            or envelope.get('Digest') != Tag(8, sha256(payload.value).hexdigest())):
        raise ValueError('Recovery index checksum mismatch after refusal')
    record = decode(decompress(payload.value))[1].value
    if record.get('Format') != Tag(3, 2) or record['Regions'].value[0] != 10:
        raise ValueError('Invalid recovery index after refusal')
    snapshots = {prefix + 'snapshots/' + item.value for region in record['Regions'].value[1]
                 for item in region.value['Snapshots'].value[1]}
    new_snapshots = {name for name in journal if name.startswith(prefix + 'snapshots/')}
    if not new_snapshots or not new_snapshots <= snapshots:
        raise ValueError('Recovery index omitted a new original')
    referenced = set()
    for name in new_snapshots:
        snapshot = decode(decompress((world / name).read_bytes()))[1].value
        chunk = prefix + 'chunks/' + snapshot['OriginalChunk'].value + '.bin'
        if chunk not in after or after[chunk].lower() != snapshot['OriginalChunk'].value:
            raise ValueError('Recovery original chunk missing or changed: ' + chunk)
        referenced.add(chunk)
    if not (journal - new_snapshots - {index}) <= referenced:
        raise ValueError('Recovery journal contains an unreferenced new chunk')
    return sorted(journal | {index})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--forge-home', type=Path, required=True)
    parser.add_argument('--source-world', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime-inputs',type=Path,help='Use one sealed local candidate and probe bundle')
    parser.add_argument('--conditions',nargs='+',choices=['damaged-index','blocked-journal','future-entity-version'],
                        default=['damaged-index','blocked-journal'])
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    inputs=getattr(args,'runtime_inputs',None)
    if inputs is None:
        candidate = project / 'build/libs' / MAIN_JAR
        probe = project / 'build/integration-test-mod/lootablebodies-build-tests.jar'
    else:
        from native_runtime import sealed_runtime_jars
        candidate,probe=sealed_runtime_jars(inputs,project/'src/integrationTest/resources/log4j2.xml')
    source = args.source_world.resolve()
    sealed = fingerprint(source)
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    report = {'candidate_sha256': digest(candidate), 'cases': {}}
    if len(set(args.conditions))!=len(args.conditions):raise ValueError('Duplicate refusal condition')
    for case in args.conditions:
        home = root / case
        prepare(home, args.forge_home.resolve(), candidate, probe)
        shutil.copytree(source, home / 'world')
        world = home / 'world'
        mutation=inject_future_entity_version(world) if case=='future-entity-version' else None
        if mutation is None:inject_unsafe_journal(world, case)
        before = fingerprint(world)
        lock=world/'session.lock';original_lock=lock.read_bytes() if lock.is_file() else None
        log = home / 'abort.log'
        result = run_abort(args.java.resolve(), home)
        if result.returncode != 1:
            raise RuntimeError('Dedicated preservation refusal did not exit with status 1: ' + str(log))
        text = log.read_text(encoding='utf-8')
        if "Lootable Bodies could not preserve this world's corpses" not in text:
            raise RuntimeError('Missing explicit preservation abort: ' + str(log))
        if mutation and 'Saved entity chunk comes from a newer Minecraft version' not in text:
            raise RuntimeError('Missing actual typed future entity-version refusal')
        if any(token in text for token in ['Preparing start region', 'Preparing spawn area',
                                           'LOOTABLE_BODIES_RUNTIME_PASS', 'Done (']):
            raise RuntimeError('World loading continued after unsafe preservation')
        after = fingerprint(world)
        raw_after = dict(after)
        lock_updates=allowed_native_session_lock(before,after,world,original_lock)
        recovery_updates = verified_future_journal(world, before, after) if mutation else []
        for name in lock_updates + recovery_updates:
            if name in before: after[name] = before[name]
            else: del after[name]
        source_after = fingerprint(source)
        comparison = {
            'before': before, 'after': raw_after,
            'allowed_stock_config_files': [],
            'allowed_native_session_lock_files': lock_updates,
            'verified_recovery_files': recovery_updates,
            'original_session_lock_hex':original_lock.hex() if original_lock is not None else None,
            'world_added': sorted(set(after) - set(before)),
            'world_removed': sorted(set(before) - set(after)),
            'world_changed': sorted(name for name in set(before) & set(after) if before[name] != after[name]),
            'sealed_source_unchanged': source_after == sealed,
        }
        (home / 'comparison.json').write_text(json.dumps(comparison, indent=2) + '\n', encoding='utf-8')
        if before != after or source_after != sealed:
            raise RuntimeError('A failed preflight changed source world data: ' + str(home / 'comparison.json'))
        report['cases'][case] = {'exit': result.returncode, 'source_unchanged': True,
                                 'world_data_unchanged': True, 'world_loading_started': False,
                                 'stock_forge_config_created': [], 'stock_forge_config_updated': []}
        report['cases'][case]['native_session_lock_updates']=lock_updates
        report['cases'][case]['verified_recovery_files']=recovery_updates
        if mutation:report['cases'][case]['fixture_mutation']=mutation
        print('UPGRADE_ABORT_PASS', case, flush=True)
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
