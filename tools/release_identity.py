"""Release filenames shared by packaged verifiers; no publication or credentials."""
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
PROPERTIES = dict(line.split("=", 1) for line in
                  (PROJECT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                  if line.strip() and not line.lstrip().startswith("#") and "=" in line)
MOD_VERSION = PROPERTIES["mod_version"]
MAIN_JAR = f'{PROPERTIES["mod_artifact"]}-{MOD_VERSION}.jar'
