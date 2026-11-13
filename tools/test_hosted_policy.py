"""Prospective contracts. Deliberately not executed during the process review."""
import json,unittest
from pathlib import Path
from hosted_policy import gradle_roots,validate_workflow,require_qualified_manifest,check_source

class HostedPolicyTests(unittest.TestCase):
    def test_raw_jdk_version_is_rejected_before_java_preparation(self):
        text=(Path(__file__).resolve().parents[1]/'.github/workflows/native120.yml').read_text()
        with self.assertRaises(ValueError):validate_workflow(text.replace('25.0.3+9.0.LTS','25.0.3+99'))

    def test_hosted_roots_allow_archive_builds_and_reject_every_test_or_game_entry(self):
        base='./gradlew assemble verifyProductionRuntimeClasspath prepareReleaseBundle '
        self.assertEqual(3,len(gradle_roots(base+'--no-daemon 2>&1 | tee build.log')))
        for root in ('test','check','build','nativeBoundaryTest','integrationTest','clientIntegrationTest','forgeControlServer','runServer',':test','--init-script=extra.gradle','-PrunGames=true'):
            with self.subTest(root=root),self.assertRaises(ValueError):gradle_roots(base+root)

    def test_current_workflow_is_compile_archive_only(self):
        root=Path(__file__).resolve().parents[1];check_source(root)

    def test_injected_python_games_full_suites_and_sdk_installers_are_rejected(self):
        text=(Path(__file__).resolve().parents[1]/'.github/workflows/native120.yml').read_text()
        for command in ('python3 -m unittest discover -s tools','python3 tools/verify_world_upgrades.py','python3 tools/native_runtime.py','python3 tools/prepare_native117_tools.py','xvfb-run ./gradlew runClient','python3 tools/new_game_runner.py','curl https://example.invalid/server.jar','java -jar server.jar'):
            changed=text.replace('          chmod +x gradlew','          '+command+'\n          chmod +x gradlew')
            with self.subTest(command=command),self.assertRaises(ValueError):validate_workflow(changed)

    def test_missing_fork_guard_or_pipe_failure_guard_is_rejected(self):
        text=(Path(__file__).resolve().parents[1]/'.github/workflows/native120.yml').read_text()
        for removed in ("if: github.repository == 'MinecraftModDevelopmentMods/LootableBodies'",'set -euo pipefail','shell: bash'):
            with self.subTest(removed=removed),self.assertRaises(ValueError):validate_workflow(text.replace(removed,''))

    def test_manifest_boolean_cannot_override_an_integrity_hold(self):
        version='2.4.11.2602001';base='LootableBodies-'+version
        names=['CHANGELOG.txt','SHA256SUMS',base+'.jar',base+'-sources.jar',base+'-javadoc.jar',base+'.pom']
        good={'version':version,'minecraft':'26.2','forge':'65.1.5','local_qualification_complete':True,'qualification_integrity_status':'verified','files':{name:'A'*64 for name in names}}
        require_qualified_manifest(good)
        for field,value in [('qualification_integrity_status','blocked_receipt_mismatch'),('qualification_integrity_status',None),('local_qualification_complete',False),('minecraft','1.17.1')]:
            changed=dict(good);changed[field]=value
            with self.subTest(field=field,value=value),self.assertRaises(ValueError):require_qualified_manifest(changed)

    def test_missing_or_extra_file_declaration_is_fatal(self):
        version='2.4.11.2602001';base='LootableBodies-'+version
        good={'version':version,'minecraft':'26.2','forge':'65.1.5','local_qualification_complete':True,'qualification_integrity_status':'verified','files':{name:'A'*64 for name in ['CHANGELOG.txt','SHA256SUMS',base+'.jar',base+'-sources.jar',base+'-javadoc.jar',base+'.pom']}}
        for extra in (False,True):
            changed=json.loads(json.dumps(good))
            if extra:changed['files']['extra.jar']='B'*64
            else:changed['files'].pop(base+'.jar')
            with self.subTest(extra=extra),self.assertRaises(ValueError):require_qualified_manifest(changed)

if __name__=='__main__':unittest.main()
