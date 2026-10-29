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


def run_abort(java, home):
    from native_runtime import run_java, HISTORICAL_JVM_ARGS
    return run_java(java, home, 'abort', timeout=180,
                    jvm_args=['-Xmx1G', *HISTORICAL_JVM_ARGS])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--forge-home', type=Path, required=True)
    parser.add_argument('--source-world', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    candidate = project / 'build/libs' / MAIN_JAR
    probe = project / 'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    source = args.source_world.resolve()
    sealed = fingerprint(source)
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    report = {'candidate_sha256': digest(candidate), 'cases': {}}
    for case in ['damaged-index', 'blocked-journal']:
        home = root / case
        prepare(home, args.forge_home.resolve(), candidate, probe)
        shutil.copytree(source, home / 'world')
        world = home / 'world'
        inject_unsafe_journal(world, case)
        before = source_files(world)
        config = world/'serverconfig/forge-server.toml'
        original_config = config.read_bytes() if config.is_file() else None
        lock=world/'session.lock';original_lock=lock.read_bytes() if lock.is_file() else None
        log = home / 'abort.log'
        result = run_abort(args.java.resolve(), home)
        text = log.read_text(encoding='utf-8', errors='replace')
        if "Lootable Bodies could not preserve this world's corpses" not in text:
            raise RuntimeError('Missing explicit preservation abort: ' + str(log))
        if any(token in text for token in ['Preparing start region', 'Preparing spawn area',
                                           'LOOTABLE_BODIES_RUNTIME_PASS', 'Done (']):
            raise RuntimeError('World loading continued after unsafe preservation')
        after = source_files(world)
        raw_after = dict(after)
        platform_additions = allowed_platform_additions(before, after, world, original_config)
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
        print('UPGRADE_ABORT_PASS', case, flush=True)
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
