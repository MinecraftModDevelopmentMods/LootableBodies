"""Disposable official Forge 61 launches, separate from the development bootstrap."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import zipfile
from command_logs import run_logged
from runtime_diagnostics import run_monitored,validate_logging_inputs

FORGE = 'forge-1.21.11-61.2.4'
INSTALLER_SHA256 = 'B1C3EE57BADED63F7591293134FF079B69CACEF3317D2E6783B28D92CFDC2BF4'
SERVER_INPUTS = json.loads(Path(__file__).with_name('native12111_server_inputs.json').read_text(encoding='utf-8'))
WORKER_BUDGET_PATH = Path(__file__).with_name('native12111_worker_budget.json')
WORKER_BUDGET_PROOF = json.loads(WORKER_BUDGET_PATH.read_text(encoding='utf-8')) if WORKER_BUDGET_PATH.is_file() else {}
# Forge 61 uses its actual default CPU/worker/GC configuration. A new target's
# history cannot borrow an older engine's resource qualification.
HISTORICAL_JVM_ARGS = ()


def require_historical_budget():
    if not WORKER_BUDGET_PATH.is_file():
        raise RuntimeError('Forge 61 historical runtime policy has not been qualified')
    proof=json.loads(WORKER_BUDGET_PATH.read_text(encoding='utf-8'))
    if tuple(proof.get(key) for key in ('minecraft','forge','reloads','jvms','qualified'))!=('1.21.11','61.2.4',200,4,True):
        raise RuntimeError('Forge 61 historical runtime policy has not been qualified')
    if proof.get('jvm_args')!=[]:
        raise RuntimeError('Unqualified Forge 61 worker or GC override')
    source=Path(__file__).resolve().parents[1]/'src/controlTest/java/buildcontrol/ResourceControl.java'
    manifest=Path(__file__).with_name('native12111_server_inputs.json')
    if proof.get('controller_sha256')!=digest(source) or proof.get('server_input_manifest_sha256')!=digest(manifest):
        raise RuntimeError('Forge 61 resource qualification source or SDK binding changed')
    if not __import__('re').fullmatch('[A-F0-9]{64}',proof.get('qualification_evidence_sha256','')):
        raise RuntimeError('Missing Forge 61 resource qualification identity')
    evidence=Path(__file__).resolve().parents[1]/'.gradle/native12111-resource-qualification/proof.json'
    if not evidence.is_file() or digest(evidence)!=proof['qualification_evidence_sha256']:
        raise RuntimeError('Missing or changed actual local Forge 61 resource qualification evidence')
    return HISTORICAL_JVM_ARGS


def project_assets(project):
    folder='Library/Application Support/minecraft/assets' if platform.system()=='Darwin' else '.minecraft/assets'
    return project/'.gradle/minecraft-home'/folder


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fingerprint(directory):
    return {p.relative_to(directory).as_posix(): digest(p)
            for p in sorted(directory.rglob('*')) if p.is_file()}


def verify_server_inputs(distribution):
    """Pin the exact completed SDK after both official installer sides.

    Forge 61 generated archives match the installer's declared expected hashes.
    No changed/truncated class, intermediate, resource or extra byte is accepted.
    """
    actual={}
    for name,expected in SERVER_INPUTS.items():
        artifact=Path(distribution)/name
        if artifact.is_symlink() or not artifact.is_file():raise FileNotFoundError('Missing Forge 61 SDK input: '+name)
        value=digest(artifact)
        if value!=expected:raise RuntimeError('Incorrect official Forge 61 server input: '+name)
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
        raise FileNotFoundError('Incomplete official Forge 61 server installation')
    verify_server_inputs(distribution)
    home.mkdir(parents=True, exist_ok=False)
    (home / 'mods').mkdir()
    shutil.copy2(distribution/(FORGE+'-shim.jar'),home/(FORGE+'-shim.jar'))
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
        'level-name=world\nlevel-seed=4267321\nlevel-type=minecraft\\:normal\nonline-mode=false\n'
        'server-ip=127.0.0.1\nserver-port=0\ngenerate-structures=false\n'
        'spawn-animals=false\nspawn-monsters=false\nspawn-protection=0\n'
        'max-tick-time=120000\n'
        # The official Windows runtime falls back to Java NIO. Select it
        # directly in disposable fixtures to avoid probing Linux/BSD libraries.
        + ('use-native-transport=false\n' if platform.system()=='Windows' else ''),
        encoding='utf-8')
    for name in ('ops', 'whitelist', 'banned-players', 'banned-ips'):
        (home / (name + '.json')).write_text('[]\n', encoding='utf-8')


def require_runtime_jdk(java):
    java=Path(java).resolve()
    release=java.parent.parent/'release'
    if not release.is_file():
        raise RuntimeError('Native runtime requires exact Temurin 21.0.7+6 JDK metadata')
    version=dict(line.split('=',1) for line in release.read_text(encoding='utf-8').splitlines() if '=' in line)
    if version.get('JAVA_RUNTIME_VERSION')!='"21.0.7+6-LTS"' or version.get('IMPLEMENTOR')!='"Eclipse Adoptium"':
        raise RuntimeError('Native runtime requires exact Temurin 21.0.7+6')
    return java


def run_java(java, home, phase, *, timeout=240, jvm_args=()):
    """Run one owned Forge JVM, retaining failure evidence before returning."""
    if phase=='abort' or phase.startswith('upgrade-'):require_historical_budget()
    java = require_runtime_jdk(java)
    home = home.resolve()
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
    command = [str(java), '-Dfile.encoding=UTF-8', '-Xms256m', '-Xmx2G', '-Xlog:gc*,safepoint:file=' + gc.as_posix() + ':time,uptime,level,tags',
               *jvm_args, '@libraries/net/minecraftforge/forge/1.21.11-61.2.4/' + arguments, '--nogui']
    validate_logging_inputs(command)
    def initial_diagnostics(process):
        # Short refusals and successful phases must retain real thread/heap
        # evidence too. This callback remains inside the unchanged deadline.
        gc_log = diagnostics / 'gc.txt'
        ready_by = time.monotonic() + min(5, timeout)
        while not (gc_log.is_file() and b'[gc,init]' in gc_log.read_bytes()):
            # The Windows launcher process can exist before jvm.dll loads.
            # An owned GC initialization record proves the JVM is attachable.
            if process.poll() is not None or time.monotonic() >= ready_by:
                raise RuntimeError('Owned JVM did not initialize before initial diagnostics')
            time.sleep(.025)
        for name, tool, option in [('initial-threads.txt',jstack,'-l'),('initial-heap-gc.txt',jstat,'-gc')]:
            with (diagnostics/name).open('x',encoding='utf-8') as output:
                subprocess.run([str(tool),option,str(process.pid)],stdout=output,
                               stderr=subprocess.STDOUT,timeout=8,check=True)
    try:
        completed = run_monitored(command, cwd=home, log=log, timeout=timeout,
                                  diagnostics=diagnostics,
                                  diagnostic_command=[str(jstack), '-l'],
                                  metric_command=[str(jstat), '-gc'],
                                  process_started=initial_diagnostics)
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
        if version.kind != 3 or version.value != 4671 or generator.kind != 8 or generator.value != 'minecraft:flat':
            raise ValueError('Wrong native version or generator')
    except (AttributeError, KeyError, ValueError) as error:
        raise RuntimeError('Expected an explicit native4671 flat codec header') from error
    world.mkdir()
    shutil.copy2(header, world/'level.dat')
    (home/'native-flat-header-input.json').write_text(json.dumps({
        'sha256':expected_sha256, 'native_data_version':4671, 'generator':'minecraft:flat',
        'only_header_copied':True}, indent=2)+'\n', encoding='utf-8')


def run_server(java, home, phase, *, timeout=240, extra=(), marker=None, message=None):
    home = home.resolve()
    log = home / (phase + '.log')
    marker = home / (marker or 'world/' + phase + '.pass')
    if marker.exists():
        raise RuntimeError('Refusing stale runtime evidence for ' + phase)
    completed = run_java(java, home, phase, timeout=timeout,
                         jvm_args=['-Xmx2G', '-Dlootablebodies.phase='+phase, *extra])
    text = log.read_text(encoding='utf-8')
    if completed.returncode or not marker.is_file() or (message and message not in text):
        raise RuntimeError('Native packaged ' + phase + ' failed: ' + str(log))
    if list((home / 'crash-reports').glob('*')):
        raise RuntimeError('Native runtime crash reports: ' + str(home))
    from native120_logs import audit
    audit(log)
    for name in ('latest.log', 'debug.log'):
        runtime_log = home / (phase+'.'+name)
        if not runtime_log.is_file() or not runtime_log.stat().st_size:
            raise FileNotFoundError('Missing complete native log: '+str(runtime_log))
        audit(runtime_log)
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
            request = urllib.request.Request(address, headers={'User-Agent': 'LootableBodiesRuntimeTests/2.4.14.121111'})
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
        # Forge 61's exact descriptor requires this dist-marker API archive.
        if (value.endswith('/net/minecraftforge/mergetool-api/1.0/mergetool-api-1.0.jar')
                and not path.is_symlink() and path.is_file()
                and digest(path) == '100F0C4D658B9DD73600CE4BB9C75657E0651F0F0F5E0B2F977F85D8F495350B'):
            continue
        if any(word in name for word in ('junit','mockito','opentest4j','slime-launcher','legacydev','mergetool','launchwrapper')) or '/mavenizer/' in value or re.match(r'asm(?:-debug-all|-commons|-tree|-util|-analysis)?-[0-5][.-]',name) or re.match(r'lwjgl(?:_util|-platform)?-2[.-]',name):
            raise RuntimeError('Development, testing or incompatible legacy library in packaged client: '+str(path))


def client_inputs(runtime, prepared):
    from native120_client import load
    base, forge, record = load(runtime, prepared)
    return base, forge, [Path(p) for p in record['classpath']], Path(record['natives'])


def prepare_assets(runtime, cache=None):
    raise RuntimeError('Use pinned native 1.21.11 assets; inherited cache-copy hydration is disabled')


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
    from native120_client import command
    if prepared is None:
        prepared=Path(__file__).resolve().parents[1]/'.gradle/native12111-client-inputs'
    return command(java,home,runtime,assets,prepared,username=username,extra=extra)


def install(java, home, client=False):
    """Install Forge 61 locally; its installer owns the server downloads."""
    from prepare_migration_inputs import fetch
    home=home.resolve();home.mkdir(parents=True,exist_ok=False)
    installer=home/(FORGE+'-installer.jar')
    fetch('https://maven.minecraftforge.net/net/minecraftforge/forge/1.21.11-61.2.4/'+installer.name,installer,INSTALLER_SHA256)
    if client:
        from native120_client import BASE_VERSION,BASE_SHA1
        base=home/'versions'/BASE_VERSION/(BASE_VERSION+'.json')
        _download({'sha1':BASE_SHA1,'url':'https://piston-meta.mojang.com/v1/packages/'+BASE_SHA1+'/1.21.11.json'},base)
        metadata=json.loads(base.read_text());_download(metadata['downloads']['client'],base.with_suffix('.jar'))
        (home/'launcher_profiles.json').write_text('{"profiles":{}}\n')
    run_logged([str(java),'-Djava.awt.headless=true','-jar',installer.name,
                '--installClient' if client else '--installServer',str(home)],
                cwd=home,path=home/'install.log',timeout=600)
    if client:
        from native120_client import prepare
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
