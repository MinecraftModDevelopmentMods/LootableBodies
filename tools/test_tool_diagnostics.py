import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import native_runtime
import prepare_legacy_tools
import prepare_native113_tools


class ToolDiagnosticsTests(unittest.TestCase):
    payload = 'original beginning\n' + 'build progress\n' * 100 + 'fixture failure detail\n'

    def failed_command(self, command, **options):
        options['stdout'].write(self.payload)
        options['stdout'].flush()
        raise subprocess.CalledProcessError(7, command)

    def check_writer(self, module):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            (source / ('gradlew.bat' if sys.platform == 'win32' else 'gradlew')).write_text('fixture')
            arguments = ['writer', '--output', str(root), '--java17-home', str(root),
                         '--java8-home', str(root), '--java25-home', str(root)]
            stderr = io.StringIO()
            with patch.object(sys, 'argv', arguments), patch.object(module, 'prepare', return_value=source), \
                    patch('subprocess.run', side_effect=self.failed_command), contextlib.redirect_stderr(stderr):
                with self.assertRaises(subprocess.CalledProcessError) as failure:
                    module.main()
            self.assertEqual(7, failure.exception.returncode)
            self.assertEqual(self.payload, (root / 'build.log').read_text())
            self.assertIn('fixture failure detail', stderr.getvalue())
            self.assertIn(str(root / 'build.log'), stderr.getvalue())

    def test_legacy_writer_failure_keeps_full_log_and_explains_it_in_ci(self):
        self.check_writer(prepare_legacy_tools)

    def test_native113_writer_failure_keeps_full_log_and_explains_it_in_ci(self):
        self.check_writer(prepare_native113_tools)

    def test_installer_failure_keeps_full_log_and_explains_it_in_ci(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / 'native'
            stderr = io.StringIO()
            with patch('prepare_migration_inputs.fetch'), patch('subprocess.run', side_effect=self.failed_command), \
                    contextlib.redirect_stderr(stderr):
                with self.assertRaises(subprocess.CalledProcessError) as failure:
                    native_runtime.install(Path(sys.executable), home)
            self.assertEqual(7, failure.exception.returncode)
            self.assertEqual(self.payload, (home / 'install.log').read_text())
            self.assertIn('fixture failure detail', stderr.getvalue())
            self.assertIn(str(home / 'install.log'), stderr.getvalue())


if __name__ == '__main__':
    unittest.main()
