"""Disposable official Forge 37 launches, separate from the development bootstrap."""
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
from runtime_diagnostics import run_monitored

FORGE = 'forge-1.17.1-37.1.1'
INSTALLER_SHA256 = '31F2319B1B9CA491AACCC020887240F8524A4FF2A8E0F8B795E17E889CBC043B'
SERVER_INPUTS = json.loads(Path(__file__).with_name('native117_server_inputs.json').read_text(encoding='utf-8'))
# Qualified by 200 actual packaged native37 resource reloads in four bounded
# JVMs, without this mod or a world. Ordinary gameplay retains native defaults.
WORKER_BUDGET_PROOF = json.loads(Path(__file__).with_name('native117_worker_budget.json').read_text(encoding='utf-8'))
if (WORKER_BUDGET_PROOF['minecraft'], WORKER_BUDGET_PROOF['forge'],
        WORKER_BUDGET_PROOF['reloads'], WORKER_BUDGET_PROOF['jvms'],
        WORKER_BUDGET_PROOF['native_preparation_workers']) != ('1.17.1', '37.1.1', 200, 4, 1):
    raise RuntimeError('Missing native37 worker-budget qualification')
HISTORICAL_JVM_ARGS = ('-XX:ActiveProcessorCount=2',)


def project_assets(project):
    folder='Library/Application Support/minecraft/assets' if platform.system()=='Darwin' else '.minecraft/assets'
    return project/'.gradle/minecraft-home'/folder


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fingerprint(directory):
    return {p.relative_to(directory).as_posix(): digest(p)
            for p in sorted(directory.rglob('*')) if p.is_file()}


def verify_server_inputs(distribution):
    """Pin raw inputs and seal both installer-generated archive contents.

    Only installer directory timestamps and ZIP entry order may vary. The
    returned hashes bind the actual SDK bytes prepared for every process.
    """
    from native_sdk_contents import verify_generated_archive
    generated=json.loads(Path(__file__).with_name('native117_generated_sdk.json').read_text())
    actual={}
    for name,expected in SERVER_INPUTS.items():
        artifact=Path(distribution)/name
        if artifact.is_symlink() or not artifact.is_file():raise FileNotFoundError('Missing native37 SDK input: '+name)
        value=digest(artifact)
        if value!=expected:
            seal=generated.get(name)
            if not seal or seal['canonical_archive_sha256']!=expected:
                raise RuntimeError('Incorrect official Forge37 server input: '+name)
            verify_generated_archive(artifact,seal)
            if digest(artifact)!=value:raise RuntimeError('Native SDK changed during verification: '+name)
        actual[name]=value
    return actual


def seed_loader_defaults(home, deterministic=False, include_default_path=True):
    """Seed an owned home; disable optional HTTP checks only for probes."""
    config=home/'config/fml.toml'
    config.parent.mkdir(parents=True, exist_ok=True)
    try:
        with config.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write('versionCheck = '+('false' if deterministic else 'true')+'\nsplashscreen = true\n'
                         'maxThreads = -1\n'+('defaultConfigPath = "defaultconfigs"\n' if include_default_path else ''))
    except FileExistsError:
        pass  # Retain existing choices, comments and even unreadable bytes.


def prepare_server(home, distribution, candidate=None, probe=None):
    home = home.resolve()
    distribution = distribution.resolve()
    libraries = distribution / 'libraries'
    if not libraries.is_dir():
        raise FileNotFoundError('Incomplete official Forge 37 server installation')
    verify_server_inputs(distribution)
    home.mkdir(parents=True, exist_ok=False)
    (home / 'mods').mkdir()
    seed_loader_defaults(home, deterministic=probe is not None)
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
    # Native37's flat server-property codec uses bare JsonOps and logs errors.
    # Existing copied headers remain authoritative. Fresh flat fixtures need
    # an explicitly prepared native-codec header before their first launch.
    (home / 'server.properties').write_text(
        'level-name=world\nlevel-seed=4267321\nlevel-type=DEFAULT\nonline-mode=false\n'
        'server-ip=127.0.0.1\nserver-port=0\ngenerate-structures=false\n'
        'spawn-animals=false\nspawn-monsters=false\nspawn-protection=0\n'
        'max-tick-time=120000\n', encoding='utf-8')
    for name in ('ops', 'whitelist', 'banned-players', 'banned-ips'):
        (home / (name + '.json')).write_text('[]\n', encoding='utf-8')


