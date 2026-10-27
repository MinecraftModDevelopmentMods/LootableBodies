"""Share sealed test inputs across bounded historical-world shards, never caches."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import time
from types import SimpleNamespace
from verify_world_upgrades import ORIGINS, PHASES, fingerprint, patch_stamps, verify
from release_identity import MAIN_JAR


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def partition(origins, shard, total=6):
    if total < 1 or not 0 <= shard < total or len(origins) != len(set(origins)):
        raise ValueError('Invalid or duplicate migration shard')
    return list(origins)[shard::total]


def safe_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or path.parts[:1] != ('build',) or '\\' in name:
        raise ValueError('Only explicit build test inputs may be transferred: ' + name)
    return path.as_posix()


def seal(project, paths, output, commit):
    if not re.fullmatch('[a-f0-9]{40}', commit):
        raise ValueError('Exact input commit is required')
    paths = [safe_name(str(name)) for name in paths]
    if not paths or len(paths) != len(set(paths)):
        raise ValueError('Missing or duplicate prepared inputs')
    files = {}
    for name in paths:
        path = project / name
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(project.resolve()):
            raise ValueError('Missing or external prepared input: ' + name)
        files[name] = {'sha256': digest(path), 'size': path.stat().st_size}
    output.mkdir(parents=True, exist_ok=False)
    archive = output / 'inputs.tar.gz'
    with tarfile.open(archive, 'w:gz') as stream:
        for name in sorted(files):
            stream.add(project / name, arcname=name, recursive=False)
    suite = {p.relative_to(project).as_posix():digest(p) for p in sorted((project/'tools').glob('*.py'))}
    record = {'head': commit, 'archive_sha256': digest(archive), 'files': files, 'tool_sources':suite}
    (output / 'inputs.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


def restore(project, folder, commit):
    record = json.loads((folder / 'inputs.json').read_text())
    archive = folder / 'inputs.tar.gz'
    if record['head'] != commit or digest(archive) != record['archive_sha256']:
        raise ValueError('Stale commit or changed prepared archive')
    suite = {p.relative_to(project).as_posix():digest(p) for p in sorted((project/'tools').glob('*.py'))}
    if suite != record['tool_sources']:
        raise ValueError('Test tools differ from the sealed preparation')
    with tarfile.open(archive, 'r:gz') as stream:
        members = stream.getmembers()
        if len(members) != len(record['files']) or {m.name for m in members} != set(record['files']):
            raise ValueError('Prepared archive contents differ from manifest')
        for member in members:
            name = safe_name(member.name)
            target = project / name
            expected = record['files'][name]
            if not member.isfile() or member.size != expected['size'] or not target.resolve().is_relative_to(project.resolve()):
                raise ValueError('Invalid prepared input: ' + name)
            data = stream.extractfile(member).read()
            if hashlib.sha256(data).hexdigest().upper() != expected['sha256']:
                raise ValueError('Changed prepared bytes: ' + name)
            if target.exists() and (not target.is_file() or target.is_symlink() or digest(target) != expected['sha256']):
                raise ValueError('Refusing to replace different existing bytes: ' + name)
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                target.write_bytes(data)
    return record


def aggregate(reports, origins, commit):
    expected = {origin + '-' + variant for origin in origins for variant in ['normal', 'stamps']}
    cases = {}; aborts = {}; seals = {}; identities = set()
    for report in reports:
        if report['head'] != commit:
            raise ValueError('Stale shard commit')
        identity = (report['candidate_sha256'], report['probe_sha256'])
        if not all(re.fullmatch('[A-F0-9]{64}', value) for value in identity):
            raise ValueError('Invalid candidate or probe checksum')
        identities.add(identity)
        if cases.keys() & report['cases'].keys() or aborts.keys() & report['aborts'].keys() or seals.keys() & report['source_seals'].keys():
            raise ValueError('Duplicate historical evidence')
        cases.update(report['cases']); aborts.update(report['aborts']); seals.update(report['source_seals'])
    if len(identities) != 1 or set(cases) != expected or set(aborts) != expected or set(seals) != set(origins):
        raise ValueError('Missing cases or mismatched candidate/source evidence')
    if not all(isinstance(files, dict) and files and all(re.fullmatch('[A-F0-9]{64}', sha) for sha in files.values()) for files in seals.values()):
        raise ValueError('Missing or invalid source fingerprints')
    for origin in origins:
        for variant in ['normal', 'stamps']:
            name = origin + '-' + variant; case = cases[name]
            if case['phases'] != list(PHASES) or case['source_unchanged'] is not True or case['origin_jar_sha256'] != ORIGINS[origin][2]:
                raise ValueError('Incomplete historical qualification: ' + name)
            conditions = aborts[name]['conditions']
            if set(conditions) != {'damaged-index', 'blocked-journal'} or not all(
                    value['source_unchanged'] is True and value['world_data_unchanged'] is True and value['world_loading_started'] is False
                    for value in conditions.values()):
                raise ValueError('Unsafe preservation contract failed: ' + name)
    candidate, probe = next(iter(identities))
    return {'head':commit, 'candidate_sha256':candidate, 'probe_sha256':probe, 'cases':cases,
            'aborts':aborts, 'source_seals':seals, 'jvm_phases':sum(len(c['phases']) for c in cases.values()),
            'abort_cases':sum(len(c['conditions']) for c in aborts.values()), 'all_sources_unchanged':True}


def run_shard(project, java, shard, commit):
    started = time.monotonic()
    origins = partition(list(ORIGINS), shard)
    seeds = project / 'build/migration-seeds'
    seed_report = json.loads((seeds / 'seed-report.json').read_text())['origins']
    manifest = json.loads((project / 'build/migration-ci-inputs/inputs.json').read_text())
    if manifest['head'] != commit:
        raise ValueError('Prepared commit does not match this shard')
    inputs = ['build/libs/' + MAIN_JAR, 'build/integration-test-mod/lootablebodies-build-tests-reobf.jar']
    for name in inputs:
        if digest(project / name) != manifest['files'][name]['sha256']:
            raise ValueError('Prepared candidate/probe changed')
    args = SimpleNamespace(java=java, origins=origins, variants=['normal','stamps'], seeds=[seeds],
        forge115_home=project/'build/native-server-distribution', output=project/'build/migration-results')
    verify(args, project)
    abort_root = project / 'build/migration-abort';abort_root.mkdir(exist_ok=False)
    aborts = {}
    for origin in origins:
        source = seeds / origin / 'world'
        for variant in ['normal','stamps']:
            name = origin + '-' + variant; selected = source; changes = []
            if variant == 'stamps':
                selected = abort_root / 'stamp-sources' / name / 'world'
                shutil.copytree(source, selected);changes = patch_stamps(selected, abort_root)
            with (abort_root / (name + '.log')).open('x') as log:
                subprocess.run([sys.executable,str(project/'tools/verify_upgrade_abort.py'),'--java',str(java),
                    '--forge-home',str(args.forge115_home),'--source-world',str(selected),
                    '--output',str(abort_root/name)],cwd=project,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
            result = json.loads((abort_root/name/'result.json').read_text())
            aborts[name] = {'conditions':result['cases'], 'stamp_mutations':changes}
            (abort_root/'result.json').write_text(json.dumps({'cases':aborts},indent=2)+'\n')
        if fingerprint(source) != seed_report[origin]['world']:
            raise ValueError('Historical source changed: ' + origin)
    positive = json.loads((args.output/'result.json').read_text())
    for name in inputs:
        if digest(project/name) != manifest['files'][name]['sha256']:
            raise ValueError('Candidate/probe changed during qualification')
    report = dict(positive, head=commit, aborts=aborts, source_seals={origin:seed_report[origin]['world'] for origin in origins},
                  elapsed_seconds=round(time.monotonic()-started,3))
    aggregate([report],origins,commit)
    destination = project/'build/migration-ci/report.json';destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(report,indent=2)+'\n')
    print('MIGRATION_SHARD_PASS',shard,'cases='+str(len(report['cases'])),'seconds='+str(report['elapsed_seconds']),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode',choices=['seal','restore','shard','aggregate'])
    parser.add_argument('--commit',required=True)
    parser.add_argument('--java',type=Path)
    parser.add_argument('--shard',type=int)
    parser.add_argument('--folder',type=Path,default=Path('build/migration-ci-inputs'))
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip()
    if actual != args.commit:raise ValueError('Checkout does not match the declared input commit')
    if args.mode=='seal':
        paths=['build/libs/'+MAIN_JAR,'build/integration-test-mod/lootablebodies-build-tests-reobf.jar','build/migration-seeds/seed-report.json']
        native=project/'build/native-server-distribution'
        paths += [p.relative_to(project).as_posix() for p in native.glob('*.jar') if 'installer' not in p.name]
        paths += [p.relative_to(project).as_posix() for p in (native/'libraries').rglob('*.jar')]
        report=json.loads((project/'build/migration-seeds/seed-report.json').read_text())['origins']
        if set(report)!=set(ORIGINS):raise ValueError('Missing prepared historical worlds')
        for origin,details in report.items():
            world=project/'build/migration-seeds'/origin/'world'
            if fingerprint(world)!=details['world']:raise ValueError('Changed prepared source')
            paths += [(world/name).relative_to(project).as_posix() for name in details['world']]
        seal(project,paths,args.folder,args.commit)
    elif args.mode=='restore':restore(project,args.folder,args.commit)
    elif args.mode=='shard':
        if args.java is None or args.shard is None:parser.error('--java and --shard are required')
        run_shard(project,args.java.resolve(),args.shard,args.commit)
    else:
        reports=[json.loads(p.read_text()) for p in sorted(args.folder.glob('*/migration-ci/report.json'))]
        if len(reports)!=6:raise ValueError('All six migration shards are required')
        result=aggregate(reports,list(ORIGINS),args.commit)
        output=project/'build/migration-summary.json';output.write_text(json.dumps(result,indent=2)+'\n')
        print('MIGRATION_MATRIX_PASS cases='+str(len(result['cases']))+' phases='+str(result['jvm_phases'])+' unsafe_aborts='+str(result['abort_cases']))


if __name__=='__main__':main()
