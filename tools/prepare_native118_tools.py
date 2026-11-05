"""Compile an isolated writer against exact public18, never the target19 API."""
import argparse,json,os,shutil,subprocess
from pathlib import Path
from prepare_legacy_tools import prepare,digest
from command_logs import run_build_input_logged

SOURCE='89f0a9f64ffff41b5fd751528c630ffee47998cb'
PUBLIC_MAIN='34EF41EFCE4666A684EA202DAF8712162A1C403A90120BA11EF0260533FB690F'
OUTPUT='native118-seed/lootablebodies-native118-world-fixtures-reobf.jar'

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--java17-home',type=Path,required=True)
    p.add_argument('--java25-home',type=Path,required=True)
    p.add_argument('--public-main',type=Path,required=True)
    a=p.parse_args();project=Path(__file__).resolve().parents[1];output=a.output.resolve()
    if digest(a.public_main)!=PUBLIC_MAIN:raise ValueError('Native18 writer requires genuine public34EF main bytes')
    source=prepare(project,output,SOURCE)
    env=dict(os.environ,JAVA_HOME=str(a.java17_home.resolve()),GRADLE_USER_HOME='D:/GradleCaches/minecraft-mods-shared',LOOTABLEBODIES_JAVA17_HOME=str(a.java17_home.resolve()),LOOTABLEBODIES_MAVENIZER_JAVA_HOME=str(a.java25_home.resolve()))
    env.pop('GITHUB_ACTIONS',None)
    writer_files={f.relative_to(project).as_posix():digest(f) for f in (project/'src/nativeSeed118').rglob('*') if f.is_file()}
    init=project/'gradle/native118-writer.init.gradle';init_hash=digest(init)
    (output/'writer-inputs.json').write_text(json.dumps({'source':SOURCE,'public_main_sha256':PUBLIC_MAIN,'files':writer_files,'init_sha256':init_hash},indent=2)+'\n',encoding='utf-8')
    command=[str(a.java17_home.resolve()/'bin/java.exe'),'-Xmx64m','-Xms64m','-Dfile.encoding=UTF-8','-Dorg.gradle.appname=gradlew','-classpath',str(source/'gradle/wrapper/gradle-wrapper.jar'),'org.gradle.wrapper.GradleWrapperMain','prepareNative118WorldFixtures','--init-script='+str(init),'-PhistoricalWriterSource='+str(project),'--offline','--no-daemon','--no-build-cache','--console=plain','--stacktrace']
    run_build_input_logged(command,cwd=source,env=env,path=output/'build.log',timeout=1800)
    artifact=source/'build'/OUTPUT
    if not artifact.is_file() or not artifact.stat().st_size:raise FileNotFoundError(artifact)
    if digest(init)!=init_hash or any(digest(project/name)!=value for name,value in writer_files.items()):raise ValueError('Historical writer sources changed during compilation')
    target=project/'build'/OUTPUT;target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():raise FileExistsError(target)
    shutil.copy2(artifact,target)
    main=output/'public-main'/a.public_main.name;main.parent.mkdir();shutil.copy2(a.public_main,main)
    if digest(main)!=PUBLIC_MAIN:raise ValueError('Copied public18 main bytes changed')
    record={'source':SOURCE,'public_main':str(main),'public_main_sha256':PUBLIC_MAIN,'artifact':OUTPUT,'sha256':digest(artifact),'writer_sources':writer_files,'production_java':'17.0.20.1+1','compiled_against':'Exact released18 native40; target19 classes not used','runtime_qualified':False}
    (output/'tools.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
    print('PUBLIC18_NATIVE_WRITER_COMPILE_PASS_RUNTIME_PENDING',flush=True)

if __name__=='__main__':main()
