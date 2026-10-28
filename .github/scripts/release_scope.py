"""Decide whether an old-version runtime suite has changed inputs."""
import argparse,json,re,subprocess
from pathlib import Path

SCOPE_JOB='''  runtime-scope:
    name: Check runtime inputs
    runs-on: ubuntu-latest
    outputs:
      required: ${{ steps.scope.outputs.required }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
        with:
          fetch-depth: 0
      - id: scope
        run: python3 .github/scripts/release_scope.py --event "$GITHUB_EVENT_PATH" --output "$GITHUB_OUTPUT"

'''
NEEDS_SCOPE="    needs: runtime-scope\n    if: needs.runtime-scope.outputs.required == 'true'\n"

def runtime_required(paths,manual=False,workflow_pairs=None):
    if manual or not paths:return True
    allowed={'.github/workflows/deploy-release.yml','.github/workflows/release-workflow-checks.yml',
             '.github/scripts/test_release_workflow.py','.github/scripts/release_scope.py','.github/scripts/test_release_scope.py'}
    pairs=workflow_pairs or {}
    for name in paths:
        if name in allowed or re.fullmatch(r'\.github/workflows/artifact-audit-1\.\d+\.yml',name):continue
        if name not in ('.github/workflows/build.yml','.github/workflows/migration-matrix.yml') or name not in pairs:
            return True
        before,after=pairs[name]
        # Runtime commands, inputs and assertions must be byte-equivalent after
        # removing only this exact scheduling wiring. Any other edit runs all gates.
        def inputs(text):return text.replace(SCOPE_JOB,'').replace(NEEDS_SCOPE,'')
        if inputs(before)!=inputs(after):return True
    return False

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();event=json.loads(args.event.read_text())
    base=event.get('pull_request',{}).get('base',{}).get('sha',event.get('before',''))
    required=True
    if re.fullmatch('[a-f0-9]{40}',base) and base!='0'*40:
        try:
            paths=subprocess.check_output(['git','diff','--name-only',base,'HEAD'],text=True).splitlines()
            pairs={}
            for name in paths:
                if name in ('.github/workflows/build.yml','.github/workflows/migration-matrix.yml'):
                    pairs[name]=(subprocess.check_output(['git','show',base+':'+name],text=True),Path(name).read_text())
            required=runtime_required(paths,workflow_pairs=pairs)
        except (OSError,subprocess.CalledProcessError):
            required=True
    with args.output.open('a') as output:output.write('required='+str(required).lower()+'\n')
    print('Full runtime checks required: '+str(required).lower())

if __name__=='__main__':main()
