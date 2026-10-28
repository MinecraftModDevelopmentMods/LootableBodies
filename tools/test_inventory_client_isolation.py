"""Inventory graphics fixtures must not depend on the public skin service."""
from pathlib import Path
import subprocess,tempfile,unittest
from unittest.mock import patch
import verify_packaged_client as client


class InventoryClientIsolationTest(unittest.TestCase):
    def prepared_at_launch(self,mode,expected):
        class Prepared(Exception):pass
        with tempfile.TemporaryDirectory() as folder:
            project=Path(folder);home=project/'owned-client'
            jar=project/'build/libs'/client.MAIN_JAR
            jar.parent.mkdir(parents=True);jar.write_bytes(b'unchanged production bytes')
            def prepare(root,*args):root.mkdir()
            def launch(command,**options):
                self.assertEqual(home,options['cwd'])
                config=(home/'config/lootablebodies.cfg').read_text()
                self.assertIn('B:local_texture='+expected,config)
                self.assertEqual(b'unchanged production bytes',jar.read_bytes())
                raise Prepared()
            argv=['verify_packaged_client','--java',str(project/'java'),
                '--forge-client-home',str(project/'sdk'),'--output',str(home),'--mode',mode]
            with patch.object(client,'__file__',str(project/'tools/verify_packaged_client.py')),\
                    patch.object(client,'prepare_client',prepare),\
                    patch.object(client,'project_assets',return_value=project/'assets'),\
                    patch.object(client,'client_command',return_value=['owned-java']),\
                    patch.object(subprocess,'run',side_effect=launch),patch('sys.argv',argv),\
                    self.assertRaises(Prepared):
                client.main()

    def test_inventory_death_and_reload_fixture_uses_the_local_texture(self):
        self.prepared_at_launch('issues','true')

    def test_dedicated_skin_and_config_checks_keep_the_player_skin_path(self):
        for mode in ('skin','config'):
            with self.subTest(mode=mode):self.prepared_at_launch(mode,'false')


if __name__=='__main__':unittest.main()
