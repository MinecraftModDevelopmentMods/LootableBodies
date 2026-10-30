"""Test the dispatcher's actual selection and publication preflight scripts offline."""
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows/deploy-release.yml'
REPOSITORY = 'MinecraftModDevelopmentMods/LootableBodies'
SHA = 'a' * 40
CORE = ['Cold Forge bootstrap', 'Build, test, and audit', 'Analyze Java', 'Validation']
MATRIX112 = [origin + ' / ' + stamps for origin in
             ['original-110', 'released-110', 'original-111', 'recovery-110',
              'released-111', 'original-112', 'recovery-111', 'via111-110']
             for stamps in ['normal', 'stamps']]
MATRIX113 = MATRIX112 + [origin + ' / ' + stamps for origin in
             ['released-112', 'recovery-112', 'via112-110', 'via112-111']
             for stamps in ['normal', 'stamps']]
MATRIX114 = MATRIX113 + [origin + ' / ' + stamps for origin in
             ['released-113', 'recovery-113', 'via113-110', 'via113-111', 'via113-112']
             for stamps in ['normal', 'stamps']]
MATRIX115 = ['World upgrade matrix', 'Prepare verified migration inputs', 'Validate release workflow'] + ['Historical world shard ' + str(i) for i in range(6)]
CORE117 = ['Build, test, and audit', 'Analyze Java', 'Validation', 'Validate release workflow']
MATRIX117 = []
MATRIX116 = ['World upgrade matrix', 'Prepare verified migration inputs', 'Validate release workflow'] + ['Historical world shard ' + str(i) for i in range(12)]
MATRIX = [origin + ' / ' + stamps for origin in
          ['original-110', 'released-110', 'original-111', 'recovery-110']
          for stamps in ['normal', 'stamps']]


def script(name):
    lines = WORKFLOW.read_text(encoding='utf-8').splitlines()
    start = lines.index('      - name: ' + name)
    start = next(i for i in range(start, len(lines)) if lines[i] == '        run: |') + 1
    end = start
    while end < len(lines) and (not lines[end].strip() or lines[end].startswith('          ')):
        end += 1
    return textwrap.dedent('\n'.join(lines[start:end]))


