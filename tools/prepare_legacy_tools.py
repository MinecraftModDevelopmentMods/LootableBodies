"""Build historical writers from the released 1.12 source, outside native sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

SOURCE = 'ee8ee733fa091e961f27812ad780d56c35813f30'
REPOSITORY = 'https://github.com/MinecraftModDevelopmentMods/LootableBodies.git'
OUTPUTS = (
    'legacy-seed/lootablebodies-world-fixtures-reobf.jar',
    'transition-fixture/lootablebodies-111-transition-reobf.jar',
    'baseline-test/lootablebodies-original-112-checks-reobf.jar',
    'integration-test-mod/lootablebodies-build-tests-reobf.jar',
)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def prepare(project, output, commit=SOURCE):
    source = output / 'source'
    manifest = output / 'source.json'
    if source.exists():
        recorded = json.loads(manifest.read_text(encoding='utf-8'))
        if recorded['commit'] != commit or any(
                not (source / name).is_file() or digest(source / name) != expected
                for name, expected in recorded['files'].items()):
            raise RuntimeError('Historical source changed; preserve it and use a new output folder')
        return source
    output.mkdir(parents=True, exist_ok=False)
    if subprocess.run(['git', 'cat-file', '-e', commit], cwd=project,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        subprocess.run(['git', 'fetch', '--no-tags', REPOSITORY, commit], cwd=project, check=True)
    archive = output / 'released-source.tar'
    with archive.open('wb') as stream:
        subprocess.run(['git', 'archive', '--format=tar', commit], cwd=project,
                       stdout=stream, check=True)
    source.mkdir()
    files = {}
    with tarfile.open(archive) as tar:
        for entry in tar:
            name = PurePosixPath(entry.name)
            if name.is_absolute() or '..' in name.parts or not (entry.isfile() or entry.isdir()):
                raise RuntimeError('Unsafe historical source archive entry: ' + entry.name)
            destination = source.joinpath(*name.parts)
            if entry.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(tar.extractfile(entry).read())
                files[name.as_posix()] = digest(destination)
    manifest.write_text(json.dumps({'commit': commit, 'files': files}, indent=2) + '\n', encoding='utf-8')
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--java17-home', type=Path, required=True)
    parser.add_argument('--java8-home', type=Path, required=True)
    parser.add_argument('--java25-home', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    output = (args.output or project / '.gradle/legacy-tools/released-112').resolve()
    source = prepare(project, output)
    environment = dict(os.environ, JAVA_HOME=str(args.java17_home),
                       LOOTABLEBODIES_JAVA8_HOME=str(args.java8_home),
                       LOOTABLEBODIES_MAVENIZER_JAVA_HOME=str(args.java25_home))
    wrapper = source / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    if os.name != 'nt':
        wrapper.chmod(0o755)
    with (output / 'build.log').open('w', encoding='utf-8') as log:
        subprocess.run([str(wrapper), 'prepareLegacyWorldFixtures', 'prepareOriginal112Checks', 'preparePackagedIntegrationProbe',
                        '--init-script='+str(project/'gradle/historical-writers.init.gradle'),
                        '-PhistoricalWriterSource='+str(project),'--no-daemon', '--console=plain'], cwd=source, env=environment,
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
    hashes = {}
    for name in OUTPUTS:
        artifact = source / 'build' / name
        if not artifact.is_file() or not artifact.stat().st_size:
            raise FileNotFoundError(artifact)
        hashes[name] = digest(artifact)
    (output / 'tools.json').write_text(json.dumps({'source': SOURCE, 'artifacts': hashes, 'writer_sources': {p.relative_to(project).as_posix():digest(p) for p in (project/'src/legacySeed/java').rglob('*.java')}}, indent=2) + '\n', encoding='utf-8')
    print('Historical writer tools built from released source ' + SOURCE)


if __name__ == '__main__':
    main()
