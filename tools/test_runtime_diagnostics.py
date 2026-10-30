"""Real child-process contracts for evidence captured before a runtime deadline."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from runtime_diagnostics import run_monitored


class RuntimeDeadlineTests(unittest.TestCase):
    def test_evidence_write_failure_cannot_leave_the_runtime_or_collector_alive(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            owned = []
            real_popen, real_replace = subprocess.Popen, Path.replace

            def launch(*args, **kwargs):
                process = real_popen(*args, **kwargs)
                owned.append(process)
                return process

            def replace(path, destination):
                if path.parent == home/'phase.diagnostics' and len(owned) >= 2:
                    raise OSError('simulated unavailable evidence volume')
                return real_replace(path, destination)

            try:
                with patch('runtime_diagnostics.subprocess.Popen', side_effect=launch), \
                     patch.object(Path, 'replace', replace), self.assertRaises(OSError):
                    self.run_child(home, 'import time; time.sleep(30)', collector=[
                        sys.executable, '-c', 'import time; time.sleep(30)'])
                self.assertEqual(2, len(owned))
                self.assertTrue(all(p.poll() is not None for p in owned),
                                'Evidence failures must still reap both owned children')
            finally:
                # The red regression must not leave its intentionally exposed child alive.
                for process in owned:
                    if process.poll() is None:
                        process.kill()
                    process.wait()

    def run_child(self, home, code, *, timeout=1.2, collector=None, metric=None):
        return run_monitored(
            [sys.executable, '-u', '-c', code], cwd=home,
            log=home/'phase.log', timeout=timeout,
            diagnostics=home/'phase.diagnostics',
            diagnostic_command=collector or [sys.executable, '-c',
                'import sys; print("THREAD_CAPTURE_TARGET="+sys.argv[1], flush=True)'],
            metric_command=metric)

    def test_heap_metrics_are_captured_even_if_a_gc_log_has_no_events(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child(home, 'import time; time.sleep(30)', metric=[
                    sys.executable, '-c', 'from pathlib import Path; import sys,time; '
                    'seen=Path("heap-seen"); time.sleep(30) if seen.exists() else None; '
                    'seen.touch(); print("HEAP_TARGET="+sys.argv[1],flush=True)'])
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertTrue(report['metrics'])
            self.assertTrue(any(m['returncode']==0 for m in report['metrics']))
            self.assertTrue(any(m['timed_out'] for m in report['metrics']))
            for metric in report['metrics']:
                # A later collector must stop at the existing runtime deadline.
                # Require actual heap output and retain every bounded stop.
                self.assertTrue(metric['returncode']==0 or
                                (metric['timed_out'] and metric['returncode'] is not None))
                if metric['returncode']==0:
                    self.assertIn('HEAP_TARGET='+str(report['pid']),
                                  (home/'phase.diagnostics'/metric['file']).read_text())

    def test_a_stuck_heap_collector_is_killed_without_extending_the_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            before = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child(home, 'import time; time.sleep(30)', metric=[
                    sys.executable, '-c', 'import time; time.sleep(30)'])
            self.assertLess(time.monotonic()-before, 4)
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertTrue(report['metrics'])
            self.assertTrue(all(m['timed_out'] and m['returncode'] is not None
                                for m in report['metrics']))

    def test_live_evidence_survives_timeout_even_with_a_pass_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            before = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child(home,
                    'from pathlib import Path; import time; '
                    'Path("phase.pass").write_text("premature marker"); '
                    'print("STARTED", flush=True); time.sleep(30)')
            self.assertLess(time.monotonic()-before, 4)
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertTrue(report['timed_out'])
            self.assertIsNotNone(report['returncode'])
            self.assertTrue(report['captures'])
            for capture in report['captures']:
                self.assertLess(capture['started_seconds'], 1.2)
                self.assertEqual(0, capture['returncode'])
                self.assertIn('THREAD_CAPTURE_TARGET='+str(report['pid']),
                              (home/'phase.diagnostics'/capture['file']).read_text())
            self.assertIn('STARTED', (home/'phase.log').read_text())

    def test_a_stuck_collector_cannot_extend_the_runtime_or_survive_it(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            before = time.monotonic()
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child(home, 'import time; time.sleep(30)',
                    collector=[sys.executable, '-u', '-c',
                               'import time; print("ATTACH_STARTED", flush=True); time.sleep(30)'])
            self.assertLess(time.monotonic()-before, 4)
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertTrue(report['timed_out'])
            self.assertTrue(report['captures'])
            self.assertTrue(all(c['timed_out'] and c['returncode'] is not None
                                for c in report['captures']))

    def test_failed_attach_is_retained_and_does_not_mask_the_runtime_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            with self.assertRaises(subprocess.TimeoutExpired):
                self.run_child(home, 'import time; time.sleep(30)',
                    collector=[sys.executable, '-c',
                               'from pathlib import Path; import sys,time; '
                               'seen=Path("attach-seen"); '
                               'time.sleep(30) if seen.exists() else None; '
                               'seen.touch(); print("attach refused", flush=True); sys.exit(9)'])
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertTrue(report['timed_out'])
            self.assertTrue(any(c['returncode'] == 9 for c in report['captures']))
            # A later attach may still be running at the fixed deadline. It
            # must be reaped, rather than extending the runtime to obtain 9.
            self.assertTrue(any(c['timed_out'] for c in report['captures']))
            self.assertTrue(all(c['returncode'] == 9 or
                                (c['timed_out'] and c['returncode'] is not None)
                                for c in report['captures']))
            self.assertIn('attach refused',
                          (home/'phase.diagnostics'/report['captures'][0]['file']).read_text())

    def test_nonzero_exit_preserves_complete_output_without_a_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            result = self.run_child(home,
                'import sys; print("REAL_FAILURE", flush=True); sys.exit(7)')
            self.assertEqual(7, result.returncode)
            self.assertIn('REAL_FAILURE', (home/'phase.log').read_text())
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertFalse(report['timed_out'])
            self.assertEqual(7, report['returncode'])
            self.assertEqual([], report['captures'])

    def test_success_is_recorded_without_waiting_for_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            result = self.run_child(home, 'print("COMPLETE")')
            self.assertEqual(0, result.returncode)
            self.assertEqual('COMPLETE', (home/'phase.log').read_text().strip())
            report = json.loads((home/'phase.diagnostics/result.json').read_text())
            self.assertEqual([], report['captures'])
            self.assertFalse(report['timed_out'])

    def test_existing_evidence_refuses_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            (home/'phase.diagnostics').mkdir()
            (home/'phase.diagnostics/original.txt').write_text('keep')
            with self.assertRaises(FileExistsError):
                self.run_child(home, 'from pathlib import Path; Path("launched").touch()')
            self.assertFalse((home/'launched').exists())
            self.assertEqual('keep', (home/'phase.diagnostics/original.txt').read_text())


if __name__ == '__main__':
    unittest.main()
