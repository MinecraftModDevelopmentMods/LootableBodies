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
    def test_every_historical_phase_keeps_native47_defaults_without_changing_its_gate(self):
        launches = []

        def launch(java, home, phase, **options):
            launches.append((phase, options))
            (home/'world').mkdir(exist_ok=True)
            (home/'world'/(phase+'.pass')).touch()
            (home/(phase+'.log')).write_text('LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase='+phase+'\n')
            for name in ['latest.log','debug.log']:(home/(phase+'.'+name)).write_text('Synthetic complete native log fixture\n')
            return subprocess.CompletedProcess([], 0)

        with tempfile.TemporaryDirectory() as directory, patch.object(native_runtime, 'run_java', launch):
            for phase in PHASES:
                home=Path(directory)/phase; home.mkdir()
                run_upgrade(Path('java'), home, phase)
        self.assertEqual(PHASES, [p for p, options in launches])
        for phase, options in launches:
            self.assertEqual(240, options['timeout'])
            self.assertEqual([],[p for p in options['jvm_args'] if p.startswith('-Dmax.bg.threads=')])
            self.assertFalse(any(p.startswith(('-XX:ActiveProcessorCount','-XX:+Use')) for p in options['jvm_args']))
            self.assertIn('-Dlootablebodies.phase='+phase, options['jvm_args'])

    def test_unsafe_refusals_use_the_same_budget_and_keep_their_shorter_deadline(self):
        with patch.object(native_runtime, 'run_java', return_value=subprocess.CompletedProcess([], 1)) as launch:
            run_abort(Path('java'), Path('owned'))
        options=launch.call_args.kwargs
        self.assertEqual(180, options['timeout'])
        self.assertEqual([],[p for p in options['jvm_args'] if p.startswith('-Dmax.bg.threads=')])
        self.assertFalse(any(p.startswith(('-XX:ActiveProcessorCount','-XX:+Use')) for p in options['jvm_args']))
        self.assertNotIn('-Dlootablebodies.phase=abort', options['jvm_args'])

    def test_ordinary_packaged_game_keeps_the_default_native_processor_count(self):
        arguments=[]

        def launch(java, home, phase, **options):
            arguments.extend(options['jvm_args'])
            (home/'world').mkdir()
            (home/'world'/(phase+'.pass')).touch()
            (home/(phase+'.log')).write_text('ORDINARY_RUNTIME_COMPLETE\n')
            for name in ['latest.log','debug.log']:(home/(phase+'.'+name)).write_text('Synthetic complete native log fixture\n')
            return subprocess.CompletedProcess([], 0)

        with tempfile.TemporaryDirectory() as directory, patch.object(native_runtime, 'run_java', launch):
            native_runtime.run_server(Path('java'), Path(directory), 'ordinary')
        self.assertFalse(any(p.startswith(('-XX:ActiveProcessorCount','-Dmax.bg.threads','-XX:+Use')) for p in arguments))

    def test_missing_target_qualification_refuses_before_any_historical_process_starts(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            policy=Path(directory)/'policy.json'
            for change in ({'qualified':False},{'minecraft':'1.18.2','qualified':True}):
                policy.write_text(json.dumps(change))
                with patch.object(native_runtime,'WORKER_BUDGET_PATH',policy),patch.object(native_runtime,'run_monitored') as launch:
                    with self.assertRaisesRegex(RuntimeError,'has not been qualified'):
                        native_runtime.run_java(Path('not-a-jdk'),Path('owned'),'upgrade-near')
                    launch.assert_not_called()

    def test_forged_flag_or_stale_source_binding_cannot_authorize_history(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            policy=Path(directory)/'policy.json'
            base={'minecraft':'1.21.1','forge':'52.1.20','reloads':200,'jvms':4,'qualified':True,'jvm_args':[]}
            for fields in ({'jvm_args':['-Dmax.bg.threads=1']},{'controller_sha256':'0'*64}):
                policy.write_text(json.dumps(dict(base,**fields)))
                with patch.object(native_runtime,'WORKER_BUDGET_PATH',policy),patch.object(native_runtime,'run_monitored') as launch:
                    with self.assertRaises(RuntimeError):native_runtime.run_java(Path('not-a-jdk'),Path('owned'),'abort')
                    launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
