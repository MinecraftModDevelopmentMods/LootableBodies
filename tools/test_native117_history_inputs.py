"""Exercise the actual sealed-archive reader before hosted preparation."""
import hashlib,io,json,tarfile,tempfile,unittest
from pathlib import Path
import prepare_native117_history as history

class Native117HistoryInputsTests(unittest.TestCase):
    def fixture(self,root,names=None):
        names=names or {'build/migration-seeds/original-110/world/level.dat':b'actual-original'}
        folder=root/'download';folder.mkdir();archive=folder/'inputs.tar.gz';files={}
        with tarfile.open(archive,'w:gz') as t:
            for name,data in names.items():
                i=tarfile.TarInfo(name);i.size=len(data);t.addfile(i,io.BytesIO(data))
                files[name]={'size':len(data),'sha256':hashlib.sha256(data).hexdigest().upper()}
        record={'head':history.SOURCE,'archive_sha256':history.sha(archive),'files':files}
        (folder/'inputs.json').write_text(json.dumps(record))
        return folder,history.sha(folder/'inputs.json'),history.sha(archive)
    def test_original_bytes_are_restored_once_without_regeneration(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);folder,manifest,archive=self.fixture(root);out=root/'original'
            history.extract_sealed(folder,out,manifest,archive)
            self.assertEqual(b'actual-original',(out/'build/migration-seeds/original-110/world/level.dat').read_bytes())
            with self.assertRaises(FileExistsError):history.extract_sealed(folder,out,manifest,archive)
    def test_changed_manifest_or_archive_fail_before_extraction(self):
        for target in ('inputs.json','inputs.tar.gz'):
            with self.subTest(target=target),tempfile.TemporaryDirectory() as d:
                root=Path(d);folder,manifest,archive=self.fixture(root);p=folder/target;p.write_bytes(p.read_bytes()+b'changed')
                with self.assertRaisesRegex(ValueError,'Pinned'):history.extract_sealed(folder,root/'out',manifest,archive)
                self.assertFalse((root/'out').exists())
    def test_traversal_and_unknown_archive_members_are_refused(self):
        for name in ('../outside','build/../../outside','C:/outside'):
            with self.subTest(name=name),tempfile.TemporaryDirectory() as d:
                root=Path(d);folder,manifest,archive=self.fixture(root,{name:b'bad'})
                with self.assertRaises(ValueError):history.extract_sealed(folder,root/'out',manifest,archive)
