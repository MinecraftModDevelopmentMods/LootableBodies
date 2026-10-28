"""Native target infrastructure must never launch the inherited distribution."""
from pathlib import Path
import unittest
import native_runtime as runtime

class Native116InfrastructureTest(unittest.TestCase):
    def test_native_distribution_and_every_server_input_use_the_target(self):
        self.assertEqual('forge-1.16.5-36.2.42',runtime.FORGE)
        self.assertEqual('DFA90C681F30BE2406548B6429BCCB68D95AF4F64AB19B8C58F6128FD50FF857',runtime.INSTALLER_SHA256)
        self.assertEqual({'forge-1.16.5-36.2.42.jar','minecraft_server.1.16.5.jar',
            'libraries/net/minecraftforge/forge/1.16.5-36.2.42/forge-1.16.5-36.2.42-server.jar'},set(runtime.SERVER_INPUTS))
    def test_development_assets_use_the_native_index(self):
        text=(Path(__file__).resolve().parents[1]/'gradle/assets.gradle').read_text()
        self.assertIn('indexes/1.16.json',text)
        self.assertNotIn('e9d0adb8f642abe422909ede50f651b2b58a3573',text)
    def test_every_released115_history_extends_the_complete_earlier_matrix(self):
        from verify_world_upgrades import ORIGINS,PHASES
        names={'released-115','recovery-115','via115-110','via115-111','via115-112','via115-113','via115-114'}
        self.assertTrue(names.issubset(ORIGINS));self.assertEqual(30,len(ORIGINS))
        self.assertEqual(23,len(set(ORIGINS)-names));self.assertEqual(10,len(PHASES))
        self.assertEqual('DC7798F3E165EC369D8092324C9F85F51D7E633824D1BD04D946A60ADA3DDDA7',ORIGINS['released-115'][2])

if __name__=='__main__':unittest.main()