def run_java(java, home, phase, *, timeout=240, jvm_args=()):
    """Run one owned Forge JVM, retaining failure evidence before returning."""
    java = Path(java).resolve()
    home = home.resolve()
    release = java.parent.parent / 'release'
    if not release.is_file():
        raise RuntimeError('Native runtime requires exact Temurin 16.0.2+7 JDK metadata')
    version = dict(line.split('=', 1) for line in release.read_text(encoding='utf-8').splitlines() if '=' in line)
    if (version.get('SEMANTIC_VERSION') != '"16.0.2+7"' or
            version.get('IMPLEMENTOR') != '"Eclipse Foundation"'):
        raise RuntimeError('Native runtime requires exact Temurin 16.0.2+7')
    log = home / (phase + '.log')
    diagnostics = home / (phase + '.diagnostics')
    copies = [home / (phase + '.' + name) for name in ('latest.log', 'debug.log')]
    if log.exists() or diagnostics.exists() or any(p.exists() for p in copies):
        raise RuntimeError('Refusing stale runtime evidence for ' + phase)
    jstack = java.with_name('jstack.exe' if os.name == 'nt' else 'jstack')
    jstat = java.with_name('jstat.exe' if os.name == 'nt' else 'jstat')
    for tool in (jstack, jstat):
        if not tool.is_file():
            raise FileNotFoundError('Native runtime diagnostics require the pinned JDK tool: '+str(tool))
    # Relative output avoids the drive-letter delimiter in unified logging.
    gc = diagnostics.relative_to(home) / 'gc.txt'
    arguments = 'win_args.txt' if os.name == 'nt' else 'unix_args.txt'
    command = [str(java), '-Xlog:gc*,safepoint:file=' + gc.as_posix() + ':time,uptime,level,tags',
               *jvm_args, '@libraries/net/minecraftforge/forge/1.17.1-37.1.1/' + arguments, '--nogui']
    try:
        completed = run_monitored(command, cwd=home, log=log, timeout=timeout,
                                  diagnostics=diagnostics,
                                  diagnostic_command=[str(jstack), '-l'],
                                  metric_command=[str(jstat), '-gc'])
    finally:
        # Failed and timed-out phases need their complete native logs too.
        for name, destination in zip(('latest.log', 'debug.log'), copies):
            runtime_log = home / 'logs' / name
            if runtime_log.is_file():
                shutil.copy2(runtime_log, destination)
    return completed


def seed_flat_fixture(home, header, expected_sha256):
    """Copy one sealed native-codec header into a new owned fixture world."""
    import gzip
    from nbt_regions import decode
    home, header = Path(home).resolve(), Path(header).resolve()
    if not (home/'server.properties').is_file():
        raise RuntimeError('Prepare the owned server home before seeding its header')
    world = home/'world'
    if world.exists():
        raise FileExistsError('Refusing to overwrite an existing fixture world')
    if not header.is_file() or digest(header) != expected_sha256:
        raise RuntimeError('Native flat fixture header checksum mismatch')
    _, root = decode(gzip.decompress(header.read_bytes()))
    data = root.value.get('Data')
    try:
        version = data.value['DataVersion']
        generator = data.value['WorldGenSettings'].value['dimensions'].value[
            'minecraft:overworld'].value['generator'].value['type']
        if version.kind != 3 or version.value != 2730 or generator.kind != 8 or generator.value != 'minecraft:flat':
            raise ValueError('Wrong native version or generator')
    except (AttributeError, KeyError, ValueError) as error:
        raise RuntimeError('Expected an explicit native2730 flat codec header') from error
    world.mkdir()
    shutil.copy2(header, world/'level.dat')
    (home/'native-flat-header-input.json').write_text(json.dumps({
        'sha256':expected_sha256, 'native_data_version':2730, 'generator':'minecraft:flat',
        'only_header_copied':True}, indent=2)+'\n', encoding='utf-8')


