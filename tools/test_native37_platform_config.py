import tempfile,unittest
from pathlib import Path
from native_runtime import fingerprint
from verify_upgrade_abort import allowed_native37_platform_additions

class Native37ConfigTest(unittest.TestCase):
    def target(self,root,text=None):
        path=root/'serverconfig/forge-server.toml';path.parent.mkdir()
        path.write_bytes(text or (Path(__file__).parent/'fixtures/forge37-server-default.toml').read_bytes());return path
    def old(self):return (Path(__file__).parent/'fixtures/forge36-server-default.toml').read_bytes()
    def test_exact_actual_native_default_addition(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);self.target(world);after=fingerprint(world)
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_native37_platform_additions({},after,world))
    def test_exact_actual_native_spec_retirement_preserves_supported_choices(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);path=self.target(world,self.old().replace(b'fullBoundingBoxLadders = false',b'fullBoundingBoxLadders = true'));original=path.read_bytes();before=fingerprint(world)
            path.write_bytes((Path(__file__).parent/'fixtures/forge37-server-default.toml').read_bytes().replace(b'fullBoundingBoxLadders = false',b'fullBoundingBoxLadders = true'))
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_native37_platform_additions(before,fingerprint(world),world,original))
    def test_supported_mutation_unknown_retirement_or_wrong_type_cannot_pass(self):
        for change in ['value','unknown','type']:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as directory:
                world=Path(directory);original=self.old()
                if change=='unknown':original+=b'unknownFlag = true\n'
                if change=='type':original=original.replace(b'removeErroringEntities = false',b'removeErroringEntities = 0')
                path=self.target(world,original);before=fingerprint(world);target=(Path(__file__).parent/'fixtures/forge37-server-default.toml').read_bytes()
                if change=='value':target=target.replace(b'fullBoundingBoxLadders = false',b'fullBoundingBoxLadders = true')
                path.write_bytes(target)
                self.assertEqual([],allowed_native37_platform_additions(before,fingerprint(world),world,original))

if __name__=='__main__':unittest.main()
