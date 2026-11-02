"""Cheap source/metadata policy check. No Java, SDK, test runner or network calls."""
import argparse,json,re,shlex
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
MMD="if: github.repository == 'MinecraftModDevelopmentMods/LootableBodies'"
ALLOWED_ROOTS={'assemble','verifyProductionRuntimeClasspath','prepareReleaseBundle'}
ALLOWED_OPTIONS={'--no-daemon','--no-build-cache','--stacktrace'}

def gradle_roots(command):
    words=shlex.split(command);index=next(i for i,word in enumerate(words) if word in ('./gradlew','gradlew'))
    roots=[]
    for word in words[index+1:]:
        if word in ('|','2>&1','>','>>'):break
        if word in ALLOWED_OPTIONS:continue
        if word not in ALLOWED_ROOTS:raise ValueError('Hosted Gradle root is not compile/archive-only: '+word)
        roots.append(word)
    if set(roots)!=ALLOWED_ROOTS:raise ValueError('Missing explicit hosted archive roots')
    return roots

def validate_workflow(text):
    if "java-version: '17.0.20+101'" not in text or any('java-version:' in line and '17.0.20.1+1' in line for line in text.splitlines()):
        raise ValueError('Use the setup-java SemVer identifier for the exact qualified JDK')
    commands='\n'.join(line.strip() for line in text.splitlines() if line.startswith('          ') and not line.strip().startswith('#'))
    for forbidden in ('unittest discover','-m unittest','native_runtime.py','native117_ci.py','verify_world_upgrades.py','verify_packaged','prepare_native','xvfb-run','--seed-only'):
        if forbidden in commands:raise ValueError('Expensive hosted command: '+forbidden)
    # Positive command allowlist also catches new/renamed Python game runners,
    # shell wrappers, direct Java launches and SDK downloads not listed above.
    lines=text.splitlines();shell=[]
    for index,line in enumerate(lines):
        match=re.match(r'^(\s*)run:\s*(.*)$',line)
        if not match:continue
        indent=len(match.group(1));value=match.group(2)
        if value not in ('|','>-'):
            shell.append(value);continue
        for following in lines[index+1:]:
            if not following.strip():continue
            if len(following)-len(following.lstrip())<=indent:break
            shell.append(following.strip())
    for line in shell:
        if not line or line.startswith('#'):continue
        words=shlex.split(line)
        if '|' in words:
            split=words.index('|');tail=words[split+1:]
            if tail not in (['tee','$RUNNER_TEMP/native118-build.log'],['tee','-a','$RUNNER_TEMP/native118-build.log']):raise ValueError('Unknown hosted pipe')
            words=words[:split]
        if words and words[-1]=='2>&1':words=words[:-1]
        allowed=[['set','-euo','pipefail'],['chmod','+x','gradlew'],
                 ['python3','-B','tools/hosted_policy.py'],
                 ['python3','-B','tools/hosted_policy.py','--require-qualified-manifest'],
                 ['python3','-B','tools/native118_build.py']]
        if words and words[0]=='./gradlew':gradle_roots(shlex.join(words))
        elif words not in allowed:raise ValueError('Unknown hosted command: '+line)
    launches=[line.strip() for line in text.splitlines() if line.strip().startswith('./gradlew ')]
    if len(launches)!=1:raise ValueError('Expected one authoritative MMD artifact build')
    gradle_roots(launches[0])
    if MMD not in text or 'needs: policy' not in text:raise ValueError('Forks must not run a Gradle artifact build')
    if 'set -euo pipefail' not in text or 'shell: bash' not in text:raise ValueError('Piped failure must stop the hosted job')
    if 'build/test-results/' in text:raise ValueError('Hosted jobs must not claim a new unit-test report')
    if text.count('if-no-files-found: error')!=2:raise ValueError('Missing artifacts must remain fatal')
    if text.index('tools/hosted_policy.py --require-qualified-manifest')>text.index('actions/setup-java@'):raise ValueError('Qualification must be checked before expensive preparation')

def require_qualified_manifest(record):
    if record.get('local_qualification_complete') is not True or record.get('qualification_integrity_status')!='verified':
        raise ValueError('Local qualification evidence is incomplete or on an integrity hold')
    version=record.get('version','')
    if not re.fullmatch(r'\d+\.\d+\.\d+\.118021',version) or record.get('minecraft')!='1.18.2' or record.get('forge')!='40.3.12':raise ValueError('Wrong target qualification')
    base='LootableBodies-'+version
    names={'CHANGELOG.txt','SHA256SUMS',base+'.jar',base+'-sources.jar',base+'-javadoc.jar',base+'.pom'}
    if set(record.get('files',{}))!=names or any(not re.fullmatch('[A-F0-9]{64}',value or '') for value in record['files'].values()):raise ValueError('Incomplete six-file declaration')
    # This is an early CI declaration check, not an attestation of local paths.
    # Publication still needs independently approved/rehashed local evidence.

def check_source(root):
    validate_workflow((root/'.github/workflows/native118.yml').read_text(encoding='utf-8'))
    for workflow in ('codeql-analysis.yml','deploy-release.yml'):
        if any('java-version:' in line and '17.0.20.1+1' in line for line in (root/'.github/workflows'/workflow).read_text(encoding='utf-8').splitlines()):
            raise ValueError('Non-SemVer JDK input in '+workflow)
    if 'feature/**' in(root/'.github/workflows/build.yml').read_text():raise ValueError('Copied legacy CI still catches modern feature branches')
    if 'workflow_dispatch:' in(root/'.github/workflows/migration-matrix.yml').read_text():raise ValueError('Copied legacy migration workflow can still dispatch on a modern ref')
    guard=(root/'settings.gradle').read_text()
    for required in ("System.getenv('GITHUB_ACTIONS') == 'true'",'org.gradle.api.tasks.testing.Test','org.gradle.api.tasks.JavaExec','Run expensive verification locally before publishing'):
        if required not in guard:raise ValueError('Missing Gradle hosted-execution guard')
    runtime=(root/'gradle/runtime.gradle').read_text()
    if "tasks.named('check') { dependsOn 'integrationTest' }" in runtime or "tasks.register('localRuntimeVerification')" not in runtime:raise ValueError('Game checks must remain explicit local gates')
    policy=json.loads((root/'tools/qualification-policy.json').read_text())
    if policy['hosted']['full_java_suite'] or policy['hosted']['full_python_suite'] or policy['hosted']['gameplay_or_migration'] or policy['phase_consolidation']['enabled']:raise ValueError('Unvalidated expensive-test policy')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--require-qualified-manifest',action='store_true');args=parser.parse_args()
    check_source(ROOT)
    if args.require_qualified_manifest:require_qualified_manifest(json.loads((ROOT/'.github/release118.json').read_text()))
    print('HOSTED_COMPILE_ARCHIVE_POLICY_OK')
