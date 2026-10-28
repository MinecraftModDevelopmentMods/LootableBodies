"""Keep complete tool logs and expose the failure details in CI output."""
import subprocess
import sys
import time
import re


def _transient_build_input_failure(text):
    """Recognise the two recorded Mavenizer transport failures before tasks run."""
    if not all(value in text for value in (
            'A problem occurred configuring root project',
            'net.minecraftforge.mcmaven.', 'net.minecraftforge.util.download.')):
        return False
    if re.search(r'(?m)^> Task\s|Execution failed for task|\[(?:Server|Client) thread',text):
        return False
    if re.search(r'checksum mismatch|verification failed|permission denied|forbidden|'
                 r'SSLHandshakeException|PKIX|(?:HTTP(?:\s+response)?(?:\s+code)?|status(?:\s+code)?)'
                 r'\s*[:=]?\s*(?:401|403|404|429)\b',text,re.I):
        return False
    return any(value in text for value in (
        'Caused by: java.net.SocketException: Connection reset',
        'Caused by: java.net.http.HttpTimeoutException: request timed out'))


def run_build_input_logged(command, path, **options):
    """Retry only classified input downloads; retain each complete attempt."""
    path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        previous=1
        while True:
            archived=path.with_name(path.stem+'.previous-'+str(previous)+path.suffix)
            if not archived.exists():break
            previous+=1
        archived.write_bytes(path.read_bytes())
    sequence=1
    for attempt in range(3):
        while True:
            evidence=path.with_name(path.stem+'.attempt-'+str(sequence)+path.suffix)
            sequence+=1
            if not evidence.exists():break
        try:
            result=run_logged(command,evidence,**options)
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as failure:
            path.write_bytes(evidence.read_bytes())
            print('Latest complete preparation log: '+str(path),file=sys.stderr)
            text=evidence.read_text(encoding='utf-8',errors='replace')
            if not isinstance(failure,subprocess.CalledProcessError) or attempt==2 or not _transient_build_input_failure(text):
                raise
            print('Retrying classified Mavenizer download failure; complete evidence: '+str(evidence),flush=True)
            time.sleep(5*(attempt+1))
        else:
            path.write_bytes(evidence.read_bytes())
            return result


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


if __name__=='__main__':
    import argparse
    from pathlib import Path
    parser=argparse.ArgumentParser(description='Build preparation with bounded transport retries and complete logs.')
    parser.add_argument('--log',type=Path,required=True)
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args();command=args.command
    if command[:1]==['--']:command=command[1:]
    if not command:parser.error('A preparation command is required')
    run_build_input_logged(command,args.log.resolve())
