"""Prove packaged servers stop before world loading when preservation is unsafe."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from native_runtime import prepare_server as prepare, fingerprint, digest, FORGE
from release_identity import MAIN_JAR


def source_files(world):
    return {name: value for name, value in fingerprint(world).items()
            if not name.startswith('data/lootablebodies-upgrade')}


def allowed_platform_additions(before, after, world, original_config=None):
    # Forge writes its stock server config before the about-to-start event.
    # Only an exact, newly created default is allowed; existing config is data.
    name = 'serverconfig/forge-server.toml'
    if name not in after:
        return []
    expected = (Path(__file__).parent / 'fixtures/forge28-server-default.toml').read_bytes()
    actual = (world / name).read_bytes()
    if name in before:
        if original_config is None or before[name] != __import__('hashlib').sha256(original_config).hexdigest().upper():
            return []
        # The pinned Forge itself upgrades its own config before our preflight.
        # Require proof that every existing value survives and only its two new
        # target defaults are added. Original bytes remain in the sealed source.
        import tomllib
        try:
            previous = tomllib.loads(original_config.decode('utf-8'))
            upgraded = tomllib.loads(actual.decode('utf-8'))
            previous['server'].setdefault('treatEmptyTagsAsAir', False)
            previous['server'].setdefault('fixAdvancementLoading', True)
            return [name] if previous == upgraded else []
        except (ValueError, KeyError, UnicodeError):
            return []
    return [name] if actual.replace(b'\r\n', b'\n') == expected.replace(b'\r\n', b'\n') else []


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
        log = home / 'abort.log'
        with log.open('w', encoding='utf-8') as output:
            result = subprocess.run([str(args.java.resolve()), '-Xmx1G', '-jar',
                                     FORGE + '.jar', '--nogui'],
                                    cwd=home, stdout=output, stderr=subprocess.STDOUT, timeout=180)
        text = log.read_text(encoding='utf-8', errors='replace')
        if "Lootable Bodies could not preserve this world's corpses" not in text:
            raise RuntimeError('Missing explicit preservation abort: ' + str(log))
        if any(token in text for token in ['Preparing start region', 'Preparing spawn area',
                                           'LOOTABLE_BODIES_RUNTIME_PASS', 'Done (']):
            raise RuntimeError('World loading continued after unsafe preservation')
        after = source_files(world)
        raw_after = dict(after)
        platform_additions = allowed_platform_additions(before, after, world, original_config)
        for name in platform_additions:
            if name in before: after[name] = before[name]
            else: del after[name]
        source_after = fingerprint(source)
        comparison = {
            'before': before, 'after': raw_after,
            'allowed_stock_config_files': platform_additions,
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
        print('UPGRADE_ABORT_PASS', case, flush=True)
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
