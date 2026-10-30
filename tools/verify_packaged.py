"""Verify the exact production jar in disposable native Forge 37 servers."""
import argparse
import json
from pathlib import Path
from native_runtime import FORGE,digest,fingerprint,prepare_server as prepare,run_server,seed_flat_fixture
from release_identity import MAIN_JAR as MAIN


def run(java,home,phase):
    logging=Path(__file__).resolve().parents[1]/'src/integrationTest/resources/log4j2.xml'
    run_server(java,home,phase,message='LOOTABLE_BODIES_RUNTIME_PASS phase='+phase,
               extra=['-Dlog4j.configurationFile='+logging.as_uri()])
    print('PACKAGED_PASS',home.name,phase,flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path,required=True)
    parser.add_argument('--flat-header',type=Path,required=True)
    parser.add_argument('--flat-header-sha256',required=True)
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    main_jar=project/'build/libs'/MAIN
    probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    distribution=args.forge_home.resolve()
    fresh = output / "candidate-fresh"
    prepare(fresh, distribution, main_jar, probe)
    seed_flat_fixture(fresh,args.flat_header,args.flat_header_sha256)
    run(args.java, fresh, "fresh")
    far = [fresh/('world/'+kind+'/r.8.8.mca') for kind in ('region','entities')]
    before = {path.relative_to(fresh).as_posix():digest(path) for path in far}
    run(args.java, fresh, "reload")
    if any(digest(path)!=before[path.relative_to(fresh).as_posix()] for path in far):
        raise RuntimeError("Unopened far terrain or entity region changed")
    run(args.java, fresh, "deferred")
    report = {"candidate_sha256": digest(main_jar), "deferred_regions_unchanged": before}
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
