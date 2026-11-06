"""Pinned native47 client preparation and descriptor-driven Java17 launch."""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import struct
import zipfile

BASE_VERSION = '1.20.1'
VERSION = '1.20.1-forge-47.4.28'
BASE_SHA1 = '72ae93b0934df5541860e3e6b72cf883c74da491'
FORGE_SHA256 = 'C8E1B6EB02F0D72E5BE8C55ACE50BC10AA2C20F082E41B0D32E7D16CC28B7B56'


def native_for_platform(library,system,architecture):
    parts=library['name'].split(':')
    if len(parts)<4 or not parts[3].startswith('natives-'):
        return True
    variants={('windows','amd64'):'natives-windows',('windows','x86'):'natives-windows-x86',
              ('windows','arm64'):'natives-windows-arm64',('linux','amd64'):'natives-linux',
              ('osx','amd64'):'natives-macos',('osx','arm64'):'natives-macos-arm64'}
    if (system,architecture) not in variants:
        raise RuntimeError('Unsupported native client platform: '+system+'/'+architecture)
    return parts[3]==variants[system,architecture]


def validate_native_image(content,system,architecture):
    if system!='windows':return
    machines={'amd64':0x8664,'x86':0x014c,'arm64':0xAA64}
    if architecture not in machines or len(content)<64 or content[:2]!=b'MZ':
        raise RuntimeError('Missing or malformed Windows native image')
    offset=struct.unpack_from('<I',content,0x3c)[0]
    if offset+6>len(content) or content[offset:offset+4]!=b'PE\0\0':
        raise RuntimeError('Malformed Windows native PE header')
    if struct.unpack_from('<H',content,offset+4)[0]!=machines[architecture]:
        raise RuntimeError('Native PE architecture differs from selected client platform')


def descriptors(runtime):
    runtime = Path(runtime).resolve()
    base = runtime/'versions'/BASE_VERSION/(BASE_VERSION+'.json')
    forge = runtime/'versions'/VERSION/(VERSION+'.json')
    if not base.is_file() or hashlib.sha1(base.read_bytes()).hexdigest() != BASE_SHA1:
        raise RuntimeError('Incorrect official native1.20.1 client descriptor')
    if not forge.is_file() or hashlib.sha256(forge.read_bytes()).hexdigest().upper() != FORGE_SHA256:
        raise RuntimeError('Incorrect installed native47 client descriptor')
    return json.loads(base.read_text()), json.loads(forge.read_text())


def prepare(runtime, output):
    """Download only missing official client inputs into a NEW owned root.

    The installer SDK and shared Gradle cache remain read-only. Its generated
    Forge modules are reused in place; no cache or installation is copied.
    """
    from native_runtime import allowed, _download, digest, validate_client_paths
    runtime, output = Path(runtime).resolve(), Path(output).resolve()
    base, forge = descriptors(runtime)
    output.mkdir(parents=True, exist_ok=False)
    system = {'Windows':'windows', 'Darwin':'osx', 'Linux':'linux'}[platform.system()]
    architecture = 'x86' if platform.architecture()[0] == '32bit' else 'amd64'
    natives = output/'natives'; natives.mkdir()
    modules, inputs = {}, {}

    def extract(archive):
        count=0
        with zipfile.ZipFile(archive) as jar:
            for entry in jar.infolist():
                if entry.is_dir() or entry.filename.startswith('META-INF/'):
                    continue
                if Path(entry.filename).suffix.lower() not in ('.dll','.so','.dylib','.jnilib'):
                    continue
                target=natives/Path(entry.filename).name;content=jar.read(entry)
                validate_native_image(content,system,architecture)
                if target.exists() and target.read_bytes()!=content:
                    raise RuntimeError('Conflicting native client input: '+target.name)
                target.write_bytes(content);count+=1
        if not count:raise RuntimeError('Native library archive has no platform payload: '+str(archive))

    def artifact(record, *, generated=False):
        relative = Path(record['path'])
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Unsafe descriptor artifact path')
        original = runtime/'libraries'/relative
        target = original if original.is_file() else output/'libraries'/relative
        if target == original or generated:
            if not target.is_file() or hashlib.sha1(target.read_bytes()).hexdigest() != record['sha1']:
                raise RuntimeError('Missing or changed installer input: '+str(original))
        else:
            _download(record, target)
        inputs[str(target)] = digest(target)
        return target

    for metadata in (base, forge):
        for library in metadata['libraries']:
            if not allowed(library, system, architecture) or not native_for_platform(library,system,architecture):
                continue
            downloads = library.get('downloads', {})
            if downloads.get('artifact'):
                path = artifact(downloads['artifact'], generated=metadata is forge)
                parts = library['name'].split(':')
                modules[':'.join(parts[:2]+parts[3:])] = path
                if len(parts)>3 and parts[3].startswith('natives-'):
                    extract(path)
            classifier = library.get('natives', {}).get(system)
            if classifier:
                classifier = classifier.replace('${arch}', '32' if architecture == 'x86' else '64')
                archive = artifact(downloads['classifiers'][classifier])
                extract(archive)
    client = runtime/'versions'/BASE_VERSION/(BASE_VERSION+'.jar')
    expected = base['downloads']['client']['sha1']
    if not client.is_file() or hashlib.sha1(client.read_bytes()).hexdigest() != expected:
        raise RuntimeError('Missing or changed official client jar')
    paths = list(modules.values())+[client]
    validate_client_paths(paths)
    for path in [client, *natives.iterdir(), runtime/'versions'/BASE_VERSION/(BASE_VERSION+'.json'), runtime/'versions'/VERSION/(VERSION+'.json')]:
        inputs[str(path)] = digest(path)
    record = {'version':VERSION, 'system':system, 'architecture':architecture,
              'sdk':str(runtime), 'library_directory':str(runtime/'libraries'),
              'classpath':[str(p) for p in paths], 'natives':str(natives), 'files':inputs}
    (output/'inputs.json').write_text(json.dumps(record,indent=2)+'\n')
    return record


