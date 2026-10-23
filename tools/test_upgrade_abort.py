import shutil
import tempfile
import unittest
from pathlib import Path
from verify_upgrade_abort import inject_unsafe_journal


class UpgradeAbortFixtureTests(unittest.TestCase):
    def check_fixture(self, case, existing):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            source = home / 'source'
            journal = source / 'data/lootablebodies-upgrade'
            journal.parent.mkdir(parents=True)
            if existing:
                journal.mkdir()
                (journal / 'index.nbt').write_bytes(b'original journal index')
                (journal / 'original-corpse.nbt').write_bytes(b'original corpse payload')
            run = home / 'run'
            world = run / 'world'
            shutil.copytree(source, world)
            inject_unsafe_journal(world, case)
            changed = world / 'data/lootablebodies-upgrade'
            if case == 'damaged-index':
                self.assertEqual(b'interrupted invalid index', (changed / 'index.nbt').read_bytes())
            else:
                self.assertEqual(b'cannot create a journal directory here', changed.read_bytes())
            if existing:
                saved = run / 'original-journal'
                self.assertEqual(b'original journal index', (saved / 'index.nbt').read_bytes())
                self.assertEqual(b'original corpse payload', (saved / 'original-corpse.nbt').read_bytes())
                self.assertEqual(b'original journal index', (journal / 'index.nbt').read_bytes())
                self.assertEqual(b'original corpse payload', (journal / 'original-corpse.nbt').read_bytes())
            else:
                self.assertFalse((run / 'original-journal').exists())

    def test_existing_journal_damaged_index(self):
        self.check_fixture('damaged-index', True)

    def test_existing_journal_blocked_directory(self):
        self.check_fixture('blocked-journal', True)

    def test_new_journal_damaged_index(self):
        self.check_fixture('damaged-index', False)

    def test_new_journal_blocked_directory(self):
        self.check_fixture('blocked-journal', False)

    def test_unknown_case_preserves_existing_journal(self):
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory) / 'world'
            journal = world / 'data/lootablebodies-upgrade'
            journal.mkdir(parents=True)
            (journal / 'index.nbt').write_bytes(b'original')
            with self.assertRaises(ValueError):
                inject_unsafe_journal(world, 'unknown')
            self.assertEqual(b'original', (journal / 'index.nbt').read_bytes())


if __name__ == '__main__':
    unittest.main()
