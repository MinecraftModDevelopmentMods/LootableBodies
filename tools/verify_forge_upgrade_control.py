"""Independent 1.10-to-1.11 Forge log control, without Lootable Bodies installed."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
from verify_world_upgrades import FORGE, prepare, fingerprint, digest

FORGE_RENAME = '[fml.ModTracker]: This world was saved with mod Forge which appears to be missing, things may not work well'


def run(java, home, minecraft):
    log = home / 'control.log'
    with log.open('w', encoding='utf-8') as output:
        result = subprocess.run([str(java), '-Xmx1G', '-jar', FORGE[minecraft] + '-universal.jar', 'nogui'],
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
    p.add_argument('--forge111-home', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    project = Path(__file__).resolve().parents[1]
    control = project / 'build/control-test-mod/forge-platform-control-reobf.jar'
    root = a.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    old, new = root / 'source-110', root / 'target-111'
    prepare(old, a.forge110_home.resolve(), '1.10.2', control, control)
    run(a.java.resolve(), old, '1.10.2')
    sealed = fingerprint(old / 'world')
    prepare(new, a.forge111_home.resolve(), '1.11.2', control, control)
    shutil.copytree(old / 'world', new / 'world')
    text = run(a.java.resolve(), new, '1.11.2')
    if FORGE_RENAME not in text or fingerprint(old / 'world') != sealed:
        raise RuntimeError('Forge rename control did not reproduce the precise diagnostic or changed its source')
    errors = [line for line in text.splitlines() if '/ERROR]' in line]
    if any(FORGE_RENAME not in line and 'FML appears to be missing any signature data' not in line for line in errors):
        raise RuntimeError('Unexpected control errors: ' + repr(errors))
    report = {'control_jar_sha256': digest(control), 'lootable_bodies_installed': False,
              'source_unchanged': True, 'reproduced_diagnostic': FORGE_RENAME, 'error_lines': errors,
              'source_world': sealed}
    (root / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('FORGE_UPGRADE_CONTROL_PASS', flush=True)


if __name__ == '__main__':
    main()
