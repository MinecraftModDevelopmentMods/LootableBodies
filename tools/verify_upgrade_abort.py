"""Prove packaged servers stop before world loading when preservation is unsafe."""
import argparse
import json
from pathlib import Path
import shutil
import tomllib
from native_runtime import prepare_server as prepare, fingerprint, digest
from release_identity import MAIN_JAR


def source_files(world):
    return {name: value for name, value in fingerprint(world).items()
            if not name.startswith('data/lootablebodies-upgrade')}


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
    """One copied genuine public17 chunk, valid future Int; never touch source."""
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
    if len(candidates)!=1:raise ValueError('Expected one genuine near public17 corpse chunk')
    index,root,bodies=candidates[0]
    if root.get('DataVersion')!=Tag(3,2730) or any(e.value.get('LootableBodiesDataVersion')!=Tag(3,7) for e in bodies):
        raise ValueError('Future-version control requires genuine native17 writer data')
    before=digest(path);original=world.parent/'future-entity-original.mca'
    if original.exists():raise ValueError('Future fixture original already exists')
    shutil.copy2(path,original)
    root['DataVersion']=Tag(3,2976);write_disposable_region(path,chunks)
    return {'region':path.relative_to(world).as_posix(),'chunk_index':index,'source_version':2730,
            'injected_version':2976,'nbt_type':'Int','original_sha256':before,'mutated_sha256':digest(path),
            'original_copy':str(original),'corpse_marker':7}


def run_abort(java, home):
    from native_runtime import run_java, HISTORICAL_JVM_ARGS
    logging=Path(__file__).resolve().parents[1]/'src/integrationTest/resources/log4j2.xml'
    return run_java(java, home, 'abort', timeout=180,
                    jvm_args=['-Xmx1G', *HISTORICAL_JVM_ARGS,
                              '-Dlog4j.configurationFile='+logging.as_uri()])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--forge-home', type=Path, required=True)
    parser.add_argument('--source-world', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--conditions',nargs='+',choices=['damaged-index','blocked-journal','future-entity-version'],
                        default=['damaged-index','blocked-journal'])
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    candidate = project / 'build/libs' / MAIN_JAR
    probe = project / 'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
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
        before = source_files(world)
        config = world/'serverconfig/forge-server.toml'
        original_config = config.read_bytes() if config.is_file() else None
        lock=world/'session.lock';original_lock=lock.read_bytes() if lock.is_file() else None
        log = home / 'abort.log'
        result = run_abort(args.java.resolve(), home)
        text = log.read_text(encoding='utf-8', errors='replace')
        if "Lootable Bodies could not preserve this world's corpses" not in text:
            raise RuntimeError('Missing explicit preservation abort: ' + str(log))
        if mutation and 'Saved entity chunk comes from a newer Minecraft version' not in text:
            raise RuntimeError('Missing actual typed future entity-version refusal')
        if any(token in text for token in ['Preparing start region', 'Preparing spawn area',
                                           'LOOTABLE_BODIES_RUNTIME_PASS', 'Done (']):
            raise RuntimeError('World loading continued after unsafe preservation')
        after = source_files(world)
        raw_after = dict(after)
        platform_additions = allowed_native40_platform_additions(before, after, world, original_config)
        lock_updates=allowed_native_session_lock(before,after,world,original_lock)
        for name in platform_additions+lock_updates:
            if name in before: after[name] = before[name]
            else: del after[name]
        source_after = fingerprint(source)
        comparison = {
            'before': before, 'after': raw_after,
            'allowed_stock_config_files': platform_additions,
            'allowed_native_session_lock_files': lock_updates,
            'original_session_lock_hex':original_lock.hex() if original_lock is not None else None,
            'world_added': sorted(set(after) - set(before)),
            'world_removed': sorted(set(before) - set(after)),
            'world_changed': sorted(name for name in set(before) & set(after) if before[name] != after[name]),
            'sealed_source_unchanged': source_after == sealed,
            'original_forge_config': original_config.decode('utf-8', errors='replace') if original_config else None,
            'resulting_forge_config': config.read_text(encoding='utf-8') if config.is_file() else None,
        }
        (home / 'comparison.json').write_text(json.dumps(comparison, indent=2) + '\n', encoding='utf-8')
        if before != after or source_after != sealed:
            raise RuntimeError('A failed preflight changed source world data: ' + str(home / 'comparison.json'))
        report['cases'][case] = {'exit': result.returncode, 'source_unchanged': True,
                                 'world_data_unchanged': True, 'world_loading_started': False,
                                 'stock_forge_config_created': [name for name in platform_additions if name not in before],
                                 'stock_forge_config_updated': [name for name in platform_additions if name in before]}
        report['cases'][case]['native_session_lock_updates']=lock_updates
        if mutation:report['cases'][case]['fixture_mutation']=mutation
        print('UPGRADE_ABORT_PASS', case, flush=True)
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
