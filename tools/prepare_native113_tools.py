"""Build the 1.13 fixture writer against the exact released 1.13 source."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from prepare_legacy_tools import prepare, digest
from command_logs import run_build_input_logged as run_logged

SOURCE = '8219190bab22600475542d20ed5df74fea15c27b'
OUTPUT = 'native113-seed/lootablebodies-native113-world-fixtures-reobf.jar'
PROBE = 'integration-test-mod/lootablebodies-build-tests-reobf.jar'


def historical_runtime(project):
    """Read the frozen release's installer/launch code, never the candidate's."""
    import importlib.util
    root = project / '.gradle/legacy-tools/released-113'
    source = root / 'source/tools/native_runtime.py'
    original = json.loads((root/'source.json').read_text())
    if original['commit'] != SOURCE or digest(source) != original['files']['tools/native_runtime.py']:
        raise RuntimeError('Missing or changed released 1.13 runtime tools; run prepare_native113_tools.py')
    spec = importlib.util.spec_from_file_location('released113_runtime', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--java17-home', type=Path, required=True)
    parser.add_argument('--java8-home', type=Path, required=True)
    parser.add_argument('--java25-home', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    output = (args.output or project / '.gradle/legacy-tools/released-113').resolve()
    source = prepare(project, output, SOURCE)
    environment = dict(os.environ, JAVA_HOME=str(args.java17_home),
                       LOOTABLEBODIES_JAVA8_HOME=str(args.java8_home),
                       LOOTABLEBODIES_MAVENIZER_JAVA_HOME=str(args.java25_home))
    wrapper = source / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    if os.name != 'nt':
        wrapper.chmod(0o755)
    run_logged([str(wrapper), 'prepareNative113WorldFixtures', 'preparePackagedIntegrationProbe',
                        '--init-script='+str(project/'gradle/native113-writer.init.gradle'),
                        '-PhistoricalWriterSource='+str(project), '--no-daemon', '--console=plain'],
                       cwd=source, env=environment, path=output / 'build.log', timeout=1800)
    artifact = source / 'build' / OUTPUT
    if not artifact.is_file() or not artifact.stat().st_size:
        raise FileNotFoundError(artifact)
    files = [p for p in (project/'src/nativeSeed113').rglob('*') if p.is_file()]
    probe = source / 'build' / PROBE
    if not probe.is_file() or not probe.stat().st_size:
        raise FileNotFoundError(probe)
    record = {'source': SOURCE, 'artifact': OUTPUT, 'sha256': digest(artifact),
              'probe': PROBE, 'probe_sha256': digest(probe),
              'writer_sources': {p.relative_to(project).as_posix(): digest(p) for p in files}}
    (output/'tools.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    target = project / 'build' / OUTPUT
    target.parent.mkdir(parents=True, exist_ok=True)
    import shutil
    shutil.copy2(artifact, target)
    print('Historical 1.13 writer built from released source '+SOURCE)


if __name__ == '__main__':
    main()
