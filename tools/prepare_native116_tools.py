"""Build historical fixtures against the exact released 1.16 source and API."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
from prepare_legacy_tools import prepare, digest
from command_logs import run_build_input_logged as run_logged

SOURCE = '9edcc246bc156d93dabd51970b5a832d7d53a511'
OUTPUT = 'native116-seed/lootablebodies-native116-world-fixtures-reobf.jar'
PROBE = 'integration-test-mod/lootablebodies-build-tests-reobf.jar'


def historical_runtime(project):
    """Load checksum-verified release tooling, not the 1.17 candidate's tooling."""
    root=project/'.gradle/legacy-tools/released-116'
    source=root/'source/tools/native_runtime.py'
    original=json.loads((root/'source.json').read_text())
    if original['commit'] != SOURCE or digest(source) != original['files']['tools/native_runtime.py']:
        raise RuntimeError('Missing or changed released 1.16 runtime tools; run prepare_native116_tools.py')
    spec=importlib.util.spec_from_file_location('released116_runtime', source)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--java17-home', type=Path, required=True)
    parser.add_argument('--java8-home', type=Path, required=True)
    parser.add_argument('--java25-home', type=Path, required=True)
    args=parser.parse_args()
    project=Path(__file__).resolve().parents[1]
    output=(args.output or project/'.gradle/legacy-tools/released-116').resolve()
    source=prepare(project, output, SOURCE)
    environment=dict(os.environ, JAVA_HOME=str(args.java17_home),
                     LOOTABLEBODIES_JAVA8_HOME=str(args.java8_home),
                     LOOTABLEBODIES_MAVENIZER_JAVA_HOME=str(args.java25_home))
    wrapper=source/('gradlew.bat' if os.name=='nt' else 'gradlew')
    if os.name!='nt': wrapper.chmod(0o755)
    run_logged([str(wrapper), 'prepareNative116WorldFixtures', 'assemble',
                '--init-script='+str(project/'gradle/native116-writer.init.gradle'),
                '-PhistoricalWriterSource='+str(project), '--no-daemon', '--console=plain'],
               cwd=source, env=environment, path=output/'build.log', timeout=1800)
    artifact=source/'build'/OUTPUT
    for path in (artifact,):
        if not path.is_file() or not path.stat().st_size: raise FileNotFoundError(path)
    files=[p for p in (project/'src/nativeSeed116').rglob('*') if p.is_file()]
    public_main=source/'build/libs/LootableBodies-2.4.7.116051.jar'
    public_sha='E545CF725650E8A01C5863A4C92BCB2D23437D61C9441C2F5D27A5272EDDA229'
    if digest(public_main)!=public_sha:raise RuntimeError('Native116 writer must use exact public E545CF main bytes')
    record={'public_main_sha256':public_sha,'source': SOURCE, 'artifact': OUTPUT, 'sha256': digest(artifact),
            'probe': 'pinned original hosted116 input archive; never rebuilt',
            'writer_sources':{p.relative_to(project).as_posix():digest(p) for p in files}}
    (output/'tools.json').write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    target=project/'build'/OUTPUT
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifact, target)
    print('Historical 1.16 writer built from released source '+SOURCE, flush=True)


if __name__=='__main__': main()
