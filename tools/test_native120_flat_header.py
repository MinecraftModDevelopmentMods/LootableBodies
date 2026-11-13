import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from native_runtime import seed_flat_fixture
from nbt_regions import Tag,encode
from verify_native_respawn_control import require_forge_only_header


class Native120FlatHeaderTests(unittest.TestCase):
    def header(self,root,version=4903,generator='minecraft:flat'):
        settings=Tag(10,{'dimensions':Tag(10,{'minecraft:overworld':Tag(10,{
            'generator':Tag(10,{'type':Tag(8,generator)})})})})
        data=encode('',Tag(10,{'Data':Tag(10,{'DataVersion':Tag(3,version)})}))
        path=root/'native-header.dat';path.write_bytes(gzip.compress(data,mtime=0))
        worldgen=root/'native-worldgen.dat'
        worldgen.write_bytes(gzip.compress(encode('',Tag(10,{'DataVersion':Tag(3,version),'data':settings})),mtime=0))
        digest=hashlib.sha256(path.read_bytes()).hexdigest().upper()
        (root/'flat-inputs.json').write_text(json.dumps({digest:{'header':path.name,'worldgen':worldgen.name,
            'worldgen_sha256':hashlib.sha256(worldgen.read_bytes()).hexdigest().upper()}}))
        return path,digest
    def home(self,root):
        home=root/'home';home.mkdir();(home/'server.properties').touch();return home
    def test_only_the_two_explicit_sealed_inputs_are_copied(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);header,digest=self.header(root);before=header.read_bytes();home=self.home(root)
            with patch('native_runtime.FLAT_INPUTS_PATH',root/'flat-inputs.json'):
                seed_flat_fixture(home,header,digest)
            self.assertEqual(before,(home/'world/level.dat').read_bytes());self.assertEqual(before,header.read_bytes())
            files=sorted(p.relative_to(home/'world').as_posix() for p in (home/'world').rglob('*') if p.is_file())
            self.assertEqual(['data/minecraft/world_gen_settings.dat','level.dat'],files)
            self.assertEqual((root/'native-worldgen.dat').read_bytes(),(home/'world'/files[0]).read_bytes())
    def test_wrong_hash_target_or_generator_refuses_before_creating_world(self):
        for version,generator,bad_hash in [(4903,'minecraft:flat',True),(4790,'minecraft:flat',False),(4671,'minecraft:flat',False),(3955,'minecraft:flat',False),(3839,'minecraft:flat',False),(3465,'minecraft:flat',False),(2586,'minecraft:flat',False),(2730,'minecraft:flat',False),(2975,'minecraft:flat',False),(4903,'minecraft:noise',False)]:
            with self.subTest(version=version,generator=generator,bad_hash=bad_hash),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);header,digest=self.header(root,version,generator);home=self.home(root)
                with patch('native_runtime.FLAT_INPUTS_PATH',root/'flat-inputs.json'), self.assertRaises(RuntimeError):
                    seed_flat_fixture(home,header,'0'*64 if bad_hash else digest)
                self.assertFalse((home/'world').exists())
    def test_existing_world_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);header,digest=self.header(root);home=self.home(root);(home/'world').mkdir()
            protected=home/'world/level.dat';protected.write_bytes(b'existing world bytes')
            with self.assertRaises(FileExistsError):seed_flat_fixture(home,header,digest)
            self.assertEqual(b'existing world bytes',protected.read_bytes())

    def test_actual_native20_codec_header_is_a_sealed_current_target_input(self):
        header=Path(__file__).parent/'fixtures/native262-flat-level.dat'
        expected='5FCCA77C14DE23AD7AF73525B81C69F9AD650E0C1E73E9B81AFD9353CC9440FF'
        self.assertEqual(expected,hashlib.sha256(header.read_bytes()).hexdigest().upper())
        with tempfile.TemporaryDirectory() as directory:
            home=self.home(Path(directory));seed_flat_fixture(home,header,expected)
            self.assertEqual(header.read_bytes(),(home/'world/level.dat').read_bytes())

    def test_forge_only_control_rejects_native_header_with_the_mod_installed(self):
        fixtures=Path(__file__).parent/'fixtures'
        forge_only=fixtures/'native262-forge-only-flat-level.dat'
        expected='9C720AF9FD35B21B29B06A3C9683CF81159157BEE22870E048C3DC862594CBB2'
        self.assertEqual(expected,hashlib.sha256(forge_only.read_bytes()).hexdigest().upper())
        require_forge_only_header(forge_only)
        with self.assertRaisesRegex(ValueError,'Forge-only saved mod list'):
            require_forge_only_header(fixtures/'native262-flat-level.dat')


if __name__=='__main__':unittest.main()
