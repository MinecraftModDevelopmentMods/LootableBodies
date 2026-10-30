"""Death-screen proof requires both the real render receipt and receiving wire."""
import json,tempfile,unittest
from pathlib import Path
import verify_visibility as verifier

class VisibilityDeathProofTest(unittest.TestCase):
    def fixture(self,root):
        (root/'sync').mkdir();(root/'clientA').mkdir()
        packets=[]
        for phase,name,identity in [(2,'first','11111111-1111-4111-8111-111111111111'),(14,'second','22222222-2222-4222-8222-222222222222')]:
            (root/'sync'/(name+'.uuid')).write_text(identity)
            receipt=dict(phase=phase,uuid=identity,death_screen_drawn=True,live_corpse_rendered=True)
            (root/'sync'/('A.death-render-'+str(phase)+'.json')).write_text(json.dumps(receipt))
            packets.append('1 500 IN MOD_SPAWN id=4 mod=lootablebodies type=1 uuid='+identity)
        (root/'clientA/client-packets.txt').write_text('\n'.join(packets)+'\n')
    def test_both_actual_deaths_require_render_and_tcp_proof(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.fixture(root)
            self.assertEqual([2,14],[x['phase'] for x in verifier.death_render_proof(root)])
    def test_missing_second_death_is_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.fixture(root);(root/'sync/A.death-render-14.json').unlink()
            with self.assertRaises(FileNotFoundError):verifier.death_render_proof(root)
    def test_wrong_identity_phase_or_render_event_is_fatal(self):
        for key,value in [('uuid','wrong'),('phase',3),('death_screen_drawn',False),('live_corpse_rendered',False),('death_screen_drawn',1)]:
            with self.subTest(key=key,value=value),tempfile.TemporaryDirectory() as d:
                root=Path(d);self.fixture(root);file=root/'sync/A.death-render-2.json'
                receipt=json.loads(file.read_text());receipt[key]=value;file.write_text(json.dumps(receipt))
                with self.assertRaises(RuntimeError):verifier.death_render_proof(root)
    def test_an_outgoing_or_wrong_mod_packet_is_not_a_received_corpse(self):
        for before,after in [(' IN ',' OUT '),('mod=lootablebodies ','mod=other ')]:
            with self.subTest(after=after),tempfile.TemporaryDirectory() as d:
                root=Path(d);self.fixture(root);file=root/'clientA/client-packets.txt';file.write_text(file.read_text().replace(before,after))
                with self.assertRaises(RuntimeError):verifier.death_render_proof(root)
    def test_dropped_packet_cannot_qualify_a_render_receipt(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.fixture(root);file=root/'clientA/client-packets.txt'
            file.write_text(file.read_text()+'3 501 FAULT_DROPPED MOD_SPAWN uuid=11111111-1111-4111-8111-111111111111\n')
            with self.assertRaises(RuntimeError):verifier.death_render_proof(root)

if __name__=='__main__':unittest.main()
