"""Ordinary development fixtures need complete native defaults before launch."""
from pathlib import Path
import tempfile,tomllib,unittest
from verify_normal_server import prepare_development_home

class OrdinaryServerSetupTest(unittest.TestCase):
    def test_complete_native_defaults_exist_before_gradle_starts_forge(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder)/'server';prepare_development_home(home)
            self.assertEqual({'versionCheck':True,'splashscreen':True,'maxThreads':-1,
                'defaultConfigPath':'defaultconfigs'},tomllib.loads((home/'config/fml.toml').read_text()))
            self.assertEqual([],list(home.rglob('*.jar')))

    def test_existing_choices_are_byte_identical_when_preparing_the_owned_home(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder)/'server';config=home/'config/fml.toml';config.parent.mkdir(parents=True)
            original=b'# Existing choices\r\nversionCheck = false\r\nmaxThreads = 7\r\n'
            config.write_bytes(original);prepare_development_home(home)
            self.assertEqual(original,config.read_bytes())

if __name__=='__main__':unittest.main()
