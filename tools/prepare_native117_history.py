"""Carry original sealed 1.16 inputs into native 1.17 qualification, never caches."""
import argparse,hashlib,importlib.util,json,os,shutil,subprocess,sys,tarfile
from pathlib import Path,PurePosixPath

SOURCE='9edcc246bc156d93dabd51970b5a832d7d53a511'
PRIOR_RUN=40909860288
PRIOR_MANIFEST='1AE69CAD824DF6AD61A90715E5BEDC158B7A14EE311D59ECF7C4A9EE023B3AEC'
PRIOR_ARCHIVE='6D3A380A7998E1DE756E9CBCF3C345FE3F862117B0697935D91E440E1E21E6F1'
PUBLIC_MAIN='E545CF725650E8A01C5863A4C92BCB2D23437D61C9441C2F5D27A5272EDDA229'
PUBLIC_PROBE='142A5E48E9EA0DB4A0C40E08804319BC628B9E2F97704033EA007A20E1E28452'

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for data in iter(lambda:f.read(1048576),b''):h.update(data)
    return h.hexdigest().upper()

def extract_sealed(folder,output,manifest_sha,archive_sha):
    folder,output=Path(folder),Path(output)
    if sha(folder/'inputs.json')!=manifest_sha or sha(folder/'inputs.tar.gz')!=archive_sha:
        raise ValueError('Pinned prior inputs changed')
    record=json.loads((folder/'inputs.json').read_text())
    if record['head']!=SOURCE or record['archive_sha256']!=archive_sha:raise ValueError('Wrong prior source')
    with tarfile.open(folder/'inputs.tar.gz','r:gz') as t:
        members=t.getmembers();names=[m.name for m in members]
        if len(names)!=len(set(names)) or set(names)!=set(record['files']):raise ValueError('Unexpected archive members')
        for m in members:
            path=PurePosixPath(m.name)
            if path.is_absolute() or '..' in path.parts or path.parts[:1]!=('build',) or '\\' in m.name or not m.isfile():raise ValueError('Unsafe prior input')
            expected=record['files'][m.name]
            if m.size!=expected['size'] or hashlib.sha256(t.extractfile(m).read()).hexdigest().upper()!=expected['sha256']:
                raise ValueError('Changed prior archive entry')
        output.mkdir(parents=True,exist_ok=False)
        for m in members:
            target=output/m.name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(t.extractfile(m).read())
    return record

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download',type=Path,required=True)
    parser.add_argument('--java8',type=Path,required=True)
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    from verify_world_upgrades import ORIGINS,fingerprint,preserve_historical_markers
    from prepare_native116_tools import historical_runtime
    from native_runtime import seed_loader_defaults
    prior=project/'build/prior116';record=extract_sealed(args.download,prior,PRIOR_MANIFEST,PRIOR_ARCHIVE)
    old=prior/'build/migration-seeds';seeds=project/'build/migration-seeds';seeds.mkdir(exist_ok=False)
    report=json.loads((old/'seed-report.json').read_text());assert len(report['origins'])==30
    for name,details in report['origins'].items():
        source=old/name/'world';assert fingerprint(source)==details['world']
        target=seeds/name/'world';target.parent.mkdir();shutil.copytree(source,target)
        assert fingerprint(target)==details['world']
    runtime=historical_runtime(project)
    sdk=prior/'build/native-server-distribution'
    mainjar=prior/'build/libs/LootableBodies-2.4.7.116051.jar'
    probe=prior/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    writer=project/'build/native116-seed/lootablebodies-native116-world-fixtures-reobf.jar'
    assert sha(mainjar)==PUBLIC_MAIN and sha(probe)==PUBLIC_PROBE
    build=json.loads((project/'.gradle/legacy-tools/released-116/tools.json').read_text())
    assert build['source']==SOURCE and sha(writer)==build['sha256']
    java=args.java8.resolve()
    for name,recovery in [('released-116',False),('recovery-116',True)]:
        home=seeds/name;runtime.prepare_server(home,sdk,mainjar,writer);seed_loader_defaults(home,deterministic=True)
        runtime.run_server(java,home,'seed',extra=[*runtime.HISTORICAL_JVM_ARGS,'-Dlootablebodies.fixture.version=2.4.7.116051','-Dlootablebodies.fixture.recovery='+str(recovery).lower()],marker='world/legacy-seed.pass',message='LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft=1.16.5 mod=2.4.7.116051 corpses=9 dimensions=3')
        report['origins'][name]={'minecraft':'1.16.5','mod_version':'2.4.7.116051','jar_sha256':PUBLIC_MAIN,'writer_sha256':sha(writer),'world':fingerprint(home/'world')}
        (seeds/'seed-report.json').write_text(json.dumps(report,indent=2)+'\n')
    for suffix,oldname in [('110','original-110'),('111','released-111'),('112','released-112'),('113','released-113'),('114','released-114'),('115','released-115')]:
        name='via116-'+suffix;source=seeds/oldname/'world';details=report['origins'][oldname];assert fingerprint(source)==details['world']
        home=seeds/name;runtime.prepare_server(home,sdk,mainjar,probe);seed_loader_defaults(home,deterministic=True)
        shutil.copytree(source,home/'world');world=home/'world';markers=preserve_historical_markers(home)
        players=list((world/'playerdata').glob('*.dat'));assert len(players)==1;shutil.copy2(players[0],world/'upgrade-player-source.nbt')
        far={p.relative_to(world).as_posix():sha(p) for p in world.rglob('region/r.*.mca') if p.name in ('r.8.8.mca','r.16.16.mca')};assert len(far)==6
        runtime.run_server(java,home,'upgrade-near',extra=runtime.HISTORICAL_JVM_ARGS,message='LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase=upgrade-near')
        assert fingerprint(source)==details['world'] and all(sha(world/p)==h for p,h in far.items())
        report['origins'][name]={'minecraft':details['minecraft']+' via 1.16.5','mod_version':details['mod_version']+' via 2.4.7.116051','jar_sha256':details['jar_sha256'],'intermediate_jar_sha256':PUBLIC_MAIN,'transition_probe_sha256':PUBLIC_PROBE,'world':fingerprint(world),'distant_regions_unchanged':far,'historical_markers':markers}
        (seeds/'seed-report.json').write_text(json.dumps(report,indent=2)+'\n')
    assert set(report['origins'])==set(ORIGINS)
    for name,details in report['origins'].items():assert fingerprint(seeds/name/'world')==details['world']
    (seeds/'provenance.json').write_text(json.dumps({'original_30_reused_from_run':PRIOR_RUN,'original_manifest':PRIOR_MANIFEST,'original_archive':PRIOR_ARCHIVE,'eight_new_native116_sources':True,'original_worlds_regenerated':False,'all38_sealed':True},indent=2)+'\n')
    print('NATIVE117_HISTORY_PREPARED original30_reused=30 new_native116=8',flush=True)

if __name__=='__main__':main()
