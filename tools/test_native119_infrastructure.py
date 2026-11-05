"""Native target infrastructure must never launch the inherited distribution."""
from pathlib import Path
import unittest
import native_runtime as runtime

class Native119InfrastructureTest(unittest.TestCase):
    def test_native_distribution_and_every_server_input_use_the_target(self):
        self.assertEqual('forge-1.19.4-45.4.5',runtime.FORGE)
        self.assertEqual('1B4475D6ECE01E7242855504FBE0FE37AC79EFD7B3281BF42B8DCF390D421F35',runtime.INSTALLER_SHA256)
        self.assertEqual(105,len(runtime.SERVER_INPUTS))
        self.assertEqual('FFA7F65BBCB88AEA51AC5B2D37DCCE89DFCDEED0E54EDCCD6CB915E084E288D3',
            runtime.SERVER_INPUTS['libraries/net/minecraftforge/forge/1.19.4-45.4.5/forge-1.19.4-45.4.5-server.jar'])
        self.assertEqual('5CFAE247E85ECBB7A0E98250635C6DC94C711B8DF74BEF2ACBF18A39792563B6',
            runtime.SERVER_INPUTS['libraries/net/minecraftforge/forge/1.19.4-45.4.5/win_args.txt'])
        self.assertFalse(any('1.16.5' in path for path in runtime.SERVER_INPUTS))
    def test_development_assets_use_the_native_index(self):
        text=(Path(__file__).resolve().parents[1]/'gradle/assets.gradle').read_text()
        self.assertIn("minecraftAssetIndex = '3'",text)
        self.assertIn("1.19.4.json",text)
        self.assertNotIn('e9d0adb8f642abe422909ede50f651b2b58a3573',text)
    def test_every_released115_history_extends_the_complete_earlier_matrix(self):
        from verify_world_upgrades import ORIGINS,PHASES
        names={'released-115','recovery-115','via115-110','via115-111','via115-112','via115-113','via115-114'}
        earlier={name for name in ORIGINS if not name.startswith(('released-116','recovery-116','via116-','released-117','recovery-117','via117-','released-118','recovery-118','via118-'))}
        self.assertTrue(names.issubset(earlier));self.assertEqual(30,len(earlier))
        self.assertEqual(23,len(earlier-names));self.assertEqual(10,len(PHASES))
        self.assertEqual('DC7798F3E165EC369D8092324C9F85F51D7E633824D1BD04D946A60ADA3DDDA7',ORIGINS['released-115'][2])
    def test_public116_sources_extend_every_prior_origin_without_replacing_it(self):
        from verify_world_upgrades import ORIGINS
        names={'released-116','recovery-116',*(f'via116-{version}' for version in range(110,116))}
        earlier={name for name in ORIGINS if not name.startswith(('released-117','recovery-117','via117-','released-118','recovery-118','via118-'))}
        self.assertEqual(38,len(earlier));self.assertTrue(names.issubset(earlier))
        self.assertEqual('E545CF725650E8A01C5863A4C92BCB2D23437D61C9441C2F5D27A5272EDDA229',ORIGINS['released-116'][2])
    def test_public117_adds_nine_origins_without_replacing_the_original38(self):
        from verify_world_upgrades import ORIGINS
        names={'released-117','recovery-117',*(f'via117-{version}' for version in range(110,117))}
        earlier={name for name in ORIGINS if not name.startswith(('released-118','recovery-118','via118-'))}
        self.assertEqual(47,len(earlier));self.assertEqual(9,len(names));self.assertTrue(names.issubset(earlier));self.assertEqual(38,len(earlier-names))
        self.assertEqual('854A46D80B8599966E43B3EAED407CD1A99FE4FB019AEE9138FE2F98E8DDA9FA',ORIGINS['released-117'][2])

    def test_public118_adds_ten_origins_without_replacing_the_actual47(self):
        from verify_world_upgrades import ORIGINS
        names={'released-118','recovery-118',*(f'via118-{version}' for version in range(110,118))}
        self.assertEqual(57,len(ORIGINS));self.assertEqual(10,len(names));self.assertTrue(names.issubset(ORIGINS));self.assertEqual(47,len(set(ORIGINS)-names))
        self.assertEqual('34EF41EFCE4666A684EA202DAF8712162A1C403A90120BA11EF0260533FB690F',ORIGINS['released-118'][2])

if __name__=='__main__':unittest.main()