class ReleaseWorkflowTests(unittest.TestCase):
    def select(self, version, repository=REPOSITORY, ref='refs/heads/master-1.12'):
        bash = shutil.which('bash')
        if os.name == 'nt':
            bash = str(Path(shutil.which('git')).parents[1] / 'bin/bash.exe')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'output'
            result = subprocess.run([bash, '-c', script('Require the MMD dispatcher and a supported target')],
                env=dict(os.environ, RELEASE_VERSION=version, GITHUB_REPOSITORY=repository,
                         GITHUB_REF=ref, GITHUB_OUTPUT=output.as_posix()), capture_output=True, text=True)
            values = dict(line.split('=', 1) for line in output.read_text().splitlines()) if output.exists() else {}
            return result.returncode, values

    def validate(self, minecraft, names=None, mutation=None, release_exists=False, wrong_tag=False, audit_success=True, qualification=None, local_receipt=None):
        suffix, branch, forge = {
            '1.10.2': ('110021', '1.10.2', '12.18.3.2511'),
            '1.11.2': ('111021', '1.11.2-dev', '13.20.1.2588'),
            '1.12.2': ('112021', 'master-1.12', '14.23.5.2859'),
            '1.13.2': ('113021', 'master-1.13.2', '25.0.223'),
            '1.14.4': ('114041', 'master-1.14.4', '28.2.26'),
            '1.15.2': ('115021', 'master-1.15.2', '31.2.57'),
            '1.16.5': ('116051', 'master-1.16.5', '36.2.42'),
            '1.17.1': ('117011', 'master-1.17.1', '37.1.1')}[minecraft]
        version = '2.4.2.' + suffix
        files={name:'C'*64 for name in ['main.jar','sources.jar','javadoc.jar','release.pom','CHANGELOG.txt','SHA256SUMS']}
        receipt={'schema':1,'source':SHA,'version':version,'files':files,'evidence_sha256':'F'*64,'qualified_locally':True,'manual_accepted':True}
        if local_receipt is not None:receipt=local_receipt
        values = dict(minecraft_version=minecraft, forge_version=forge, loader_name='forge', loader_code='1',
            java_version='16' if minecraft=='1.17.1' else '8', java_toolchain_version='16.0.2+7' if minecraft=='1.17.1' else '8.0.502+7', gradle_java_version='17', mod_id='lootablebodies',
            mod_artifact='LootableBodies', mod_group='zone.moddev.mc.lootablebodies', mod_license='LGPL-2.1-only',
            curseforge_project_id='240968', mod_version=version)
        if mutation:
            values.update(mutation)
        checks = CORE + {'1.11.2': MATRIX, '1.12.2': MATRIX112,
                         '1.13.2': MATRIX113 + ['Validate release workflow'],
                         '1.14.4': MATRIX114 + ['Validate release workflow'],
                         '1.15.2': MATRIX115, '1.16.5': MATRIX116}.get(minecraft, []) if names is None else names
        if minecraft=='1.17.1' and names is None:checks=CORE117+MATRIX117

        def command(args, **options):
            if args[0] == 'git':
                return SHA + '\n'
            if '/actions/workflows/native117.yml/' in args[-1]:
                row=dict(id=117,head_sha=SHA,head_branch='master-1.17.1',event='push',path='.github/workflows/native117.yml',conclusion='success')
                if qualification:row.update(qualification)
                return json.dumps({'workflow_runs':[row]})
            if '/actions/workflows/' in args[-1]:
                return json.dumps({'workflow_runs': [{'id': 1, 'display_title': 'Audit ' + minecraft + ' artifacts from ' + SHA,
                    'conclusion': 'success' if audit_success else 'failure'}]})
            return json.dumps({'check_runs': [dict(name=name, id=i, conclusion='success') for i, name in enumerate(checks)]})
        def existing(args, **options):
            if '/releases/tags/' in args[-1] and release_exists:
                return subprocess.CompletedProcess(args, 0, '{}', '')
            if '/git/ref/tags/' in args[-1] and wrong_tag:
                return subprocess.CompletedProcess(args, 0, json.dumps({'object': {'sha': 'b' * 40}}), '')
            return subprocess.CompletedProcess(args, 1, '', 'HTTP 404')
        code = script('Validate identity, exact commit and successful checks').split("python3 - <<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                'RELEASE_VERSION': version, 'GITHUB_REPOSITORY': REPOSITORY,
                'MINECRAFT_VERSION': minecraft, 'SOURCE_BRANCH': branch,
                'GITHUB_OUTPUT': str(Path(directory) / 'output'),
                'LOCAL_QUALIFICATION':json.dumps(receipt) if receipt else ''}), \
                patch('pathlib.Path.read_text', side_effect=['\n'.join(k+'='+v for k,v in values.items()),json.dumps({'files':files})]), \
                patch('subprocess.check_output', side_effect=command), patch('subprocess.run', side_effect=existing):
            exec(compile(code, 'release-preflight', 'exec'), {})

    def test_future_118_candidate_cannot_dispatch_publication(self):
        code, values = self.select('2.4.9.118021')
        self.assertNotEqual(0, code)
        self.assertEqual({}, values)

    def test_routes_all_supported_versions(self):
        for suffix, minecraft, branch in [('110021', '1.10.2', '1.10.2'), ('111021', '1.11.2', '1.11.2-dev'), ('112021', '1.12.2', 'master-1.12'), ('113021', '1.13.2', 'master-1.13.2'), ('114041', '1.14.4', 'master-1.14.4')]:
            with self.subTest(minecraft=minecraft):
                status, values = self.select('2.4.2.' + suffix)
                self.assertEqual(0, status)
                self.assertEqual(minecraft, values['minecraft_version'])
                self.assertEqual(branch, values['source_branch'])

    def test_invalid_versions_are_rejected(self):
        for version in ['2.4.2', '2.4.2.114021', '02.4.2.111021', '2.4.2.111021;exit 0']:
            with self.subTest(version=version):
                self.assertNotEqual(0, self.select(version)[0])

    def test_other_repository_or_branch_cannot_dispatch(self):
        self.assertNotEqual(0, self.select('2.4.2.111021', repository='SkyBlade1978/LootableBodies')[0])
        self.assertNotEqual(0, self.select('2.4.2.111021', ref='refs/heads/1.11.2-dev')[0])

    def test_110_keeps_its_existing_required_checks(self):
        self.validate('1.10.2')

    def test_111_requires_every_upgrade_case(self):
        self.validate('1.11.2')
        for missing in MATRIX:
            with self.subTest(missing=missing), self.assertRaises(SystemExit):
                self.validate('1.11.2', names=CORE + [name for name in MATRIX if name != missing])

    def test_111_requires_an_independent_audit_of_its_exact_artifact(self):
        with self.assertRaises(SystemExit):
            self.validate('1.11.2', audit_success=False)

    def test_112_requires_every_upgrade_case(self):
        self.validate('1.12.2')
        for missing in MATRIX112:
            with self.subTest(missing=missing), self.assertRaises(SystemExit):
                self.validate('1.12.2', names=CORE + [name for name in MATRIX112 if name != missing])

    def test_112_requires_an_independent_audit_of_its_exact_artifact(self):
        with self.assertRaises(SystemExit):
            self.validate('1.12.2', audit_success=False)

    def test_113_requires_every_upgrade_case_and_workflow_validation(self):
        self.validate('1.13.2')
        required = MATRIX113 + ['Validate release workflow']
        for missing in required:
            with self.subTest(missing=missing), self.assertRaises(SystemExit):
                self.validate('1.13.2', names=CORE + [name for name in required if name != missing])

    def test_113_requires_an_independent_audit_of_its_exact_artifact(self):
        with self.assertRaises(SystemExit):
            self.validate('1.13.2', audit_success=False)

    def test_113_audit_receives_the_registered_shared_ci_completion(self):
        # GitHub registers this shared workflow under its default-branch name,
        # even when the run uses another Minecraft branch's workflow contents.
        audit = WORKFLOW.with_name('artifact-audit-1.13.yml').read_text(encoding='utf-8')
        event_names = audit.split('workflows: [', 1)[1].split(']', 1)[0].split(',')
        self.assertIn('LootableBodies 1.12 CI', [name.strip() for name in event_names])

    def test_114_requires_every_upgrade_case_and_workflow_validation(self):
        self.validate('1.14.4')
        required = MATRIX114 + ['Validate release workflow']
        self.assertEqual(34, len(MATRIX114))
        for missing in required:
            with self.subTest(missing=missing), self.assertRaises(SystemExit):
                self.validate('1.14.4', names=CORE + [name for name in required if name != missing])

    def test_114_requires_an_independent_audit_of_its_exact_artifact(self):
        with self.assertRaises(SystemExit):
            self.validate('1.14.4', audit_success=False)

    def test_114_audit_receives_the_registered_shared_ci_completion(self):
        audit = WORKFLOW.with_name('artifact-audit-1.14.yml').read_text(encoding='utf-8')
        event_names = audit.split('workflows: [', 1)[1].split(']', 1)[0].split(',')
        self.assertIn('LootableBodies 1.12 CI', [name.strip() for name in event_names])

    def test_115_routes_to_its_own_branch(self):
        code, values = self.select('2.4.6.115021')
        self.assertEqual(0, code)
        self.assertEqual('master-1.15.2', values['source_branch'])

    def test_115_requires_preparation_every_shard_and_the_full_matrix(self):
        self.validate('1.15.2')
        for missing in MATRIX115:
            with self.subTest(missing=missing), self.assertRaises(SystemExit):
                self.validate('1.15.2', names=CORE + [name for name in MATRIX115 if name != missing])

    def test_115_requires_its_independent_artifact_audit(self):
        with self.assertRaises(SystemExit):
            self.validate('1.15.2', audit_success=False)

    def test_115_audit_handles_the_registered_workflow_name(self):
        audit = WORKFLOW.with_name('artifact-audit-1.15.yml').read_text()
        names = audit.split('workflows: [',1)[1].split(']',1)[0].split(',')
        self.assertIn('LootableBodies 1.12 CI', [name.strip() for name in names])

    def test_116_routes_to_its_own_branch(self):
        code, values = self.select('2.4.7.116051')
        self.assertEqual(0, code)
        self.assertEqual('1.16.5', values['minecraft_version'])
        self.assertEqual('master-1.16.5', values['source_branch'])

    def test_116_requires_preparation_all_twelve_shards_and_the_aggregate(self):
        self.validate('1.16.5')
        for missing in MATRIX116:
            with self.subTest(missing=missing), self.assertRaises(SystemExit):
                self.validate('1.16.5', names=CORE + [name for name in MATRIX116 if name != missing])

    def test_116_requires_its_independent_exact_artifact_audit(self):
        with self.assertRaises(SystemExit):
            self.validate('1.16.5', audit_success=False)

    def test_116_audit_handles_both_registered_workflow_names(self):
        audit = WORKFLOW.with_name('artifact-audit-1.16.yml').read_text()
        names = audit.split('workflows: [',1)[1].split(']',1)[0].split(',')
        self.assertIn('LootableBodies 1.12 CI', [name.strip() for name in names])
        self.assertIn('LootableBodies 1.16 CI', [name.strip() for name in names])

    def test_117_routes_to_the_accepted_target(self):
        code,values=self.select('2.4.8.117011')
        self.assertEqual(0,code);self.assertEqual('master-1.17.1',values['source_branch'])

    def test_117_requires_build_security_and_archive_checks_without_hosted_games(self):
        self.validate('1.17.1')
        for missing in CORE117+MATRIX117:
            with self.subTest(missing=missing),self.assertRaises(SystemExit):
                self.validate('1.17.1',names=[x for x in CORE117+MATRIX117 if x!=missing])

    def test_117_requires_a_complete_hash_bound_local_qualification_receipt(self):
        self.validate('1.17.1')
        files={name:'C'*64 for name in ['main.jar','sources.jar','javadoc.jar','release.pom','CHANGELOG.txt','SHA256SUMS']}
        good={'schema':1,'source':SHA,'version':'2.4.2.117011','files':files,'evidence_sha256':'F'*64,'qualified_locally':True,'manual_accepted':True}
        for mutation in [{'source':'b'*40},{'version':'2.4.8.117011'},{'files':{}},{'evidence_sha256':''},{'evidence_sha256':'0'*64},{'qualified_locally':False},{'manual_accepted':False},{'schema':True}]:
            with self.subTest(mutation=mutation),self.assertRaises(SystemExit):
                self.validate('1.17.1',local_receipt=dict(good,**mutation))
        with self.assertRaises(SystemExit):self.validate('1.17.1',local_receipt={})

    def test_local_receipt_is_passed_to_the_actual_preflight_step(self):
        step=WORKFLOW.read_text().split('- name: Validate identity, exact commit and successful checks',1)[1].split('        run: |',1)[0]
        self.assertIn('LOCAL_QUALIFICATION: ${{ inputs.local_qualification }}',step)

    def test_117_requires_an_exact_successful_merged_native_run(self):
        for mutation in [{'head_sha':'b'*40},{'head_branch':'master-1.16.5'},{'event':'pull_request'},{'path':'.github/workflows/build.yml'},{'conclusion':'failure'}]:
            with self.subTest(mutation=mutation),self.assertRaises(SystemExit):
                self.validate('1.17.1',qualification=mutation)

    def test_117_requires_the_independent_audit_and_java16(self):
        with self.assertRaises(SystemExit):self.validate('1.17.1',audit_success=False)
        with self.assertRaises(SystemExit):self.validate('1.17.1',mutation={'java_version':'8'})

    def test_117_promotes_exact_six_files_and_rejects_changed_or_extra_bytes(self):
        code=script('Verify all six native117 bytes before publication').split("python3 - <<'PY'\n",1)[1].split('\nPY',1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);bundle=root/'build/release/bundle';bundle.mkdir(parents=True);meta=root/'.github';meta.mkdir()
            names=['main.jar','sources.jar','javadoc.jar','release.pom','CHANGELOG.txt','SHA256SUMS'];expected={}
            for name in names:
                data=('approved '+name).encode();(bundle/name).write_bytes(data);expected[name]=hashlib.sha256(data).hexdigest().upper()
            (meta/'release117.json').write_text(json.dumps({'files':expected}))
            previous=Path.cwd()
            try:
                os.chdir(root);exec(compile(code,'immutable-native117-promotion','exec'),{})
                for name in names:
                    f=bundle/name;data=f.read_bytes();f.write_bytes(data+b'changed')
                    with self.assertRaises(SystemExit):exec(compile(code,'changed-native117-promotion','exec'),{})
                    f.write_bytes(data)
                (bundle/'extra.txt').write_text('unapproved')
                with self.assertRaises(SystemExit):exec(compile(code,'extra-native117-promotion','exec'),{})
            finally:os.chdir(previous)

    def test_identity_changes_are_rejected(self):
        for mutation in [{'mod_id': 'other'}, {'minecraft_version': '1.10.2'}, {'mod_license': 'CC0-1.0'}]:
            with self.subTest(mutation=mutation), self.assertRaises(SystemExit):
                self.validate('1.11.2', mutation=mutation)

    def test_existing_release_is_not_uploaded_again(self):
        with self.assertRaises(SystemExit):
            self.validate('1.10.2', release_exists=True)

    def test_112_artifact_audit_skips_only_explicitly_scoped_out_builds(self):
        text=WORKFLOW.with_name('artifact-audit-1.12.yml').read_text()
        code=text.split('def artifact_required(jobs):',1)[1].split('\n          # Fetch',1)[0]
        code='def artifact_required(jobs):'+code
        import textwrap
        code=code.replace('\n          ','\n')
        namespace={};exec(compile(code,'artifact-scope','exec'),namespace)
        check=namespace['artifact_required']
        def job(name,conclusion,status='completed'):return dict(name=name,conclusion=conclusion,status=status)
        build=job('Build, test, and audit','success')
        self.assertTrue(check([build]))
        skipped=[job('Check runtime inputs','success'),job('Build, test, and audit','skipped'),job('Cold Forge bootstrap','skipped')]
        self.assertFalse(check(skipped))
        for invalid in [[],[build,build],skipped[1:],skipped[:2],
                        [dict(build,status='in_progress')],
                        [dict(build,conclusion='failure')],
                        [dict(skipped[0],conclusion='failure'),*skipped[1:]],
                        [*skipped[:2],dict(skipped[2],conclusion='failure')]]:
            with self.subTest(invalid=invalid),self.assertRaises(ValueError):check(invalid)
        self.assertIn("needs: scope",text)
        self.assertIn("if: needs.scope.outputs.required == 'true'",text)

    def test_tag_at_another_commit_is_rejected(self):
        with self.assertRaises(SystemExit):
            self.validate('1.10.2', wrong_tag=True)


if __name__ == '__main__':
    unittest.main()
