import hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import native117_ci as ci

class Native117CITests(unittest.TestCase):
    def test_hosted_workflow_keeps_gameplay_and_sdk_installation_local(self):
        workflow=(Path(ci.__file__).resolve().parents[1]/'.github/workflows/native117.yml').read_text()
        for token in ['native_runtime.py','scenario --group','migration_ci.py','xvfb-run','pulseaudio','prepareMinecraftAssets']:
            with self.subTest(token=token):self.assertNotIn(token,workflow)
        self.assertIn('test assemble javadoc verifyProductionRuntimeClasspath prepareReleaseBundle',workflow)

    def test_hosted_workflow_uploads_the_exact_six_files_for_publication(self):
        workflow=(Path(ci.__file__).resolve().parents[1]/'.github/workflows/native117.yml').read_text()
        self.assertIn('name: LootableBodies-1.17.1-${{ github.sha }}',workflow)
        self.assertIn('actual != expected',workflow)
        self.assertIn('if-no-files-found: error',workflow)

    def test_eclipse_gate_receives_the_real_prepared_client_natives_after_sdk_install(self):
        with tempfile.TemporaryDirectory() as d,patch.object(ci,'PROJECT',Path(d)):
            events=[];natives=Path(d)/'build/native-client-distribution/prepared-inputs/natives'
            def installer(name,tool,*args,**kwargs):
                events.append(name)
                if '--client' in args:natives.mkdir(parents=True)
            def eclipse(name,*tasks,**kwargs):
                self.assertTrue(natives.is_dir(),'IDE verification ran before actual client input preparation')
                self.assertIn('verifyEclipseProductionClasspath',tasks)
                self.assertIn('-PminecraftNativesDirectory=build/native-client-distribution/prepared-inputs/natives',tasks)
                events.append(name)
            with patch.object(ci,'python',side_effect=installer),patch.object(ci,'gradle',side_effect=eclipse):
                ci.native_sdk_and_eclipse(Path(d)/'jdk/bin/java')
            self.assertEqual(['native-server-install.log','native-client-install.log','eclipse-and-assets.log'],events)

    def test_only_the_six_accepted_bytes_can_be_promoted(self):
        with tempfile.TemporaryDirectory() as d,patch.object(ci,'PROJECT',Path(d)):
            target=Path(d)/'bundle';target.mkdir();expected={}
            for name in ['main.jar','sources.jar','javadoc.jar','release.pom','SHA256SUMS','CHANGELOG.txt']:
                data=('approved '+name).encode();(target/name).write_bytes(data);expected[name]=hashlib.sha256(data).hexdigest().upper()
            metadata=Path(d)/'.github';metadata.mkdir()
            (metadata/'release117.json').write_text(json.dumps({'version':ci.MOD_VERSION,'files':expected}))
            self.assertEqual(expected,ci.check_bundle(target))
            for name in expected:
                f=target/name;data=f.read_bytes();f.write_bytes(data+b'changed')
                with self.assertRaises(ValueError):ci.check_bundle(target)
                f.write_bytes(data)
            (target/'extra.txt').write_text('unexpected')
            with self.assertRaises(ValueError):ci.check_bundle(target)
    def test_unknown_scenario_fails_before_a_game_command(self):
        with tempfile.TemporaryDirectory() as d,patch.object(ci,'PROJECT',Path(d)),patch.object(ci,'python') as game,patch.dict('os.environ',{'JAVA_HOME_16_X64':d}):
            # Scenario outputs are confined to the checkout, including failures.
            import os
            previous=Path.cwd()
            try:
                os.chdir(d)
                with self.assertRaises(ValueError):ci.scenario('unknown')
            finally:os.chdir(previous)
            game.assert_not_called()
