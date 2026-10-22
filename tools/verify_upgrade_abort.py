"""Prove packaged servers stop before world loading when preservation is unsafe."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from verify_world_upgrades import prepare, fingerprint, digest, FORGE
from release_identity import MAIN_JAR


def source_files(world):
    return {name: value for name, value in fingerprint(world).items()
            if not name.startswith('data/lootablebodies-upgrade')}


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
        prepare(home, args.forge_home.resolve(), '1.12.2', candidate, probe)
        shutil.copytree(source, home / 'world')
        world = home / 'world'
        journal = world / 'data/lootablebodies-upgrade'
        journal.parent.mkdir(exist_ok=True)
        if case == 'damaged-index':
            journal.mkdir()
            (journal / 'index.nbt').write_bytes(b'interrupted invalid index')
        else:
            journal.write_bytes(b'cannot create a journal directory here')
        before = source_files(world)
        log = home / 'abort.log'
        with log.open('w', encoding='utf-8') as output:
            result = subprocess.run([str(args.java.resolve()), '-Xmx1G', '-jar',
                                     FORGE['1.12.2'] + '-universal.jar', 'nogui'],
                                    cwd=home, stdout=output, stderr=subprocess.STDOUT, timeout=180)
        text = log.read_text(encoding='utf-8', errors='replace')
        if "Lootable Bodies could not preserve this world's corpses" not in text:
            raise RuntimeError('Missing explicit preservation abort: ' + str(log))
        if any(token in text for token in ['Preparing start region', 'Preparing spawn area',
                                           'LOOTABLE_BODIES_RUNTIME_PASS', 'Done (']):
            raise RuntimeError('World loading continued after unsafe preservation')
        if before != source_files(world) or fingerprint(source) != sealed:
            raise RuntimeError('A failed preflight changed source world data')
        report['cases'][case] = {'exit': result.returncode, 'source_unchanged': True,
                                 'world_data_unchanged': True, 'world_loading_started': False}
        print('UPGRADE_ABORT_PASS', case, flush=True)
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
