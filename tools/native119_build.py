"""Check local-qualified release identities; no SDK installation or game launches."""
import argparse,hashlib,json,re
from pathlib import Path
from release_identity import MOD_VERSION,MAIN_JAR

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def check_bundle(manifest,bundle):
    record=json.loads(Path(manifest).read_text());bundle=Path(bundle)
    if (record.get('version'),record.get('minecraft'),record.get('forge'))!=(MOD_VERSION,'1.19.4','45.4.5'):
        raise ValueError('Wrong target release identity')
    if record.get('local_qualification_complete') is not True:raise ValueError('Local gameplay qualification is incomplete')
    if record.get('qualification_integrity_status')!='verified':raise ValueError('Local qualification evidence is on an integrity hold or unverified')
    expected=record.get('files',{})
    names={'CHANGELOG.txt','SHA256SUMS',MAIN_JAR,MAIN_JAR[:-4]+'-sources.jar',MAIN_JAR[:-4]+'-javadoc.jar',MAIN_JAR[:-4]+'.pom'}
    if set(expected)!=names or any(not re.fullmatch('[A-F0-9]{64}',v) for v in expected.values()):raise ValueError('Missing accepted six-file identities')
    if not bundle.is_dir() or any(p.is_symlink() or not p.is_file() for p in bundle.iterdir()):raise ValueError('Invalid or aliased bundle')
    actual={p.name:digest(p) for p in bundle.iterdir()}
    if actual!=expected:raise ValueError('Built six-file bundle differs from local qualification')
    return actual

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--manifest',type=Path,default=Path('.github/release119.json'));parser.add_argument('--bundle',type=Path,default=Path('build/release/bundle'));args=parser.parse_args()
    check_bundle(args.manifest,args.bundle);print('LOCAL_QUALIFIED_BUNDLE_MATCH')
