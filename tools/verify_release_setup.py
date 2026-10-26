"""Exercise release rejection paths locally; every upload task must fail before execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from release_identity import PROJECT, PROPERTIES, MOD_VERSION, MAIN_JAR


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def fingerprint(folder):
    return {p.relative_to(folder).as_posix(): sha(p) for p in sorted(folder.rglob("*")) if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    bundle = PROJECT / "build/release/bundle"
    before = fingerprint(bundle)
    if len(before) != 6:
        raise RuntimeError("Run prepareReleaseBundle first; exactly six bundle files are required")
    wrapper = str(PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    results = []

    def run(name, tasks, expected=None, prepared=False):
        log = output / (name + ".log")
        with log.open("w", encoding="utf-8") as stream:
            process = subprocess.run([wrapper, *tasks, "--offline", "--no-daemon", "--console=plain"],
                                     cwd=PROJECT, stdout=stream, stderr=subprocess.STDOUT, timeout=180)
        text = log.read_text(encoding="utf-8", errors="replace")
        if expected is None:
            if process.returncode or "BUILD SUCCESSFUL" not in text:
                raise RuntimeError(f"Valid release contract failed: {log}")
        elif process.returncode == 0 or expected not in text:
            raise RuntimeError(f"Expected rejection {expected!r} was not proved: {log}")
        if prepared and any(f"> Task :{task}" in text for task in ("compileJava", "jar", "sourcesJar", "javadocJar")):
            raise RuntimeError(f"Prepared input caused a jar rebuild: {log}")
        results.append({"case": name, "expected_rejection": expected, "exit": process.returncode})
        print("RELEASE_CONTRACT_PASS", name, flush=True)

    run("valid-prepared", ["verifyPreparedReleaseArtifacts", f"-PpreparedReleaseDir={bundle}"], prepared=True)
    for name, override, expected in [
        ("three-component-version", "mod_version=2.4.1", "Major.Minor.Bug.114041"),
        ("wrong-target", "mod_version=2.4.4.112021", "Major.Minor.Bug.114041"),
        ("wrong-group", "mod_group=zone.moddev.mc.orespawn", "Release identity mismatch: mod_group"),
        ("wrong-artifact", "mod_artifact=OreSpawn", "Release identity mismatch: mod_artifact"),
        ("wrong-mod-id", "mod_id=orespawn", "Release identity mismatch: mod_id"),
        ("wrong-project", "curseforge_project_id=245586", "Release identity mismatch: curseforge_project_id"),
        ("wrong-loader", "loader_name=neoforge", "Release identity mismatch: loader_name"),
        ("wrong-license", "mod_license=CC0-1.0", "Release identity mismatch: mod_license"),
        ("wrong-java", "java_version=17", "Release identity mismatch: java_version"),
        ("stale-java-version", "mod_version=2.4.6.114041", "Java release version or persistent identity differs"),
    ]:
        run(name, ["verifyReleaseConfiguration", "-P" + override], expected)

    def fixture(name):
        target = output / name
        shutil.copytree(bundle, target)
        return target

    def sums(folder):
        (folder / "SHA256SUMS").write_text("".join(
            f"{sha(p)}  {p.name}\n" for p in sorted(folder.glob("*.jar"))), encoding="utf-8")

    def rewrite_jar(folder, entry, replacement):
        jar = folder / MAIN_JAR
        temporary = jar.with_suffix(".rewritten")
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(temporary, "w") as target:
            for info in source.infolist():
                target.writestr(info, replacement if info.filename == entry else source.read(info.filename))
        temporary.replace(jar)
        sums(folder)

    for name, expected in [
        ("missing-sources", "exactly the declared jars"),
        ("empty-main", "Empty artifact"),
        ("bad-checksum", "checksums do not match"),
        ("changed-pom", "POM differs"),
        ("changed-changelog", "changelog differs"),
        ("extra-jar", "exactly the declared jars"),
        ("deobfuscated-main", "runtime SRG mappings"),
        ("changed-license", "licence or attribution"),
        ("foreign-class", "Foreign production class"),
    ]:
        folder = fixture(name)
        if name == "missing-sources":
            (folder / MAIN_JAR.replace(".jar", "-sources.jar")).unlink()
        elif name == "empty-main":
            (folder / MAIN_JAR).write_bytes(b"")
        elif name == "bad-checksum":
            (folder / "SHA256SUMS").write_text("0" * 64 + "  " + MAIN_JAR + "\n")
        elif name == "changed-pom":
            pom = folder / f'{PROPERTIES["mod_artifact"]}-{MOD_VERSION}.pom'
            pom.write_bytes(pom.read_bytes().replace(b"Cyanobacterium", b"OtherAuthor"))
        elif name == "changed-changelog":
            (folder / "CHANGELOG.txt").write_text("unreviewed release notes\n")
        elif name == "extra-jar":
            (folder / "unexpected.jar").write_bytes(b"unexpected")
        elif name == "deobfuscated-main":
            shutil.copyfile(PROJECT / "build/libs-dev" / MAIN_JAR.replace(".jar", "-deobf.jar"), folder / MAIN_JAR)
            sums(folder)
        elif name == "changed-license":
            rewrite_jar(folder, "META-INF/LICENSE", b"wrong licence\n")
        elif name == "foreign-class":
            with zipfile.ZipFile(folder / MAIN_JAR, "a") as jar:
                jar.writestr("foreign/Injected.class", bytes.fromhex("CAFEBABE00000034"))
            sums(folder)
        run(name, ["verifyPreparedReleaseArtifacts", f"-PpreparedReleaseDir={folder}"], expected, prepared=True)

    for task in ["publish", "publishToMavenLocal", "publishMavenJavaPublicationToReleaseRepository"]:
        run("blocked-" + task, [task, "-PpublicationEnabled=true", f"-Dmaven.repo.local={output / 'unused-maven-local'}"],
            "Publication is disabled")
    if (output / "unused-maven-local").exists() or fingerprint(bundle) != before:
        raise RuntimeError("Publication guard wrote a repository or immutable input changed")
    run("valid-prepared-final", ["verifyPreparedReleaseArtifacts", f"-PpreparedReleaseDir={bundle}"], prepared=True)
    (output / "result.json").write_text(json.dumps({"checks": results, "bundle_unchanged": True,
        "prepared_jar_rebuild": False, "publication_disabled": True}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
