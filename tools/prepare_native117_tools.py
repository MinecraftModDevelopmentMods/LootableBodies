"""Build a private native37 writer from exact released17 source, never target18."""
import argparse,json,os,shutil
from pathlib import Path
from prepare_legacy_tools import prepare,digest
from command_logs import run_build_input_logged

SOURCE='4a4a94cdf9b7f17add67cf22341c7f0ad6fcc808'
PUBLIC_MAIN='854A46D80B8599966E43B3EAED407CD1A99FE4FB019AEE9138FE2F98E8DDA9FA'
OUTPUT='native117-seed/lootablebodies-native117-world-fixtures-reobf.jar'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--java17-home',type=Path,required=True)
    p.add_argument('--java16-home',type=Path,required=True)
    p.add_argument('--java25-home',type=Path,required=True)
    p.add_argument('--public-main',type=Path,required=True)
    a=p.parse_args();project=Path(__file__).resolve().parents[1];output=a.output.resolve()
    if digest(a.public_main)!=PUBLIC_MAIN:raise ValueError('Writer requires genuine public17 main854A bytes')
    source=prepare(project,output,SOURCE)
    # No sibling source/output and no SDK/cache copies. The common cache has
    # exactly one writer while this isolated archived source compiles the probe.
    env=dict(os.environ,JAVA_HOME=str(a.java17_home.resolve()),LOOTABLEBODIES_JAVA16_HOME=str(a.java16_home.resolve()),LOOTABLEBODIES_MAVENIZER_JAVA_HOME=str(a.java25_home.resolve()))
    wrapper=source/('gradlew.bat' if os.name=='nt' else 'gradlew')
    if os.name!='nt':wrapper.chmod(0o755)
    writer_files={f.relative_to(project).as_posix():digest(f) for f in (project/'src/nativeSeed117').rglob('*') if f.is_file()}
    (output/'writer-inputs.json').write_text(json.dumps({'source':SOURCE,'public_main_sha256':PUBLIC_MAIN,'files':writer_files,'init_sha256':digest(project/'gradle/native117-writer.init.gradle')},indent=2)+'\n')
    run_build_input_logged([str(wrapper),'prepareNative117WorldFixtures','--init-script='+str(project/'gradle/native117-writer.init.gradle'),'-PhistoricalWriterSource='+str(project),'--no-daemon','--console=plain'],cwd=source,env=env,path=output/'build.log',timeout=1800)
    artifact=source/'build'/OUTPUT
    if not artifact.is_file() or not artifact.stat().st_size:raise FileNotFoundError(artifact)
    if any(digest(project/name)!=sha for name,sha in writer_files.items()):raise ValueError('Historical writer source changed while compiling')
    target=project/'build'/OUTPUT;target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():raise FileExistsError(target)
    shutil.copy2(artifact,target)
    main=output/'public-main'/a.public_main.name;main.parent.mkdir();shutil.copy2(a.public_main,main)
    if digest(main)!=PUBLIC_MAIN:raise ValueError('Copied public17 main changed')
    record={'source':SOURCE,'public_main':str(main),'public_main_sha256':PUBLIC_MAIN,'artifact':OUTPUT,'sha256':digest(artifact),'writer_sources':writer_files,'production_java':'16.0.2+7','compiled_against':'released17 native37, not18','runtime_qualified':False}
    (output/'tools.json').write_text(json.dumps(record,indent=2)+'\n')
    print('Public17 historical writer compiled; runtime preparation remains separate',flush=True)

if __name__=='__main__':main()
