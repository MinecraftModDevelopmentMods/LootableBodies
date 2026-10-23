"""Independent 1.10-to-1.12 Forge log control, without Lootable Bodies installed."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from verify_world_upgrades import FORGE, prepare, fingerprint, digest

FORGE_RENAME = '[FML.ModTracker]: This world was saved with mod Forge which appears to be missing, things may not work well'


def run(java, home, minecraft):
    log = home / 'control.log'
    with log.open('w', encoding='utf-8') as output:
        result = subprocess.run([str(java), '-Xmx1G', *(['-Dfml.queryResult=confirm'] if minecraft == '1.12.2' else []), '-jar', FORGE[minecraft] + '-universal.jar', 'nogui'],
                                cwd=home, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    text = log.read_text(encoding='utf-8', errors='replace')
    if result.returncode or 'FORGE_PLATFORM_CONTROL_PASS' not in text or 'lootablebodies@' in text:
        raise RuntimeError('Independent Forge control failed: ' + str(log))
    if list((home / 'crash-reports').glob('*')):
        raise RuntimeError('Independent Forge control crashed')
    return text


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--forge110-home', type=Path, required=True)
    p.add_argument('--forge112-home', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    control = project / 'build/control-test-mod/forge-platform-control-reobf.jar'
    root = a.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    old, new = root / 'source-110', root / 'target-112'
    prepare(old, a.forge110_home.resolve(), '1.10.2', control, control)
    run(a.java.resolve(), old, '1.10.2')
    sealed = fingerprint(old / 'world')
    prepare(new, a.forge112_home.resolve(), '1.12.2', control, control)
    shutil.copytree(old / 'world', new / 'world')
    text = run(a.java.resolve(), new, '1.12.2')
    if FORGE_RENAME not in text or fingerprint(old / 'world') != sealed:
        raise RuntimeError('Forge rename control did not reproduce the precise diagnostic or changed its source')
    errors = [line for line in text.splitlines() if '/ERROR]' in line]
    expected_sound=('Unidentified mapping from registry minecraft:soundevents',
                    'minecraft:entity.experience_orb.touch: 196',
                    'There are unidentified mappings in this world - we are going to attempt to process anyway')
    if any(FORGE_RENAME not in line and 'FML appears to be missing any signature data' not in line and not any(message in line for message in expected_sound) for line in errors):
        raise RuntimeError('Unexpected control errors: ' + repr(errors))
    report = {'control_jar_sha256': digest(control), 'lootable_bodies_installed': False,
              'source_unchanged': True, 'reproduced_diagnostic': FORGE_RENAME, 'error_lines': errors,
              'source_world': sealed, 'empty_control_only_confirm': True, 'known_vanilla_sound_rename': 'minecraft:entity.experience_orb.touch -> minecraft:entity.experience_orb.pickup'}
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('FORGE_UPGRADE_CONTROL_PASS', flush=True)


if __name__ == '__main__':
    main()
