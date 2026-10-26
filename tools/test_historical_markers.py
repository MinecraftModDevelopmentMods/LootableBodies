import tempfile
import unittest
from pathlib import Path
from verify_world_upgrades import preserve_historical_markers


class HistoricalMarkerTests(unittest.TestCase):
    def test_earlier_runner_markers_are_preserved_outside_the_disposable_world(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);world=home/'world';world.mkdir()
            (world/'upgrade-near.pass').write_bytes(b'previous release passed\n')
            (world/'level.dat').write_bytes(b'immutable world data')
            (world/'manual.pass').write_bytes(b'unrelated file')
            preserve_historical_markers(home)
            self.assertFalse((world/'upgrade-near.pass').exists())
            self.assertEqual(b'previous release passed\n',(home/'historical-run-markers/upgrade-near.pass').read_bytes())
            self.assertEqual(b'immutable world data',(world/'level.dat').read_bytes())
            self.assertEqual(b'unrelated file',(world/'manual.pass').read_bytes())

    def test_existing_evidence_cannot_be_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory);world=home/'world';world.mkdir()
            (world/'upgrade-near.pass').write_bytes(b'new')
            prior=home/'historical-run-markers';prior.mkdir();(prior/'upgrade-near.pass').write_bytes(b'old')
            with self.assertRaises(RuntimeError):preserve_historical_markers(home)
            self.assertEqual(b'new',(world/'upgrade-near.pass').read_bytes())
            self.assertEqual(b'old',(prior/'upgrade-near.pass').read_bytes())


if __name__=='__main__':unittest.main()
