import unittest
import tempfile
import tomllib
from pathlib import Path
from native_runtime import validate_client_paths, prepare_client, seed_loader_defaults

class NativeClasspathTests(unittest.TestCase):
    def test_native_forge_dependencies_remain_available(self):
        validate_client_paths([Path(p) for p in ['asm-6.2.jar','asm-commons-6.2.jar','asm-tree-6.2.jar','modlauncher-2.1.5.jar','lwjgl-3.1.6.jar','log4j-api-2.15.0.jar']])
    def test_incompatible_legacy_bootstraps_are_rejected(self):
        for name in ['asm-debug-all-5.2.jar','launchwrapper-1.12.jar','lwjgl-2.9.4.jar','legacydev-0.2.3.1-fatjar.jar','mergetool-1.0.13-forge.jar']:
            with self.subTest(name=name),self.assertRaises(RuntimeError):validate_client_paths([Path(name)])
    def test_testing_and_gradle_libraries_are_rejected(self):
        for name in ['junit-jupiter-5.jar','mockito-core.jar','opentest4j.jar','slime-launcher-0.2.2.jar']:
            with self.subTest(name=name),self.assertRaises(RuntimeError):validate_client_paths([Path(name)])
    def test_a_mapped_development_jar_cannot_replace_the_official_runtime(self):
        with self.assertRaises(RuntimeError):validate_client_paths([Path('.gradle/mavenizer/repo/net/minecraftforge/forge.jar')])

class OwnedLoaderDefaultsTests(unittest.TestCase):
    def test_existing_loader_config_bytes_are_never_rewritten(self):
        for original in [b'versionCheck = false\nmaxThreads = 2\n# personal choice\n', b'\xff\x00damaged config']:
            with self.subTest(original=original), tempfile.TemporaryDirectory() as directory:
                home=Path(directory); config=home/'config/fml.toml'
                config.parent.mkdir(); config.write_bytes(original)
                seed_loader_defaults(home)
                self.assertEqual(original, config.read_bytes())

    def test_repeated_setup_keeps_stock_bytes_and_unrelated_mod_choices(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory); seed_loader_defaults(home)
            config=home/'config/fml.toml'; original=config.read_bytes()
            mod=home/'config/lootablebodies.cfg'; mod.write_bytes(b'options {\n B:local_texture=false\n}\n')
            seed_loader_defaults(home)
            self.assertEqual(original, config.read_bytes())
            self.assertEqual(b'options {\n B:local_texture=false\n}\n', mod.read_bytes())

    def test_new_owned_client_starts_with_all_four_native_loader_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)/'client'
            prepare_client(home)
            config=home/'config/fml.toml'
            self.assertTrue(config.is_file(), 'Prepare stock loader config before parallel loading starts')
            self.assertEqual({'versionCheck':True, 'splashscreen':True,
                              'maxThreads':-1, 'defaultConfigPath':'defaultconfigs'},
                             tomllib.loads(config.read_text(encoding='utf-8')))

if __name__=='__main__':unittest.main()
