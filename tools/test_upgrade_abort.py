import shutil
import tempfile
import unittest
from pathlib import Path
from verify_upgrade_abort import inject_unsafe_journal, allowed_platform_additions


class UpgradeAbortFixtureTests(unittest.TestCase):
    def test_native36_stock_config_is_recognised(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            config.write_bytes((Path(__file__).parent/'fixtures/forge36-server-default.toml').read_bytes())
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'new'},world))

    def test_native36_only_removes_retired_clumping_and_keeps_other_choices(self):
        import hashlib,tomllib
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            original=(Path(__file__).parent/'fixtures/forge28-server-default.toml').read_bytes().replace(b'clumpingThreshold = 64',b'clumpingThreshold = 128').replace(b'zombieBabyChance = 0.05',b'zombieBabyChance = 0.09')
            values=tomllib.loads(original.decode())['server'];values.pop('clumpingThreshold')
            target='[server]\n'+'\n'.join(key+' = '+(str(value).lower() if isinstance(value,bool) else str(value)) for key,value in values.items())+'\n'
            config.write_text(target)
            before={'serverconfig/forge-server.toml':hashlib.sha256(original).hexdigest().upper()};after={'serverconfig/forge-server.toml':'changed'}
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions(before,after,world,original))
            config.write_text(target.replace('zombieBabyChance = 0.09','zombieBabyChance = 0.05'))
            self.assertEqual([],allowed_platform_additions(before,after,world,original))

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
            stock=(Path(__file__).parent/'fixtures/forge36-server-default.toml').read_bytes();config.write_bytes(stock)
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'new'},world))
            config.write_bytes(stock+b'\nchanged=true\n')
            self.assertEqual([],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'changed'},world))
            config.write_bytes(stock)
            self.assertEqual([],allowed_platform_additions({'serverconfig/forge-server.toml':'old'}, {'serverconfig/forge-server.toml':'new'},world))

    def test_target_forge36_default_is_recognised(self):
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            stock=(Path(__file__).parent/'fixtures/forge36-server-default.toml').read_bytes();config.write_bytes(stock)
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions({}, {'serverconfig/forge-server.toml':'new'},world))

    def test_new_stock_config_accepts_platform_order_and_format_only(self):
        import tomllib
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory)
            config = world / 'serverconfig/forge-server.toml'
            config.parent.mkdir()
            stock = tomllib.loads((Path(__file__).parent / 'fixtures/forge36-server-default.toml').read_text())['server']
            lines = ['# Linux may write these entries in a different order', '[server]']
            for name, value in reversed(list(stock.items())):
                lines.append(name + ' = ' + (str(value).lower() if isinstance(value, bool) else str(value)))
            config.write_text('\n'.join(lines) + '\n')
            self.assertEqual(['serverconfig/forge-server.toml'], allowed_platform_additions(
                {}, {'serverconfig/forge-server.toml': 'new'}, world))

    def test_config_values_and_types_must_match_exactly(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory)
            config = world / 'serverconfig/forge-server.toml'
            config.parent.mkdir()
            stock = (Path(__file__).parent / 'fixtures/forge36-server-default.toml').read_bytes()
            before = {'serverconfig/forge-server.toml': hashlib.sha256(stock).hexdigest().upper()}
            after = {'serverconfig/forge-server.toml': 'changed'}
            for old, new in [(b'zombieBabyChance = 0.05', b'zombieBabyChance = 0.06'),
                             (b'dimensionUnloadQueueDelay = 0', b'dimensionUnloadQueueDelay = 0.0'),
                             (b'fixAdvancementLoading = true', b'fixAdvancementLoading = 1'),
                             (b'dimensionUnloadQueueDelay = 0', b'dimensionUnloadQueueDelay = false')]:
                with self.subTest(replacement=new):
                    config.write_bytes(stock.replace(old, new))
                    self.assertEqual([], allowed_platform_additions({}, after, world))
                    self.assertEqual([], allowed_platform_additions(before, after, world, stock))

    def test_only_proven_native_defaults_can_be_added_to_an_existing_config(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            world=Path(directory);config=world/'serverconfig/forge-server.toml';config.parent.mkdir()
            fixtures=Path(__file__).parent/'fixtures'
            original=(fixtures/'forge25-server-default.toml').read_bytes()
            target=(fixtures/'forge36-server-default.toml').read_bytes();config.write_bytes(target)
            before={'serverconfig/forge-server.toml':hashlib.sha256(original).hexdigest().upper()}
            after={'serverconfig/forge-server.toml':'changed'}
            self.assertEqual(['serverconfig/forge-server.toml'],allowed_platform_additions(before,after,world,original))
            for changed in [target.replace(b'zombieBabyChance = 0.05',b'zombieBabyChance = 0.06'),
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
