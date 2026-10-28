from pathlib import Path
import tempfile,unittest
from audit_runtime_logs import audit

class Native116RespawnLogTest(unittest.TestCase):
    def check(self,text,**options):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'console.log';path.write_text(text);return audit(path,**options)
    def original(self):return (Path(__file__).parent/'fixtures/forge36-dead-player-movement.log').read_text()
    def test_only_the_complete_independently_reproduced_native_stack_is_classified(self):
        result=self.check(self.original(),native=True)
        self.assertEqual(1,result['known_forge36_dead_player_movement_failures'])
    def test_native_mode_is_required(self):
        with self.assertRaises(RuntimeError):self.check(self.original())
    def test_the_second_proven_native_loop_tail_is_classified(self):
        text=(Path(__file__).parent/'fixtures/forge36-dead-player-movement-alt-loop.log').read_text()
        self.assertEqual(1,self.check(text,native=True)['known_forge36_dead_player_movement_failures'])
    def test_changed_missing_extra_and_mod_frames_remain_fatal(self):
        original=self.original();lines=original.splitlines(True)
        for changed in [original.replace('TicketManager.java:227','TicketManager.java:228'),
                ''.join(lines[:5]+lines[6:]),original+'\tat cyano.lootable.Example.fail(Example.java:1)\n',
                original.replace('NullPointerException: null','NullPointerException: unexpected'),
                original+'[16:18:31] [Server thread/ERROR] [minecraft/Other]: Actual failure\n']:
            with self.subTest(text=changed),self.assertRaises(RuntimeError):self.check(changed,native=True)

if __name__=='__main__':unittest.main()
