from pathlib import Path
import subprocess
import tempfile
import unittest
import json
from unittest.mock import patch
import verify_packaged_client as client


class GraphicalSplitTests(unittest.TestCase):
    def test_both_bounded_processes_share_the_saved_world_and_preserve_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);world=root/'saves/build-fixture';world.mkdir(parents=True)
            (world/'level.dat').write_bytes(b'actual saved world')
            images=root/'screenshots';images.mkdir();(images/'first.png').write_bytes(b'first image')
            def execute(command,home,label,timeout):
                self.assertEqual(root,home);self.assertEqual(180,timeout)
                if label=='client':
                    self.assertIn('-Dlootablebodies.clientStopBeforeDrops=true',command)
                    (root/'client-connected.pass').write_text('connected complete')
                    (root/'client.log').write_text('LOOTABLE_BODIES_CONNECTED_STAGE_PASS\n')
                else:
                    self.assertEqual('client-recovery',label)
                    self.assertIn('-Dlootablebodies.clientResumeDrops=true',command)
                    self.assertNotIn('-Dlootablebodies.clientStopBeforeDrops=true',command)
                    self.assertEqual(b'actual saved world',(world/'level.dat').read_bytes())
                    self.assertEqual(b'first image',(root/'connected-screenshots/first.png').read_bytes())
                    self.assertTrue((root/'client-handoff.json').is_file())
                return subprocess.CompletedProcess(command,0)
            with patch.object(client,'run_client',side_effect=execute) as run:
                client.run_default_phases(['java','main'],root)
            self.assertEqual(2,run.call_count)

    def test_missing_first_phase_proof_prevents_continuation(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(client,'run_client',return_value=subprocess.CompletedProcess(['java'],0)) as run:
                with self.assertRaises(RuntimeError):client.run_default_phases(['java','main'],Path(directory))
            self.assertEqual(1,run.call_count)

    def test_failed_first_phase_is_preserved_without_retry_or_continuation(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(client,'run_client',return_value=subprocess.CompletedProcess(['java'],1)) as run:
                with self.assertRaises(RuntimeError):client.run_default_phases(['java','main'],Path(directory))
            self.assertEqual(1,run.call_count)

    def test_continuation_timeout_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'saves/build-fixture').mkdir(parents=True)
            (root/'saves/build-fixture/level.dat').write_bytes(b'world')
            (root/'screenshots').mkdir();(root/'screenshots/first.png').write_bytes(b'image')
            (root/'client-connected.pass').write_text('complete')
            (root/'client.log').write_text('LOOTABLE_BODIES_CONNECTED_STAGE_PASS\n')
            with patch.object(client,'run_client',side_effect=[subprocess.CompletedProcess(['java'],0),subprocess.TimeoutExpired(['java'],180)]) as run:
                with self.assertRaises(subprocess.TimeoutExpired):client.run_default_phases(['java','main'],root)
            self.assertEqual(2,run.call_count)

    def test_failed_continuation_cannot_destroy_actual_first_phase_world(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);world=root/'saves/build-fixture';world.mkdir(parents=True)
            (world/'level.dat').write_bytes(b'actual first header')
            (world/'entities').mkdir();(world/'entities/r.0.0.mca').write_bytes(b'actual first region')
            (root/'screenshots').mkdir();(root/'screenshots/first.png').write_bytes(b'image')
            (root/'client-connected.pass').write_text('complete')
            (root/'client.log').write_text('LOOTABLE_BODIES_CONNECTED_STAGE_PASS\n')
            def run(command,home,label,timeout):
                if label=='client-recovery':
                    (world/'level.dat').write_bytes(b'failed continuation saved new bytes')
                    (world/'entities/r.0.0.mca').unlink()
                    return subprocess.CompletedProcess(command,1)
                return subprocess.CompletedProcess(command,0)
            with patch.object(client,'run_client',side_effect=run) as launch:
                result=client.run_default_phases(['java','main'],root)
            self.assertEqual(1,result.returncode);self.assertEqual(2,launch.call_count)
            frozen=root/'connected-world'
            self.assertEqual(b'actual first header',(frozen/'level.dat').read_bytes())
            self.assertEqual(b'actual first region',(frozen/'entities/r.0.0.mca').read_bytes())
            receipt=json.loads((root/'client-handoff.json').read_text())
            self.assertEqual(receipt['saved_world'],client.fingerprint(frozen))
            self.assertEqual('connected-world',receipt['frozen_world'])

    def test_missing_or_damaged_frozen_world_prevents_continuation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);world=root/'saves/build-fixture';world.mkdir(parents=True)
            (world/'level.dat').write_bytes(b'actual header')
            (root/'screenshots').mkdir();(root/'screenshots/first.png').write_bytes(b'image')
            (root/'client-connected.pass').write_text('complete')
            (root/'client.log').write_text('LOOTABLE_BODIES_CONNECTED_STAGE_PASS\n')
            real_copy=client.shutil.copytree
            def copy(source,target,*args,**kwargs):
                result=real_copy(source,target,*args,**kwargs)
                if Path(target).name=='connected-world':(Path(target)/'level.dat').write_bytes(b'changed')
                return result
            with patch.object(client,'run_client',return_value=subprocess.CompletedProcess(['java'],0)) as launch,\
                    patch.object(client.shutil,'copytree',side_effect=copy):
                with self.assertRaisesRegex(RuntimeError,'frozen'):
                    client.run_default_phases(['java','main'],root)
            self.assertEqual(1,launch.call_count)
