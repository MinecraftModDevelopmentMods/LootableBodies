from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from owned_process import OwnedProcess,native_process


class OwnedProcessTest(unittest.TestCase):
    def start(self,root,script,timeout,*,initial_diagnostics=None):
        home=root/'home';home.mkdir()
        return OwnedProcess([sys.executable,'-u','-c',script],cwd=home,log=root/'client.log',timeout=timeout,
            diagnostic_command=[sys.executable,'-c','print("owned capture")'],initial_diagnostics=initial_diagnostics)
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
            root=Path(directory)
            def wait_for_fixture_output(process):
                log=root/'home/client.log'
                ready_by=time.monotonic()+3
                while b'before deadline' not in log.read_bytes():
                    if process.poll() is not None or time.monotonic()>=ready_by:
                        raise AssertionError('Synthetic child never wrote its startup line')
                    time.sleep(.01)
            p=self.start(root,'import time; print("before deadline"); time.sleep(30)',.3,
                         initial_diagnostics=wait_for_fixture_output)
            with self.assertRaises(subprocess.TimeoutExpired):p.wait(timeout=5)
            record=json.loads((root/'home/client.diagnostics/result.json').read_text())
            self.assertTrue(record['timed_out']);self.assertNotEqual(0,record['returncode'])
            self.assertIn('before deadline',(root/'client.log').read_text())
    def test_group_cleanup_reaps_later_children_after_an_already_recorded_deadline(self):
        evidence=os.environ.get('LOOTABLEBODIES_UNIT_EVIDENCE')
        if evidence:Path(evidence).mkdir(parents=True,exist_ok=True)
        root=Path(tempfile.mkdtemp(prefix='owned-group-reap-',dir=evidence));children=[];passed=False
        print('OWNED_GROUP_REAP_FIXTURE='+str(root))
        try:
            for name in ('expired','other'):(root/name).mkdir()
            expired=self.start(root/'expired','import time; print("expired child"); time.sleep(30)',.2);children.append(expired)
            other=self.start(root/'other','import time; print("other child"); time.sleep(30)',30);children.append(other)
            with self.assertRaises(subprocess.TimeoutExpired):expired.wait(timeout=5)
            for child in children:
                if child.poll() is None:child.terminate()
            # The old cleanup API rethrows the already-recorded first deadline
            # here and never joins the later member of the group.
            codes=[getattr(child,'reap',child.wait)(timeout=5) for child in children]
            self.assertTrue(all(code!=0 for code in codes))
            self.assertTrue(all(child._process.poll() is not None for child in children))
            first=json.loads((root/'expired/home/client.diagnostics/result.json').read_text())
            second=json.loads((root/'other/home/client.diagnostics/result.json').read_text())
            self.assertTrue(first['timed_out']);self.assertEqual(.2,first['timeout'])
            self.assertEqual('owned_group_abort',second['stopped_reason']);self.assertFalse(second['timed_out'])
            with self.assertRaises(subprocess.TimeoutExpired):expired.wait(timeout=5)
            passed=True
        finally:
            for child in children:
                child.terminate()
                try:child.wait(timeout=5)
                except subprocess.TimeoutExpired:pass
            if passed:shutil.rmtree(root)
            else:print('RETAINED_FAILED_OWNED_GROUP='+str(root))
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

    def test_short_process_keeps_initial_evidence_inside_original_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);home=root/'home';home.mkdir()
            def initial(process):
                self.assertIsNone(process.poll())
                for name in ['initial-threads.txt','initial-heap-gc.txt']:
                    (home/'short.diagnostics'/name).write_text('synthetic callback test pid='+str(process.pid))
            process=OwnedProcess([sys.executable,'-u','-c','import time; time.sleep(.1)'],cwd=home,
                log=root/'short.log',timeout=5,diagnostic_command=[sys.executable,'-c','print("capture")'],initial_diagnostics=initial)
            self.assertEqual(0,process.wait(timeout=5))
            receipt=json.loads((home/'short.diagnostics/result.json').read_text())
            self.assertEqual(5,receipt['timeout'])
            for name in ['initial-threads.txt','initial-heap-gc.txt']:self.assertIn(name,receipt['evidence'])

    def test_initial_diagnostic_failure_reaps_runtime_and_preserves_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);home=root/'home';home.mkdir()
            def initial(process):raise RuntimeError('injected initial diagnostic failure')
            process=OwnedProcess([sys.executable,'-u','-c','import time; time.sleep(30)'],cwd=home,
                log=root/'failure.log',timeout=5,diagnostic_command=[sys.executable,'-c','print("capture")'],initial_diagnostics=initial)
            with self.assertRaisesRegex(RuntimeError,'initial diagnostic'):process.wait(timeout=5)
            receipt=json.loads((home/'failure.diagnostics/result.json').read_text())
            self.assertIsNotNone(receipt['returncode']);self.assertNotEqual(0,receipt['returncode']);self.assertFalse(receipt['timed_out'])

    def test_previous_runtime_and_wrong_patch_refuse_before_group_launch(self):
        for version,vendor in [('16.0.2+7','Eclipse Foundation'),('17.0.20.1+1','Eclipse Adoptium'),('21.0.8+9-LTS','Eclipse Adoptium'),('21.0.7+6-LTS','Eclipse Adoptium'),('25.0.3+8-LTS','Eclipse Adoptium'),('25.0.3+9-LTS','Other Vendor')]:
            with self.subTest(version=version),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);java=root/'jdk/bin/java.exe';java.parent.mkdir(parents=True)
                (java.parent.parent/'release').write_text('JAVA_RUNTIME_VERSION="'+version+'"\nIMPLEMENTOR="'+vendor+'"\n')
                with patch('owned_process.OwnedProcess') as launch,self.assertRaisesRegex(RuntimeError,'25.0.3'):
                    native_process([str(java)],cwd=root,log=root/'runtime.log',timeout=5)
                launch.assert_not_called()


if __name__=='__main__':unittest.main()
