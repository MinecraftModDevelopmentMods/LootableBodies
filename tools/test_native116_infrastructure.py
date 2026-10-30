"""Native target infrastructure must never launch the inherited distribution."""
from pathlib import Path
import unittest
import native_runtime as runtime

class Native116InfrastructureTest(unittest.TestCase):
    def test_native_distribution_and_every_server_input_use_the_target(self):
        self.assertEqual('forge-1.17.1-37.1.1',runtime.FORGE)
        self.assertEqual('31F2319B1B9CA491AACCC020887240F8524A4FF2A8E0F8B795E17E889CBC043B',runtime.INSTALLER_SHA256)
        self.assertEqual(79,len(runtime.SERVER_INPUTS))
        self.assertEqual('35DB91BCD011CAD8B812BA165CD7F5AD934CEEEE9050621423F9E5D227D7F895',
            runtime.SERVER_INPUTS['libraries/net/minecraftforge/forge/1.17.1-37.1.1/forge-1.17.1-37.1.1-server.jar'])
        self.assertEqual('B0E2D21DBA2E7C55A7DD9BBF914ADA99B124692810158F970F8F7C20543C2311',
            runtime.SERVER_INPUTS['libraries/net/minecraftforge/forge/1.17.1-37.1.1/win_args.txt'])
        self.assertFalse(any('1.16.5' in path for path in runtime.SERVER_INPUTS))
    def test_development_assets_use_the_native_index(self):
        text=(Path(__file__).resolve().parents[1]/'gradle/assets.gradle').read_text()
        self.assertIn('indexes/1.17.json',text)
        self.assertNotIn('e9d0adb8f642abe422909ede50f651b2b58a3573',text)
    def test_every_released115_history_extends_the_complete_earlier_matrix(self):
        from verify_world_upgrades import ORIGINS,PHASES
        names={'released-115','recovery-115','via115-110','via115-111','via115-112','via115-113','via115-114'}
        earlier={name for name in ORIGINS if not name.startswith(('released-116','recovery-116','via116-'))}
        self.assertTrue(names.issubset(earlier));self.assertEqual(30,len(earlier))
        self.assertEqual(23,len(earlier-names));self.assertEqual(10,len(PHASES))
        self.assertEqual('DC7798F3E165EC369D8092324C9F85F51D7E633824D1BD04D946A60ADA3DDDA7',ORIGINS['released-115'][2])
    def test_public116_sources_extend_every_prior_origin_without_replacing_it(self):
        from verify_world_upgrades import ORIGINS
        names={'released-116','recovery-116',*(f'via116-{version}' for version in range(110,116))}
        self.assertEqual(38,len(ORIGINS));self.assertTrue(names.issubset(ORIGINS))
        self.assertEqual('E545CF725650E8A01C5863A4C92BCB2D23437D61C9441C2F5D27A5272EDDA229',ORIGINS['released-116'][2])

if __name__=='__main__':unittest.main()
