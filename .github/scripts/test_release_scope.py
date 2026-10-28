"""Release routing changes do not require requalifying unchanged old gameplay."""
import unittest
from release_scope import runtime_required, SCOPE_JOB, NEEDS_SCOPE

class ReleaseScopeTest(unittest.TestCase):
    def test_only_release_routes_use_the_small_checks(self):
        self.assertFalse(runtime_required(['.github/workflows/deploy-release.yml','.github/scripts/test_release_workflow.py']))
    def test_new_target_audit_uses_the_small_checks(self):
        self.assertFalse(runtime_required(['.github/workflows/artifact-audit-1.16.yml']))
    def test_source_build_and_unknown_files_require_runtime(self):
        for name in ['src/main/java/Corpse.java','src/test/java/CorpseTest.java','tools/fixture_login.py','gradle/release.gradle','gradle.properties','build.gradle','README.md','settings.gradle','.github/workflows/codeql-analysis.yml','other.txt']:
            with self.subTest(name=name):self.assertTrue(runtime_required([name]))
    def test_empty_or_manual_input_fails_closed(self):
        self.assertTrue(runtime_required([]));self.assertTrue(runtime_required(['.github/workflows/deploy-release.yml'],manual=True))
    def test_missing_workflow_evidence_requires_runtime(self):
        self.assertTrue(runtime_required(['.github/workflows/build.yml']))
    def test_only_exact_scope_wiring_can_skip_runtime(self):
        original='name: Test\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: ./gradlew build\n'
        updated=original.replace('jobs:\n','jobs:\n'+SCOPE_JOB).replace('  build:\n','  build:\n'+NEEDS_SCOPE)
        pair={'.github/workflows/build.yml':(original,updated)}
        self.assertFalse(runtime_required(list(pair),workflow_pairs=pair))
        pair['.github/workflows/build.yml']=(original,updated.replace('./gradlew build','echo skipped'))
        self.assertTrue(runtime_required(list(pair),workflow_pairs=pair))
    def test_mixed_production_and_deploy_changes_require_runtime(self):
        self.assertTrue(runtime_required(['.github/workflows/deploy-release.yml','src/main/resources/META-INF/mods.toml']))
    def test_scope_scripts_are_themselves_tested_in_release_checks(self):
        self.assertFalse(runtime_required(['.github/scripts/release_scope.py','.github/scripts/test_release_scope.py']))

if __name__=='__main__':unittest.main()
