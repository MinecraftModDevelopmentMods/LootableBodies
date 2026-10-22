"""Fetch checksum-pinned public legacy jars and isolated Forge server distributions."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import http.client
import time
import urllib.error
import urllib.request

MODS = {
    'original-110.jar': ('https://mediafilez.forgecdn.net/files/2323/108/DrCyanosLootableBodies_1.10.2-2.2.0.jar',
                         '688AFBBF4485D1F3746C68903C9E0885D57F42CCFBFA0CB4A0FC42982C0B3F2B'),
    'released-110.jar': ('https://github.com/MinecraftModDevelopmentMods/LootableBodies/releases/download/2.4.1.110021/LootableBodies-2.4.1.110021.jar',
                         '7F9F0EE7BF09F943ED85398982EB7C2207F3DD55B75E9E92A7531C393BE1045B'),
    'original-111.jar': ('https://mediafilez.forgecdn.net/files/2529/838/DrCyanosLootableBodies-1.11.2-2.3.0.jar',
                         'C82CC329BA53A218CADB681D646F64A9C457341577BC4F9AA07E2F9926434CCF'),
}
DISTRIBUTIONS = {
    'forge110': ('1.10.2', '12.18.3.2511',
                 '664393C5A924B8F06CFB953903ABCF9FE5E99ECBE6AF8C7EA217339C766E519C',
                 '809C2EA5B4D4F3E5A294D3A326744F20FEC412A1263E708297D00F512E377542',
                 '3d501b23df53c548254f5e3f66492d178a48db63'),
    'forge111': ('1.11.2', '13.20.1.2588',
                 '00AEA3DDF1E78BBB4D5D05E8C22FB3E849DE09B47D828E57DAD373A16BB8A26F',
                 'B6DFF35B5CCB0E6353FB89B5330FE930DB8FBE8B63787C9FC2F70196BD108998',
                 'f00c294a1576e03fddcac777c3cf4c7d404c4ba4'),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fetch(url, path, expected):
    payload = None
    for address in [url, url + '?source=lootablebodies-migration', url + '?source=lootablebodies111']:
        try:
            print('MIGRATION_INPUT_FETCH',path.name,flush=True)
            request = urllib.request.Request(address, headers={'User-Agent': 'LootableBodiesMigrationTests/2.4.2.111021'})
            with urllib.request.urlopen(request, timeout=30) as response:
                chunks=[]; started=time.monotonic()
                while True:
                    part=response.read1(65536)
                    if not part: break
                    chunks.append(part)
                    if time.monotonic()-started>120: raise TimeoutError('Artifact transfer exceeded two minutes')
                payload=b''.join(chunks)
            break
        except urllib.error.HTTPError as error:
            if error.code not in (403, 429, 500, 502, 503, 504):
                raise
        except (TimeoutError,urllib.error.URLError,http.client.IncompleteRead) as error:
            print('MIGRATION_INPUT_RETRY',path.name,type(error).__name__,flush=True)
    if payload is None or hashlib.sha256(payload).hexdigest().upper() != expected:
        raise RuntimeError('Missing artifact or checksum mismatch: ' + path.name)
    path.write_bytes(payload)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    report = {}
    for name, (url, expected) in MODS.items():
        path = root / name
        fetch(url, path, expected)
        report[name] = {'source': url, 'sha256': sha(path)}
    for name, (minecraft, forge, installer_sha, universal_sha, server_sha) in DISTRIBUTIONS.items():
        folder = root / name
        folder.mkdir()
        prefix = f'forge-{minecraft}-{forge}'
        installer = folder / (prefix + '-installer.jar')
        url = f'https://maven.minecraftforge.net/net/minecraftforge/forge/{minecraft}-{forge}/{installer.name}'
        fetch(url, installer, installer_sha)
        with (folder / 'install.log').open('w', encoding='utf-8') as log:
            subprocess.run([str(args.java.resolve()), '-Djava.awt.headless=true', '-jar', installer.name, '--installServer'],
                           cwd=folder, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        universal = folder / (prefix + '-universal.jar')
        server = folder / f'minecraft_server.{minecraft}.jar'
        if sha(universal) != universal_sha or hashlib.sha1(server.read_bytes()).hexdigest() != server_sha:
            raise RuntimeError('Installed Forge/Minecraft artifact checksum mismatch')
        report[name] = {'installer_sha256': installer_sha, 'universal_sha256': universal_sha, 'server_sha1': server_sha}
    (root / 'inputs.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('MIGRATION_INPUTS_VERIFIED', flush=True)


if __name__ == '__main__':
    main()
