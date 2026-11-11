"""Forge owns server downloads; installed bytes are checked before gameplay."""
import hashlib,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import native_runtime as runtime

class VanillaServerPreparationTests(unittest.TestCase):
    def fixture(self,home,payload=b'exact installed native input'):
        relative='libraries/net/minecraft/server/1.21.1/server-1.21.1.jar'
        expected=hashlib.sha256(b'exact installed native input').hexdigest().upper()
        def installed(command,**options):
            self.assertIn('--installServer',command)
            self.assertEqual(home,options['cwd'])
            self.assertFalse((home/'versions').exists(),'Server path prefetched a separate vanilla descriptor')
            target=home/relative;target.parent.mkdir(parents=True);target.write_bytes(payload)
        return {relative:expected},installed
    def test_installer_owns_server_download_without_separate_vanilla_prefetch(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'new-sdk';pins,installed=self.fixture(home)
            with patch('prepare_migration_inputs.fetch'),patch.object(runtime,'_download') as prefetch,patch.object(runtime,'run_logged',side_effect=installed) as installer,patch.object(runtime,'SERVER_INPUTS',pins):
                self.assertEqual(home.resolve(),runtime.install(Path('fixture-java'),home))
                prefetch.assert_not_called();self.assertEqual(1,installer.call_count)
                self.assertTrue((home/'runtime-inputs.json').is_file())
    def test_wrong_installed_vanilla_bytes_refuse_before_gameplay_without_installer_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'new-sdk';pins,installed=self.fixture(home,b'changed bytes')
            with patch('prepare_migration_inputs.fetch'),patch.object(runtime,'_download') as prefetch,patch.object(runtime,'run_logged',side_effect=installed) as installer,patch.object(runtime,'SERVER_INPUTS',pins):
                with self.assertRaisesRegex(RuntimeError,'Incorrect official Forge 64 server input'):runtime.install(Path('fixture-java'),home)
                prefetch.assert_not_called();self.assertEqual(1,installer.call_count)
                self.assertFalse((home/'runtime-inputs.json').exists())
    def test_missing_installed_input_is_fatal(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'new-sdk';pins,_=self.fixture(home)
            with patch('prepare_migration_inputs.fetch'),patch.object(runtime,'_download') as prefetch,patch.object(runtime,'run_logged') as installer,patch.object(runtime,'SERVER_INPUTS',pins):
                with self.assertRaises(FileNotFoundError):runtime.install(Path('fixture-java'),home)
                prefetch.assert_not_called();self.assertEqual(1,installer.call_count)

if __name__=='__main__':unittest.main()
