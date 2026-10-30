"""The installer must see exact prepared vanilla bytes before it starts."""
import hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import native_runtime as runtime
from native117_client import BASE_VERSION,BASE_SHA1

class VanillaServerPreparationTests(unittest.TestCase):
    def fixture(self,home,*,changed=False):
        payload=b'synthetic official server fixture'
        relative='libraries/net/minecraft/server/1.17.1/server-1.17.1.jar'
        server={'url':'https://fixture.invalid/server.jar','sha1':hashlib.sha1(payload).hexdigest()}
        calls=[]
        def download(record,path):
            calls.append((record,path));path.parent.mkdir(parents=True,exist_ok=True)
            if path.suffix=='.json':
                self.assertEqual(BASE_SHA1,record['sha1'])
                path.write_text(json.dumps({'id':BASE_VERSION,'downloads':{'server':server}}))
            else:
                self.assertEqual(home/relative,path);self.assertEqual(server,record)
                path.write_bytes(b'changed fixture' if changed else payload)
        return relative,payload,calls,download
    def test_prepared_server_uses_the_exact_installer_path_and_both_pins(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);relative,payload,calls,download=self.fixture(home)
            with patch.object(runtime,'_download',side_effect=download),patch.object(runtime,'SERVER_INPUTS',{relative:hashlib.sha256(payload).hexdigest().upper()}):
                self.assertEqual(home/relative,runtime.prepare_vanilla_server(home))
            self.assertEqual(2,len(calls));self.assertEqual(payload,(home/relative).read_bytes())
    def test_wrong_vanilla_bytes_refuse_before_the_installer_can_run(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'new-sdk';relative,payload,calls,download=self.fixture(home,changed=True)
            with patch.object(runtime,'_download',side_effect=download),patch.object(runtime,'SERVER_INPUTS',{relative:hashlib.sha256(payload).hexdigest().upper()}),patch('prepare_migration_inputs.fetch'),patch.object(runtime,'run_logged') as installer:
                with self.assertRaisesRegex(RuntimeError,'vanilla server checksum mismatch'):runtime.install(Path('fixture-java'),home)
                installer.assert_not_called()

if __name__=='__main__':unittest.main()
