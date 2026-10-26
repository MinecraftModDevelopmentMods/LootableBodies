"""Disposable official Forge 28 launches, separate from the development bootstrap."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import urllib.error
import urllib.request
import zipfile
from command_logs import run_logged

FORGE = 'forge-1.14.4-28.2.26'
INSTALLER_SHA256 = 'E68A0E2D589B2954C5D109A04B80747AC8C061ACABE074DB1607F9C2FD7D2CE1'
SERVER_INPUTS = {'forge-1.14.4-28.2.26.jar': '3917C2F46E0AE4D93C007156A850B0CA2B6586649C6B4D3D8AC8D58D74801E01', 'libraries/net/minecraftforge/forge/1.14.4-28.2.26/forge-1.14.4-28.2.26-server.jar': 'C7736D4541B97166FCEF9FE2A1B42AD96D2FF8CFEB63C53F83B18658A6B596CE', 'minecraft_server.1.14.4.jar': '5ECDEDAB3A6E129321A444490D0A467C25EA702A24A99CEBE3B6AED41F8F5729'}


def project_assets(project):
    folder='Library/Application Support/minecraft/assets' if platform.system()=='Darwin' else '.minecraft/assets'
    return project/'.gradle/minecraft-home'/folder


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fingerprint(directory):
    return {p.relative_to(directory).as_posix(): digest(p)
            for p in sorted(directory.rglob('*')) if p.is_file()}


def prepare_server(home, distribution, candidate=None, probe=None):
    home = home.resolve()
    distribution = distribution.resolve()
    launcher = distribution / (FORGE + '.jar')
    libraries = distribution / 'libraries'
    if not launcher.is_file() or not libraries.is_dir():
        raise FileNotFoundError('Incomplete official Forge 28 server installation')
    for name, expected in SERVER_INPUTS.items():
        artifact = distribution / name
        if not artifact.is_file() or digest(artifact) != expected:
            raise RuntimeError('Incorrect official Forge 28 server input: ' + name)
    home.mkdir(parents=True, exist_ok=False)
    (home / 'mods').mkdir()
    shutil.copy2(launcher, home / launcher.name)
    # Share this disposable installation's immutable libraries, not a user
    # installation or Gradle cache. Every matrix run fingerprints the inputs.
    link = home / 'libraries'
    if os.name == 'nt':
        environment = dict(os.environ, LOOTABLE_TEST_LINK=str(link),
                           LOOTABLE_TEST_LIBRARIES=str(libraries))
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                        'New-Item -ItemType Junction -Path $env:LOOTABLE_TEST_LINK '
                        '-Target $env:LOOTABLE_TEST_LIBRARIES | Out-Null'],
                       env=environment, check=True, capture_output=True)
    else:
        link.symlink_to(libraries, target_is_directory=True)
    for artifact in (candidate, probe):
        if artifact is not None:
            if not artifact.is_file():
                raise FileNotFoundError(artifact)
            shutil.copy2(artifact, home / 'mods' / artifact.name)
    (home / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (home / 'server.properties').write_text(
        'level-name=world\nlevel-seed=4267321\nlevel-type=FLAT\nonline-mode=false\n'
        'server-ip=127.0.0.1\nserver-port=0\ngenerate-structures=false\n'
        'spawn-animals=false\nspawn-monsters=false\nspawn-protection=0\n'
        'max-tick-time=120000\n', encoding='utf-8')
    for name in ('ops', 'whitelist', 'banned-players', 'banned-ips'):
        (home / (name + '.json')).write_text('[]\n', encoding='utf-8')


def run_server(java, home, phase, *, timeout=240, extra=(), marker=None, message=None):
    home = home.resolve()
    log = home / (phase + '.log')
    marker = home / (marker or 'world/' + phase + '.pass')
    if marker.exists() or log.exists():
        raise RuntimeError('Refusing stale runtime evidence for ' + phase)
    command = [str(java), '-Xmx2G', '-Dlootablebodies.phase=' + phase, *extra,
               '-jar', FORGE + '.jar', '--nogui']
    with log.open('w', encoding='utf-8') as output:
        completed = subprocess.run(command, cwd=home, stdout=output,
                                   stderr=subprocess.STDOUT, timeout=timeout)
    text = log.read_text(encoding='utf-8', errors='replace')
    if completed.returncode or not marker.is_file() or (message and message not in text):
        raise RuntimeError('Native packaged ' + phase + ' failed: ' + str(log))
    if list((home / 'crash-reports').glob('*')):
        raise RuntimeError('Native runtime crash reports: ' + str(home))
    from audit_runtime_logs import audit
    audit(log,native=True)
    for name in ('latest.log', 'debug.log'):
        runtime_log = home / 'logs' / name
        if runtime_log.is_file():
            shutil.copy2(runtime_log, home / (phase + '.' + name))
            audit(runtime_log,native=True)
    return log


def _download(artifact, destination):
    expected = artifact['sha1']
    if destination.is_file() and hashlib.sha1(destination.read_bytes()).hexdigest() == expected:
        return
    if not artifact.get('url'):
        raise FileNotFoundError('Missing installer-generated runtime input: ' + str(destination))
    destination.parent.mkdir(parents=True, exist_ok=True)
    for address in (artifact['url'], artifact['url'] + '?source=lootablebodies-runtime'):
        try:
            request = urllib.request.Request(address, headers={'User-Agent': 'LootableBodiesRuntimeTests/2.4.5.114041'})
            with urllib.request.urlopen(request, timeout=60) as response:
                content = response.read()
            if hashlib.sha1(content).hexdigest() != expected:
                raise RuntimeError('Official runtime input checksum mismatch: ' + str(destination))
            destination.write_bytes(content)
            return
        except urllib.error.HTTPError as error:
            if error.code not in (403, 429, 500, 502, 503, 504):
                raise
    raise RuntimeError('Official runtime input unavailable: ' + artifact['url'])


def allowed(library, system, architecture):
    rules = library.get('rules', [])
    result = not rules
    for rule in rules:
        os_rule = rule.get('os', {})
        matches = os_rule.get('name', system) == system
        if 'arch' in os_rule:
            matches = matches and os_rule['arch'] == architecture
        if rule.get('features'):
            matches = False
        if matches:
            result = rule['action'] == 'allow'
    return result


def validate_client_paths(paths):
    import re
    for path in paths:
        value=str(path).replace('\\','/').lower();name=path.name.lower()
        if any(word in name for word in ('junit','mockito','opentest4j','slime-launcher','legacydev','mergetool','launchwrapper')) or '/mavenizer/' in value or re.match(r'asm(?:-debug-all|-commons|-tree|-util|-analysis)?-[0-5][.-]',name) or re.match(r'lwjgl(?:_util|-platform)?-2[.-]',name):
            raise RuntimeError('Development, testing or incompatible legacy library in packaged client: '+str(path))


def client_inputs(runtime):
    runtime = runtime.resolve()
    base_path = runtime / 'versions/1.14.4/1.14.4.json'
    if not base_path.is_file() or hashlib.sha1(base_path.read_bytes()).hexdigest() != 'be146d5f66a3627ed0a87c234c4d8dde8ab35098':
        raise RuntimeError('Missing or incorrect official Minecraft 1.14.4 descriptor')
    base = json.loads(base_path.read_text(encoding='utf-8'))
    forge = json.loads((runtime / 'versions/1.14.4-forge-28.2.26/1.14.4-forge-28.2.26.json').read_text(encoding='utf-8'))
    system = {'Windows': 'windows', 'Darwin': 'osx', 'Linux': 'linux'}[platform.system()]
    architecture = 'x86' if platform.architecture()[0] == '32bit' else 'amd64'
    native_directory = runtime / ('natives-' + system + '-' + architecture)
    native_directory.mkdir(exist_ok=True)
    modules = {}
    for metadata in (base, forge):
        for library in metadata['libraries']:
            if not allowed(library, system, architecture):
                continue
            downloads = library.get('downloads', {})
            artifact = downloads.get('artifact')
            if artifact:
                path = runtime / 'libraries' / artifact['path']
                _download(artifact, path)
                parts = library['name'].split(':')
                key = ':'.join(parts[:2] + parts[3:])
                modules[key] = path
            classifier = library.get('natives', {}).get(system)
            if classifier:
                classifier = classifier.replace('${arch}', '32' if architecture == 'x86' else '64')
                native = downloads['classifiers'][classifier]
                archive = runtime / 'libraries' / native['path']
                _download(native, archive)
                with zipfile.ZipFile(archive) as jar:
                    for entry in jar.infolist():
                        if entry.is_dir() or entry.filename.startswith('META-INF/'):
                            continue
                        if Path(entry.filename).suffix.lower() not in ('.dll', '.so', '.dylib', '.jnilib'):
                            continue
                        target = native_directory / Path(entry.filename).name
                        content = jar.read(entry)
                        if target.exists():
                            if target.read_bytes() != content:
                                raise RuntimeError('Conflicting native runtime input: ' + target.name)
                        else:
                            target.write_bytes(content)
    base_jar = runtime / 'versions/1.14.4/1.14.4.jar'
    _download(base['downloads']['client'], base_jar)
    paths = list(modules.values()) + [base_jar]
    validate_client_paths(paths)
    return base, forge, paths, native_directory


def prepare_assets(runtime, cache=None):
    """Hydrate owned assets from official hashes, reusing verified cache objects."""
    from concurrent.futures import ThreadPoolExecutor
    runtime = runtime.resolve()
    base = json.loads((runtime / 'versions/1.14.4/1.14.4.json').read_text(encoding='utf-8'))
    assets = runtime / 'assets'
    index = assets / 'indexes/1.14.json'
    _download(base['assetIndex'], index)
    objects = json.loads(index.read_text(encoding='utf-8'))['objects']
    unique = {item['hash'] for item in objects.values()}

    def hydrate(checksum):
        relative = checksum[:2] + '/' + checksum
        destination = assets / 'objects' / relative
        if not destination.exists() and cache is not None:
            previous = cache / 'objects' / relative
            if previous.is_file() and hashlib.sha1(previous.read_bytes()).hexdigest() == checksum:
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(previous, destination)
        _download({'sha1': checksum, 'url': 'https://resources.download.minecraft.net/' + relative}, destination)

    with ThreadPoolExecutor(max_workers=8) as pool:
        for count, _ in enumerate(pool.map(hydrate, sorted(unique)), 1):
            if count % 250 == 0:
                print('Verified native asset objects', count, '/', len(unique), flush=True)
    print('Official native assets ready:', len(unique), 'objects', flush=True)
    return assets


def prepare_client(home, candidate=None, probe=None):
    home.mkdir(parents=True, exist_ok=False)
    (home / 'mods').mkdir()
    for artifact in (candidate, probe):
        if artifact is not None:
            if not artifact.is_file():
                raise FileNotFoundError(artifact)
            shutil.copy2(artifact, home / 'mods' / artifact.name)
    # These owned clients exercise graphics and inventory, not audio playback.
    (home / 'options.txt').write_text('realmsNotifications:false\npauseOnLostFocus:false\nrenderDistance:4\nmaxFps:30\nsoundCategory_master:0.0\n', encoding='utf-8')


def client_command(java, home, runtime, assets, *, username='LootableTest', extra=()):
    base, forge, paths, natives = client_inputs(runtime)
    index = assets / 'indexes/1.14.json'
    if not index.is_file() or hashlib.sha1(index.read_bytes()).hexdigest() != base['assetIndex']['sha1']:
        raise RuntimeError('Missing or incorrect official Minecraft asset index')
    return [str(java), '-Xmx2G', '-Djava.library.path=' + str(natives),
            '-DlibraryDirectory=' + str(runtime.resolve() / 'libraries'), *extra,
            '-cp', os.pathsep.join(str(path) for path in paths), forge['mainClass'],
            *forge['arguments']['game'], '--username', username,
            '--version', '1.14.4-forge-28.2.26', '--gameDir', str(home.resolve()),
            '--assetsDir', str(assets.resolve()), '--assetIndex', '1.14',
            '--uuid', '00000000-0000-4000-8000-000000000001', '--accessToken', 'disposable-test',
            '--userType', 'legacy', '--versionType', 'release', '--width', '854', '--height', '480']


def install(java,home,client=False):
    """Install an official pinned distribution into a new, owned directory."""
    from prepare_migration_inputs import fetch
    home=home.resolve();home.mkdir(parents=True,exist_ok=False)
    installer=home/(FORGE+'-installer.jar')
    fetch('https://maven.minecraftforge.net/net/minecraftforge/forge/1.14.4-28.2.26/'+installer.name,installer,INSTALLER_SHA256)
    if client:
        base=home/'versions/1.14.4/1.14.4.json'
        _download({'sha1':'be146d5f66a3627ed0a87c234c4d8dde8ab35098','url':'https://piston-meta.mojang.com/v1/packages/be146d5f66a3627ed0a87c234c4d8dde8ab35098/1.14.4.json'},base)
        metadata=json.loads(base.read_text());_download(metadata['downloads']['client'],base.with_suffix('.jar'))
        (home/'launcher_profiles.json').write_text('{"profiles":{}}\n')
    run_logged([str(java),'-Djava.awt.headless=true','-jar',installer.name,
                        '--installClient' if client else '--installServer',str(home)],
                       cwd=home,path=home/'install.log',timeout=600)
    if client:
        base,forge,paths,natives=client_inputs(home)
        inputs={str(path.relative_to(home)):digest(path) for path in paths}
    else:
        inputs={name:digest(home/name) for name in SERVER_INPUTS}
        if inputs!=SERVER_INPUTS:raise RuntimeError('Official Forge server installation checksum mismatch')
    (home/'runtime-inputs.json').write_text(json.dumps({'installer_sha256':INSTALLER_SHA256,'client':client,'files':inputs},indent=2)+'\n')
    return home


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--client',action='store_true')
    args=parser.parse_args();install(args.java.resolve(),args.output,args.client)
