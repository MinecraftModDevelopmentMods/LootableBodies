import shutil
import tempfile
import unittest
from pathlib import Path
from verify_upgrade_abort import inject_unsafe_journal, allowed_platform_additions


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

    def test_only_the_stock_new_forge_config_is_exempt_from_world_data_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            stock=(Path(__file__).parent/'fixtures/forge28-server-default.toml').read_bytes();config.write_bytes(stock)
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'new'},world))
            config.write_bytes(stock+b'\nchanged=true\n')
            self.assertEqual([],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'changed'},world))
            config.write_bytes(stock)
            self.assertEqual([],allowed_platform_additions({'serverconfig/forge-server.toml':'old'}, {'serverconfig/forge-server.toml':'new'},world))

    def test_target_forge28_default_is_recognised(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            stock=(Path(__file__).parent/'fixtures/forge28-server-default.toml').read_bytes();config.write_bytes(stock)
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'new'},world))

    def test_only_proven_native_defaults_can_be_added_to_an_existing_config(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            fixtures=Path(__file__).parent/'fixtures'
            original=(fixtures/'forge25-server-default.toml').read_bytes()
            target=(fixtures/'forge28-server-default.toml').read_bytes();config.write_bytes(target)
            before={'serverconfig/forge-server.toml':hashlib.sha256(original).hexdigest().upper()}
            after={'serverconfig/forge-server.toml':'changed'}
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions(before,after,world,original))
            for changed in [target.replace(b'clumpingThreshold = 64',b'clumpingThreshold = 65'),
                            target+b'\nunknownOption = true\n']:
                config.write_bytes(changed)
                self.assertEqual([],allowed_platform_additions(before,after,world,original))

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
