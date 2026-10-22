"""Verify the exact SRG jar in disposable Forge 1.12.2 servers (no Gradle bootstrap)."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
from audit_runtime_logs import audit
from release_identity import MAIN_JAR, MOD_VERSION
from prepare_migration_inputs import fetch

FORGE = "forge-1.12.2-14.23.5.2859"
MAIN = MAIN_JAR


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fingerprint(folder):
    return {p.relative_to(folder).as_posix(): digest(p) for p in sorted(folder.rglob("*")) if p.is_file()}


def run(java, home, phase):
    log = home / (phase + ".log")
    marker = home / "world" / (phase + ".pass")
    if marker.exists():
        raise RuntimeError(f"Refusing stale marker {marker}")
    with log.open("w", encoding="utf-8") as output:
        completed = subprocess.run([str(java), "-Xmx2G", f"-Dlootablebodies.phase={phase}",
                                    "-jar", FORGE + "-universal.jar", "nogui"],
                                   cwd=home, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    text = log.read_text(encoding="utf-8", errors="replace")
    if completed.returncode or not marker.is_file() or "LOOTABLE_BODIES_RUNTIME_PASS phase=" + phase not in text:
        raise RuntimeError(f"Packaged {phase} failed: {log}")
    if any(word in text for word in ("AssertionError", "Caused by:", "Encountered an unexpected exception", "Exception stopping server")):
        raise RuntimeError(f"Failure in complete log {log}")
    if list((home / "crash-reports").glob("*")):
        raise RuntimeError(f"Crash reports in {home}")
    audit(log)
    for name in ['latest.log','debug.log','fml-server-latest.log']:
        debug=home/'logs'/name
        if debug.is_file():
            shutil.copy2(debug,home/(phase+'.'+name))
            audit(debug)
    print(f"PACKAGED_PASS {home.name} {phase}", flush=True)


def prepare(root, distribution, jar, probe):
    root.mkdir(parents=True, exist_ok=False)
    (root / "mods").mkdir()
    for name in (FORGE + "-universal.jar", "minecraft_server.1.12.2.jar"):
        shutil.copy2(distribution / name, root / name)
    shutil.copytree(distribution / "libraries", root / "libraries")
    shutil.copy2(jar, root / "mods" / MAIN)
    shutil.copy2(probe, root / "mods" / probe.name)
    (root / "eula.txt").write_text("eula=true\n")
    (root / "server.properties").write_text("level-name=world\nlevel-seed=4267321\nlevel-type=FLAT\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=0\ngenerate-structures=false\nspawn-animals=false\nspawn-monsters=false\nmax-tick-time=120000\n")
    for name in ("ops.json", "whitelist.json", "banned-players.json", "banned-ips.json"):
        (root / name).write_text("[]\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--forge-home", type=Path)
    parser.add_argument("--original-jar", type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    main_jar = project / "build/libs" / MAIN
    probe = project / "build/integration-test-mod/lootablebodies-build-tests-reobf.jar"
    for required in (args.java, main_jar, probe):
        if not required.is_file():
            raise FileNotFoundError(required)
    output = args.output.resolve()
    if output.exists():
        raise RuntimeError("Preserve existing evidence; use a new output directory")
    output.mkdir(parents=True)
    distribution = args.forge_home
    if distribution is None:
        distribution = output / "forge-distribution"
        distribution.mkdir()
        installer = distribution / (FORGE + "-installer.jar")
        fetch("https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2859/" + installer.name,
              installer,'DBBA1CD1BF34A4B82148BAC50A6150F63FFCB2D5DC7D88A0508C27FDCFF15B76')
        if digest(installer) != 'DBBA1CD1BF34A4B82148BAC50A6150F63FFCB2D5DC7D88A0508C27FDCFF15B76':
            raise RuntimeError('Forge installer hash mismatch')
        with (distribution / "install.log").open("w") as log:
            subprocess.run([str(args.java), "-jar", str(installer), "--installServer"], cwd=distribution,
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
    if not (distribution / (FORGE + '-universal.jar')).is_file() and (distribution / (FORGE + '.jar')).is_file():
        shutil.copy2(distribution / (FORGE + '.jar'), distribution / (FORGE + '-universal.jar'))
    if digest(distribution / (FORGE + '-universal.jar')) != 'BD8314729D787E35164476D75AFB337AFB9783917E4F6FA1F77F049ADBA22F2D':
        raise RuntimeError('Forge universal hash mismatch')
    if hashlib.sha1((distribution / 'minecraft_server.1.12.2.jar').read_bytes()).hexdigest() != '886945bfb2b978778c3a0288fd7fab09d315b25f':
        raise RuntimeError('Vanilla server hash mismatch')
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
    if args.original_jar:
        original_hash = digest(args.original_jar)
        seed = output / "original-seed"
        prepare(seed, distribution, args.original_jar, probe)
        run(args.java, seed, "seed")
        source_before = fingerprint(seed / "world")
        upgrade = output / "original-upgrade"
        prepare(upgrade, distribution, main_jar, probe)
        shutil.copytree(seed / "world", upgrade / "world")
        far = upgrade / "world/region/r.8.8.mca"
        before = digest(far)
        run(args.java, upgrade, "reload")
        if digest(far) != before:
            raise RuntimeError("Old deferred region changed before opening")
        run(args.java, upgrade, "deferred")
        # Open/save the formerly deferred corpse again in a separate JVM.
        (upgrade / "world/deferred.pass").rename(upgrade / "world/deferred-first.pass")
        (upgrade / "deferred.log").rename(upgrade / "deferred-first.log")
        run(args.java, upgrade, "deferred")
        if fingerprint(seed / "world") != source_before or digest(args.original_jar) != original_hash:
            raise RuntimeError("Original fixture changed")
        report.update(original_sha256=original_hash, original_world=source_before,
                      original_world_unchanged=True, original_deferred_region_unchanged=before)
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
