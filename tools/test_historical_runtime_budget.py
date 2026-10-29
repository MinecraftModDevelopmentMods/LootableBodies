"""All preservation cases share the native worker budget; ordinary games do not."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import native_runtime
from verify_world_upgrades import PHASES, run_upgrade
from verify_upgrade_abort import run_abort


class HistoricalRuntimeBudgetTests(unittest.TestCase):
    def test_every_historical_phase_bounds_native_reload_workers_without_changing_its_gate(self):
        launches = []

        def launch(java, home, phase, **options):
            launches.append((phase, options))
            (home/'world').mkdir(exist_ok=True)
            (home/'world'/(phase+'.pass')).touch()
            (home/(phase+'.log')).write_text('LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase='+phase+'\n')
            return subprocess.CompletedProcess([], 0)

        with tempfile.TemporaryDirectory() as directory, patch.object(native_runtime, 'run_java', launch):
            for phase in PHASES:
                home=Path(directory)/phase; home.mkdir()
                run_upgrade(Path('java'), home, phase)
        self.assertEqual(PHASES, [p for p, options in launches])
        for phase, options in launches:
            self.assertEqual(240, options['timeout'])
            self.assertIn('-XX:ActiveProcessorCount=2', options['jvm_args'])
            self.assertIn('-Dlootablebodies.phase='+phase, options['jvm_args'])

    def test_unsafe_refusals_use_the_same_budget_and_keep_their_shorter_deadline(self):
        with patch.object(native_runtime, 'run_java', return_value=subprocess.CompletedProcess([], 1)) as launch:
            run_abort(Path('java'), Path('owned'))
        options=launch.call_args.kwargs
        self.assertEqual(180, options['timeout'])
        self.assertIn('-XX:ActiveProcessorCount=2', options['jvm_args'])
        self.assertNotIn('-Dlootablebodies.phase=abort', options['jvm_args'])

    def test_ordinary_packaged_game_keeps_the_default_native_processor_count(self):
        arguments=[]

        def launch(java, home, phase, **options):
            arguments.extend(options['jvm_args'])
            (home/'world').mkdir()
            (home/'world'/(phase+'.pass')).touch()
            (home/(phase+'.log')).write_text('ORDINARY_RUNTIME_COMPLETE\n')
            return subprocess.CompletedProcess([], 0)

        with tempfile.TemporaryDirectory() as directory, patch.object(native_runtime, 'run_java', launch):
            native_runtime.run_server(Path('java'), Path(directory), 'ordinary')
        self.assertFalse(any(p.startswith('-XX:ActiveProcessorCount') for p in arguments))


if __name__ == '__main__':
    unittest.main()
