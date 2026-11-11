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
    def header(self,root,version=4790,generator='minecraft:flat'):
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
        for version,generator,bad_hash in [(4790,'minecraft:flat',True),(4671,'minecraft:flat',False),(3955,'minecraft:flat',False),(3839,'minecraft:flat',False),(3465,'minecraft:flat',False),(2586,'minecraft:flat',False),(2730,'minecraft:flat',False),(2975,'minecraft:flat',False),(4790,'minecraft:noise',False)]:
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
        header=Path(__file__).parent/'fixtures/native2612-flat-level.dat'
        expected='149C39C40EEEB0241C0B15A9113BF72055FA7CB41FC55C2126BE79EF90330543'
        self.assertEqual(expected,hashlib.sha256(header.read_bytes()).hexdigest().upper())
        with tempfile.TemporaryDirectory() as directory:
            home=self.home(Path(directory));seed_flat_fixture(home,header,expected)
            self.assertEqual(header.read_bytes(),(home/'world/level.dat').read_bytes())

    def test_forge_only_control_rejects_native_header_with_the_mod_installed(self):
        fixtures=Path(__file__).parent/'fixtures'
        forge_only=fixtures/'native2612-forge-only-flat-level.dat'
        expected='E64804965621AD9F107A12A55F29EA2CC97C2218BFC92E8DE73AFB7C80F23EA3'
        self.assertEqual(expected,hashlib.sha256(forge_only.read_bytes()).hexdigest().upper())
        require_forge_only_header(forge_only)
        with self.assertRaisesRegex(ValueError,'Forge-only saved mod list'):
            require_forge_only_header(fixtures/'native2612-flat-level.dat')


if __name__=='__main__':unittest.main()
