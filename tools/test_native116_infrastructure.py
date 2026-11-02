"""Native target infrastructure must never launch the inherited distribution."""
from pathlib import Path
import unittest
import native_runtime as runtime

class Native116InfrastructureTest(unittest.TestCase):
    def test_native_distribution_and_every_server_input_use_the_target(self):
        self.assertEqual('forge-1.18.2-40.3.12',runtime.FORGE)
        self.assertEqual('DCFF493F6C2212BDF6AE087F98029CD317A79759AB1009DCE23E63E7DBA0E216',runtime.INSTALLER_SHA256)
        self.assertEqual(95,len(runtime.SERVER_INPUTS))
        self.assertEqual('92DE04B82205209189D6E501516D0CB1EC72A97E9779A4F11D058940DE04C3C1',
            runtime.SERVER_INPUTS['libraries/net/minecraftforge/forge/1.18.2-40.3.12/forge-1.18.2-40.3.12-server.jar'])
        self.assertEqual('897DE75C036F1E5E2ED79F77271FB1CE8803A8A416107ACB339B43CA220B87CD',
            runtime.SERVER_INPUTS['libraries/net/minecraftforge/forge/1.18.2-40.3.12/win_args.txt'])
        self.assertFalse(any('1.16.5' in path for path in runtime.SERVER_INPUTS))
    def test_development_assets_use_the_native_index(self):
        text=(Path(__file__).resolve().parents[1]/'gradle/assets.gradle').read_text()
        self.assertIn('indexes/1.18.json',text)
        self.assertNotIn('e9d0adb8f642abe422909ede50f651b2b58a3573',text)
    def test_every_released115_history_extends_the_complete_earlier_matrix(self):
        from verify_world_upgrades import ORIGINS,PHASES
        names={'released-115','recovery-115','via115-110','via115-111','via115-112','via115-113','via115-114'}
        earlier={name for name in ORIGINS if not name.startswith(('released-116','recovery-116','via116-','released-117','recovery-117','via117-'))}
        self.assertTrue(names.issubset(earlier));self.assertEqual(30,len(earlier))
        self.assertEqual(23,len(earlier-names));self.assertEqual(10,len(PHASES))
        self.assertEqual('DC7798F3E165EC369D8092324C9F85F51D7E633824D1BD04D946A60ADA3DDDA7',ORIGINS['released-115'][2])
    def test_public116_sources_extend_every_prior_origin_without_replacing_it(self):
        from verify_world_upgrades import ORIGINS
        names={'released-116','recovery-116',*(f'via116-{version}' for version in range(110,116))}
        earlier={name for name in ORIGINS if not name.startswith(('released-117','recovery-117','via117-'))}
        self.assertEqual(38,len(earlier));self.assertTrue(names.issubset(earlier))
        self.assertEqual('E545CF725650E8A01C5863A4C92BCB2D23437D61C9441C2F5D27A5272EDDA229',ORIGINS['released-116'][2])
    def test_public117_adds_nine_origins_without_replacing_the_original38(self):
        from verify_world_upgrades import ORIGINS
        names={'released-117','recovery-117',*(f'via117-{version}' for version in range(110,117))}
        self.assertEqual(47,len(ORIGINS));self.assertEqual(9,len(names));self.assertTrue(names.issubset(ORIGINS));self.assertEqual(38,len(set(ORIGINS)-names))
        self.assertEqual('854A46D80B8599966E43B3EAED407CD1A99FE4FB019AEE9138FE2F98E8DDA9FA',ORIGINS['released-117'][2])

if __name__=='__main__':unittest.main()
