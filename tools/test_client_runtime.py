import tempfile
import unittest
from pathlib import Path
from client_runtime import packaged_library, library_destination

class ClientRuntimeTests(unittest.TestCase):
    def test_development_and_test_libraries_are_excluded(self):
        for name in ['legacydev-0.2.3.1-fatjar.jar','mergetool-1.0.13-forge.jar','client-extra-1.12.2.jar','asm-6.2.jar','asm-util-6.2.jar','asm-analysis-6.2.jar','asm-tree-6.2.jar','slf4j-api-1.8.0-beta4.jar','slime-launcher-0.2.2.jar','junit-jupiter-5.jar']:
            with self.subTest(name=name): self.assertFalse(packaged_library(Path(name)))
    def test_official_forge_libraries_are_retained(self):
        for name in ['asm-debug-all-5.2.jar','log4j-api-2.15.0.jar','maven-artifact-3.5.3.jar','launchwrapper-1.12.jar']:
            with self.subTest(name=name): self.assertTrue(packaged_library(Path(name)))
    def test_libraries_use_the_standard_launcher_layout(self):
        source=Path('/cache/caches/modules-2/files-2.1/org.apache.maven/maven-artifact/3.5.3/hash/maven-artifact-3.5.3.jar')
        self.assertEqual(library_destination(source,Path('/game')),Path('/game/libraries/org/apache/maven/maven-artifact/3.5.3/maven-artifact-3.5.3.jar'))
    def test_unknown_cache_shapes_are_rejected(self):
        with self.assertRaises(ValueError): library_destination(Path('/some/unknown.jar'),Path('/game'))

if __name__=='__main__': unittest.main()
