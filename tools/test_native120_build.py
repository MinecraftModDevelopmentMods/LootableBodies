import json,tempfile,unittest
from pathlib import Path
from native120_build import check_bundle
from native_runtime import digest
from release_identity import MOD_VERSION,MAIN_JAR

class Native120BuildContractTests(unittest.TestCase):
    def test_missing_changed_extra_and_unqualified_inputs_are_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);bundle=root/'bundle';bundle.mkdir();manifest=root/'manifest.json'
            names=['CHANGELOG.txt','SHA256SUMS',MAIN_JAR,MAIN_JAR[:-4]+'-sources.jar',MAIN_JAR[:-4]+'-javadoc.jar',MAIN_JAR[:-4]+'.pom']
            for name in names:(bundle/name).write_bytes(name.encode())
            record={'version':MOD_VERSION,'minecraft':'1.21.1','forge':'52.1.20','local_qualification_complete':True,'qualification_integrity_status':'verified','files':{p.name:digest(p) for p in bundle.iterdir()}}
            manifest.write_text(json.dumps(record));self.assertEqual(record['files'],check_bundle(manifest,bundle))
            for mutation in ['unqualified','integrity-hold','wrong-target','missing-file','changed-file','extra-file','missing-identity']:
                with self.subTest(mutation=mutation):
                    changed=json.loads(json.dumps(record));extra=bundle/'extra'
                    if mutation=='unqualified':changed['local_qualification_complete']=False
                    elif mutation=='integrity-hold':changed['qualification_integrity_status']='blocked_receipt_mismatch'
                    elif mutation=='wrong-target':changed['minecraft']='1.17.1'
                    elif mutation=='missing-identity':del changed['files'][MAIN_JAR]
                    elif mutation=='missing-file':(bundle/MAIN_JAR).unlink()
                    elif mutation=='changed-file':(bundle/MAIN_JAR).write_bytes(b'changed')
                    else:extra.touch()
                    manifest.write_text(json.dumps(changed))
                    with self.assertRaises(ValueError):check_bundle(manifest,bundle)
                    (bundle/MAIN_JAR).write_bytes(MAIN_JAR.encode())
                    if extra.exists():extra.unlink()
    def test_hosted_target_contains_no_game_or_sdk_command(self):
        from pathlib import Path
        import re
        text=(Path(__file__).resolve().parents[1]/'.github/workflows/native120.yml').read_text()
        commands='\n'.join(re.findall(r'(?m)^        run: \|\n((?:          .*\n)+)',text))
        self.assertIn('./gradlew assemble verifyProductionRuntimeClasspath prepareReleaseBundle ',commands)
        for forbidden in ('./gradlew test','./gradlew build','./gradlew check','unittest discover','verify_world_upgrades.py','native_runtime.py','verify_packaged','native117_ci.py','integrationTest','--seed-only'):
            self.assertNotIn(forbidden,commands)
        uploads=re.findall(r'(?m)^        uses: actions/upload-artifact@[^\n]+\n(.*?)(?=^      -|\Z)',text,re.S)
        self.assertEqual(2,len(uploads));self.assertTrue(all('if-no-files-found: error' in s for s in uploads))
        independent=(Path(__file__).resolve().parents[1]/'.github/workflows/artifact-audit-1.20.yml').read_text()
        self.assertIn('python3 -B tools/native120_build.py --bundle audited',independent)
        self.assertIn('run-id: ${{ github.event.workflow_run.id }}',independent)
        self.assertIn('if-no-files-found: error',independent)
        for forbidden in ('./gradlew','actions/setup-java@','native_runtime.py','verify_packaged'):
            self.assertNotIn(forbidden,independent)

if __name__=='__main__':unittest.main()
