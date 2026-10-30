from pathlib import Path
import json
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from owned_process import OwnedProcess


class OwnedProcessTest(unittest.TestCase):
    def start(self,root,script,timeout):
        home=root/'home';home.mkdir()
        return OwnedProcess([sys.executable,'-u','-c',script],cwd=home,log=root/'client.log',timeout=timeout,
            diagnostic_command=[sys.executable,'-c','print("owned capture")'])
    def test_completed_process_preserves_same_stdout_alias_and_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p=self.start(root,'print("real owned output")',5)
            self.assertEqual(0,p.wait(timeout=5));self.assertEqual(0,p.poll())
            self.assertEqual((root/'client.log').read_bytes(),(root/'home/client.log').read_bytes())
            record=json.loads((root/'home/client.diagnostics/result.json').read_text())
            self.assertEqual(0,record['returncode']);self.assertFalse(record['timed_out'])
    def test_group_abort_reaps_its_owned_child_without_changing_runtime_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p=self.start(root,'import time; print("ready"); time.sleep(30)',30)
            p.terminate();p.wait(timeout=5)
            record=json.loads((root/'home/client.diagnostics/result.json').read_text())
            self.assertEqual(30,record['timeout']);self.assertEqual('owned_group_abort',record['stopped_reason'])
            self.assertFalse(record['timed_out']);self.assertIsNotNone(record['returncode'])
    def test_deadline_failure_still_keeps_a_failed_receipt_and_complete_stdout(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);p=self.start(root,'import time; print("before deadline"); time.sleep(30)',.3)
            with self.assertRaises(subprocess.TimeoutExpired):p.wait(timeout=5)
            record=json.loads((root/'home/client.diagnostics/result.json').read_text())
            self.assertTrue(record['timed_out']);self.assertNotEqual(0,record['returncode'])
            self.assertIn('before deadline',(root/'client.log').read_text())
    def test_console_alias_failure_does_not_leave_an_unowned_running_child(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch('owned_process.os.link',side_effect=OSError('injected alias failure')):
                with self.assertRaisesRegex(OSError,'injected alias'):
                    self.start(root,'import time; print("ready"); time.sleep(.5)',3)
            receipt=root/'home/client.diagnostics/result.json';deadline=time.monotonic()+4
            while time.monotonic()<deadline:
                record=json.loads(receipt.read_text())
                if record['returncode'] is not None:break
                time.sleep(.01)
            self.assertEqual('owned_group_abort',record.get('stopped_reason'))
            self.assertIsNotNone(record['returncode'])
    def test_owned_console_stop_uses_the_same_monitored_child(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);home=root/'home';home.mkdir()
            p=OwnedProcess([sys.executable,'-u','-c','import sys; print("ready"); print(sys.stdin.readline().strip())'],
                cwd=home,log=root/'server.log',timeout=5,diagnostic_command=[sys.executable,'-c','print("capture")'],console_input=True)
            p.stdin.write('stop\n');p.stdin.flush();self.assertEqual(0,p.wait(timeout=5))
            self.assertEqual('ready\nstop\n',(root/'server.log').read_text())


if __name__=='__main__':unittest.main()