def load(runtime, prepared):
    from native_runtime import digest, validate_client_paths
    base, forge = descriptors(runtime)
    record = json.loads((Path(prepared)/'inputs.json').read_text())
    if record['version'] != VERSION or Path(record['sdk']) != Path(runtime).resolve():
        raise RuntimeError('Prepared client belongs to another SDK')
    system = {'Windows':'windows', 'Darwin':'osx', 'Linux':'linux'}[platform.system()]
    architecture = 'x86' if platform.architecture()[0] == '32bit' else 'amd64'
    if (record['system'], record['architecture']) != (system, architecture):
        raise RuntimeError('Prepared client belongs to another platform')
    for name, expected in record['files'].items():
        if not Path(name).is_file() or digest(Path(name)) != expected:
            raise RuntimeError('Changed prepared native client input: '+name)
    paths = [Path(p) for p in record['classpath']]
    if any(str(path) not in record['files'] for path in paths):
        raise RuntimeError('Unsealed client classpath entry')
    validate_client_paths(paths)
    return base, forge, record


def expand(arguments, values, *, system, architecture, os_version):
    result = []
    for argument in arguments:
        if isinstance(argument, dict):
            permitted = not argument.get('rules')
            for rule in argument.get('rules', []):
                target = rule.get('os', {})
                matches = target.get('name',system) == system and target.get('arch',architecture) == architecture
                if 'version' in target:
                    matches = matches and re.search(target['version'], os_version) is not None
                for feature, expected in rule.get('features', {}).items():
                    matches = matches and {'is_demo_user':False,'has_custom_resolution':True}.get(feature,False) == expected
                if matches:
                    permitted = rule['action'] == 'allow'
            if not permitted:
                continue
            argument = argument['value']
        for part in argument if isinstance(argument,list) else [argument]:
            rendered = re.sub(r'\$\{([^}]+)\}',lambda match:values[match[1]],part)
            if '${' in rendered:
                raise RuntimeError('Unexpanded launcher argument')
            result.append(rendered)
    return result


def command(java, home, runtime, assets, prepared, *, username, extra=()):
    java, home, assets = Path(java).resolve(), Path(home).resolve(), Path(assets).resolve()
    release = dict(line.split('=',1) for line in (java.parent.parent/'release').read_text().splitlines() if '=' in line)
    if release.get('SEMANTIC_VERSION') != '"17.0.20.1+1"' or release.get('IMPLEMENTOR') != '"Eclipse Adoptium"':
        raise RuntimeError('Native client requires exact Temurin17.0.20.1+1')
    base, forge, record = load(runtime,prepared)
    index = assets/'indexes'/(base['assetIndex']['id']+'.json')
    if not index.is_file() or hashlib.sha1(index.read_bytes()).hexdigest() != base['assetIndex']['sha1']:
        raise RuntimeError('Incorrect official native1.20.1 asset index')
    values = {'version_name':VERSION,'library_directory':record['library_directory'],
              'classpath_separator':os.pathsep,'classpath':os.pathsep.join(record['classpath']),
              'natives_directory':record['natives'],'launcher_name':'LootableBodiesTests','launcher_version':'1',
              'auth_player_name':username,'game_directory':str(home),'assets_root':str(assets),
              'assets_index_name':base['assetIndex']['id'],'auth_uuid':'00000000-0000-4000-8000-000000000001',
              # Native45 explicitly selects OfflineSocialInteractions for0.
              'auth_access_token':'0','user_type':'legacy','version_type':'release',
              # Match native Main's explicit empty defaults for offline users.
              'clientid':'','auth_xuid':'',
              'resolution_width':'854','resolution_height':'480'}
    options = dict(system=record['system'],architecture=record['architecture'],os_version=platform.version())
    # The inherited profile's game version differs from the actual vanilla jar
    # filename. Forge's JVM ignoreList must exclude that precise base jar from
    # the transforming module layer, which already contains the native SRG jar.
    jvm_values = dict(values,version_name=BASE_VERSION)
    jvm = expand(base['arguments']['jvm']+forge['arguments']['jvm'],jvm_values,**options)
    game = expand(base['arguments']['game']+forge['arguments']['game'],values,**options)
    return [str(java),'-Dfile.encoding=UTF-8','-Xmx2G',*jvm,*extra,forge['mainClass'],*game]
