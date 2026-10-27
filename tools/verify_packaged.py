"""Verify the exact production jar in disposable native Forge 31 servers."""
import argparse
import json
from pathlib import Path
from native_runtime import FORGE,digest,fingerprint,prepare_server as prepare,run_server,install
from release_identity import MAIN_JAR as MAIN


def run(java,home,phase):
    run_server(java,home,phase,message='LOOTABLE_BODIES_RUNTIME_PASS phase='+phase)
    print('PACKAGED_PASS',home.name,phase,flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--forge-home',type=Path)
    args=parser.parse_args();project=Path(__file__).resolve().parents[1]
    main_jar=project/'build/libs'/MAIN
    probe=project/'build/integration-test-mod/lootablebodies-build-tests-reobf.jar'
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    distribution=args.forge_home.resolve() if args.forge_home else output/'forge-distribution'
    if not args.forge_home:install(args.java.resolve(),distribution)
    fresh = output / "candidate-fresh"
    prepare(fresh, distribution, main_jar, probe)
    run(args.java, fresh, "fresh")
    far = fresh / "world/region/r.8.8.mca"
    before = digest(far)
    run(args.java, fresh, "reload")
    if digest(far) != before:
        raise RuntimeError("Unopened far region changed")
    run(args.java, fresh, "deferred")
    report = {"candidate_sha256": digest(main_jar), "deferred_region_unchanged": before}
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
