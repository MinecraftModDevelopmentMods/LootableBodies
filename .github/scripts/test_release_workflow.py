"""Test the dispatcher's actual selection and publication preflight scripts offline."""
import json
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

    def validate(self, minecraft, names=None, mutation=None, release_exists=False, wrong_tag=False, audit_success=True):
        suffix, branch, forge = {
            '1.10.2': ('110021', '1.10.2', '12.18.3.2511'),
            '1.11.2': ('111021', '1.11.2-dev', '13.20.1.2588'),
            '1.12.2': ('112021', 'master-1.12', '14.23.5.2859'),
            '1.13.2': ('113021', 'master-1.13.2', '25.0.223')}[minecraft]
        version = '2.4.2.' + suffix
        values = dict(minecraft_version=minecraft, forge_version=forge, loader_name='forge', loader_code='1',
            java_version='8', java_toolchain_version='8.0.502+7', gradle_java_version='17', mod_id='lootablebodies',
            mod_artifact='LootableBodies', mod_group='zone.moddev.mc.lootablebodies', mod_license='LGPL-2.1-only',
            curseforge_project_id='240968', mod_version=version)
        if mutation:
            values.update(mutation)
        checks = CORE + {'1.11.2': MATRIX, '1.12.2': MATRIX112,
                         '1.13.2': MATRIX113 + ['Validate release workflow']}.get(minecraft, []) if names is None else names
        def command(args, **options):
            if args[0] == 'git':
                return SHA + '\n'
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
                'GITHUB_OUTPUT': str(Path(directory) / 'output')}), \
                patch('pathlib.Path.read_text', return_value='\n'.join(k+'='+v for k,v in values.items())), \
                patch('subprocess.check_output', side_effect=command), patch('subprocess.run', side_effect=existing):
            exec(compile(code, 'release-preflight', 'exec'), {})

    def test_routes_all_supported_versions(self):
        for suffix, minecraft, branch in [('110021', '1.10.2', '1.10.2'), ('111021', '1.11.2', '1.11.2-dev'), ('112021', '1.12.2', 'master-1.12'), ('113021', '1.13.2', 'master-1.13.2')]:
            with self.subTest(minecraft=minecraft):
                status, values = self.select('2.4.2.' + suffix)
                self.assertEqual(0, status)
                self.assertEqual(minecraft, values['minecraft_version'])
                self.assertEqual(branch, values['source_branch'])

    def test_invalid_versions_are_rejected(self):
        for version in ['2.4.2', '2.4.2.114041', '02.4.2.111021', '2.4.2.111021;exit 0']:
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

    def test_identity_changes_are_rejected(self):
        for mutation in [{'mod_id': 'other'}, {'minecraft_version': '1.10.2'}, {'mod_license': 'CC0-1.0'}]:
            with self.subTest(mutation=mutation), self.assertRaises(SystemExit):
                self.validate('1.11.2', mutation=mutation)

    def test_existing_release_is_not_uploaded_again(self):
        with self.assertRaises(SystemExit):
            self.validate('1.10.2', release_exists=True)

    def test_tag_at_another_commit_is_rejected(self):
        with self.assertRaises(SystemExit):
            self.validate('1.10.2', wrong_tag=True)


if __name__ == '__main__':
    unittest.main()
