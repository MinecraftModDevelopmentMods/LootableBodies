"""Keep complete tool logs and expose the failure details in CI output."""
import subprocess
import sys


def run_logged(command, path, **options):
    try:
        with path.open('w', encoding='utf-8') as log:
            return subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                  check=True, **options)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        print('Command failed; complete log: ' + str(path), file=sys.stderr)
        print('\n'.join(path.read_text(encoding='utf-8', errors='replace').splitlines()[-80:]),
              file=sys.stderr)
        raise