def run_server(java, home, phase, *, timeout=240, extra=(), marker=None, message=None):
    home = home.resolve()
    log = home / (phase + '.log')
    marker = home / (marker or 'world/' + phase + '.pass')
    if marker.exists():
        raise RuntimeError('Refusing stale runtime evidence for ' + phase)
    completed = run_java(java, home, phase, timeout=timeout,
                         jvm_args=['-Xmx2G', '-Dlootablebodies.phase='+phase, *extra])
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
            request = urllib.request.Request(address, headers={'User-Agent': 'LootableBodiesRuntimeTests/2.4.7.116051'})
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


def client_inputs(runtime, prepared):
    from native117_client import load
    base, forge, record = load(runtime, prepared)
    return base, forge, [Path(p) for p in record['classpath']], Path(record['natives'])


def prepare_assets(runtime, cache=None):
    raise RuntimeError('Use the pinned native1.17 prepareMinecraftAssets task; inherited cache-copy hydration is disabled')


def prepare_client(home, candidate=None, probe=None):
    home.mkdir(parents=True, exist_ok=False)
    (home / 'mods').mkdir()
    seed_loader_defaults(home, deterministic=probe is not None)
    for artifact in (candidate, probe):
        if artifact is not None:
            if not artifact.is_file():
                raise FileNotFoundError(artifact)
            shutil.copy2(artifact, home / 'mods' / artifact.name)
    # These owned clients exercise graphics and inventory, not audio playback.
    (home / 'options.txt').write_text('realmsNotifications:false\npauseOnLostFocus:false\nrenderDistance:4\nmaxFps:30\nsoundCategory_master:0.0\n', encoding='utf-8')


def client_command(java, home, runtime, assets, *, username='LootableTest', extra=(), prepared=None):
    from native117_client import command
    if prepared is None:
        prepared=Path(__file__).resolve().parents[1]/'.gradle/native117-client-inputs'
    return command(java,home,runtime,assets,prepared,username=username,extra=extra)


def prepare_vanilla_server(home):
    """Stage the official pinned server where the native installer reads it."""
    from native117_client import BASE_VERSION,BASE_SHA1
    descriptor=home/'versions'/BASE_VERSION/(BASE_VERSION+'.json')
    _download({'sha1':BASE_SHA1,'url':'https://piston-meta.mojang.com/v1/packages/'+BASE_SHA1+'/1.17.1.json'},descriptor)
    metadata=json.loads(descriptor.read_text())
    if metadata['id']!=BASE_VERSION:raise RuntimeError('Wrong official server descriptor')
    relative='libraries/net/minecraft/server/'+BASE_VERSION+'/server-'+BASE_VERSION+'.jar'
    target=home/relative
    _download(metadata['downloads']['server'],target)
    if digest(target)!=SERVER_INPUTS[relative]:raise RuntimeError('Official vanilla server checksum mismatch')
    return target


def install(java, home, client=False):
    """Install the pinned native37 distribution into a new owned directory."""
    from prepare_migration_inputs import fetch
    home=home.resolve();home.mkdir(parents=True,exist_ok=False)
    installer=home/(FORGE+'-installer.jar')
    fetch('https://maven.minecraftforge.net/net/minecraftforge/forge/1.17.1-37.1.1/'+installer.name,installer,INSTALLER_SHA256)
    if client:
        from native117_client import BASE_VERSION,BASE_SHA1
        base=home/'versions'/BASE_VERSION/(BASE_VERSION+'.json')
        _download({'sha1':BASE_SHA1,'url':'https://piston-meta.mojang.com/v1/packages/'+BASE_SHA1+'/1.17.1.json'},base)
        metadata=json.loads(base.read_text());_download(metadata['downloads']['client'],base.with_suffix('.jar'))
        (home/'launcher_profiles.json').write_text('{"profiles":{}}\n')
    else:
        prepare_vanilla_server(home)
    run_logged([str(java),'-Djava.awt.headless=true','-jar',installer.name,
                '--installClient' if client else '--installServer',str(home)],
                cwd=home,path=home/'install.log',timeout=600)
    if client:
        from native117_client import prepare
        inputs=prepare(home,home/'prepared-inputs')['files']
    else:
        inputs=verify_server_inputs(home)
    (home/'runtime-inputs.json').write_text(json.dumps({'installer_sha256':INSTALLER_SHA256,'client':client,'files':inputs},indent=2)+'\n')
    return home


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--client',action='store_true')
    args=parser.parse_args();install(args.java.resolve(),args.output,args.client)
