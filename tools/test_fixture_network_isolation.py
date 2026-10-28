"""Optional native HTTP checks are isolated only in fresh owned probe homes."""
from pathlib import Path
import tempfile
import unittest
from native_runtime import seed_loader_defaults,prepare_client
from verify_world_upgrades import prepare_historical_server


class OwnedFixtureNetworkIsolationTest(unittest.TestCase):
    def test_frozen_historical_runtime_receives_complete_owned_loader_settings(self):
        class FrozenRuntime:
            def prepare_server(self,home,*args,**kwargs):
                home.mkdir();self.received=(args,kwargs);return 'original return value'
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder)/'historical';runtime=FrozenRuntime()
            self.assertEqual('original return value',prepare_historical_server(runtime,home,'released jar',probe='original writer'))
            self.assertEqual((('released jar',),{'probe':'original writer'}),runtime.received)
            import tomllib
            self.assertEqual({'versionCheck':False,'splashscreen':True,'maxThreads':-1,'defaultConfigPath':'defaultconfigs'},tomllib.loads((home/'config/fml.toml').read_text()))

    def test_existing_frozen_runtime_configuration_is_never_overwritten(self):
        original=b'# Original choices\r\nversionCheck = true\r\nmaxThreads = 7\r\n'
        class FrozenRuntime:
            def prepare_server(self,home):
                (home/'config').mkdir(parents=True);(home/'config/fml.toml').write_bytes(original)
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder)/'historical';prepare_historical_server(FrozenRuntime(),home)
            self.assertEqual(original,(home/'config/fml.toml').read_bytes())
    def test_only_owned_deterministic_fixtures_disable_optional_metadata_requests(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder)
            seed_loader_defaults(home, deterministic=True)
            text=(home/'config/fml.toml').read_text()
            self.assertIn('versionCheck = false',text)
            self.assertIn('splashscreen = true',text)
            self.assertIn('maxThreads = -1',text)
            self.assertIn('defaultConfigPath = "defaultconfigs"',text)

    def test_ordinary_launch_retains_native_update_check_default(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder)
            seed_loader_defaults(home)
            self.assertIn('versionCheck = true',(home/'config/fml.toml').read_text())

    def test_existing_native_configuration_is_byte_identical(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder);path=home/'config/fml.toml';path.parent.mkdir()
            data=b'# Keep explicit choices\r\nversionCheck = true\r\nmaxThreads = 7\r\n'
            path.write_bytes(data)
            seed_loader_defaults(home,deterministic=True)
            self.assertEqual(data,path.read_bytes())

    def test_unreadable_existing_configuration_is_not_repaired_by_the_fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder);path=home/'config/fml.toml';path.parent.mkdir()
            data=b'\xff\xfe\x00opaque native configuration'
            path.write_bytes(data)
            seed_loader_defaults(home,deterministic=True)
            self.assertEqual(data,path.read_bytes())

    def test_only_probe_clients_disable_optional_metadata_requests(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);probe=root/'probe.jar';probe.write_bytes(b'owned fixture')
            home=root/'client';prepare_client(home,probe=probe)
            self.assertIn('versionCheck = false',(home/'config/fml.toml').read_text())


if __name__=='__main__':unittest.main()
