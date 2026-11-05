import gzip
import hashlib
from pathlib import Path
import tempfile
import unittest
from native_runtime import seed_flat_fixture
from nbt_regions import Tag,encode


class Native119FlatHeaderTests(unittest.TestCase):
    def header(self,root,version=3337,generator='minecraft:flat'):
        settings=Tag(10,{'dimensions':Tag(10,{'minecraft:overworld':Tag(10,{
            'generator':Tag(10,{'type':Tag(8,generator)})})})})
        data=encode('',Tag(10,{'Data':Tag(10,{'DataVersion':Tag(3,version),'WorldGenSettings':settings})}))
        path=root/'native-header.dat';path.write_bytes(gzip.compress(data,mtime=0));return path,hashlib.sha256(path.read_bytes()).hexdigest().upper()
    def home(self,root):
        home=root/'home';home.mkdir();(home/'server.properties').touch();return home
    def test_only_the_explicit_sealed_header_is_copied(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);header,digest=self.header(root);before=header.read_bytes();home=self.home(root)
            seed_flat_fixture(home,header,digest)
            self.assertEqual(before,(home/'world/level.dat').read_bytes());self.assertEqual(before,header.read_bytes())
            self.assertEqual(['level.dat'],[p.name for p in (home/'world').iterdir()])
    def test_wrong_hash_target_or_generator_refuses_before_creating_world(self):
        for version,generator,bad_hash in [(3337,'minecraft:flat',True),(2586,'minecraft:flat',False),(2730,'minecraft:flat',False),(2975,'minecraft:flat',False),(3337,'minecraft:noise',False)]:
            with self.subTest(version=version,generator=generator,bad_hash=bad_hash),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);header,digest=self.header(root,version,generator);home=self.home(root)
                with self.assertRaises(RuntimeError):seed_flat_fixture(home,header,'0'*64 if bad_hash else digest)
                self.assertFalse((home/'world').exists())
    def test_existing_world_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);header,digest=self.header(root);home=self.home(root);(home/'world').mkdir()
            protected=home/'world/level.dat';protected.write_bytes(b'existing world bytes')
            with self.assertRaises(FileExistsError):seed_flat_fixture(home,header,digest)
            self.assertEqual(b'existing world bytes',protected.read_bytes())

    def test_actual_native19_codec_header_is_a_sealed_current_target_input(self):
        header=Path(__file__).parent/'fixtures/native119-flat-level.dat'
        expected='9792ED9B0BF8A00ACB6BFFAEB50DC8B9C462CD9B6C96A9E6ED5CA65CFD534A55'
        self.assertEqual(expected,hashlib.sha256(header.read_bytes()).hexdigest().upper())
        with tempfile.TemporaryDirectory() as directory:
            home=self.home(Path(directory));seed_flat_fixture(home,header,expected)
            self.assertEqual(header.read_bytes(),(home/'world/level.dat').read_bytes())


if __name__=='__main__':unittest.main()
