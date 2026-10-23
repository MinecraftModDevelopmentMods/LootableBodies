import unittest
from pathlib import Path
from native_runtime import validate_client_paths

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

if __name__=='__main__':unittest.main()
