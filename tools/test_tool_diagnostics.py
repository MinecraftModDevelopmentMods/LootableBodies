import contextlib
import io
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import native_runtime
import prepare_legacy_tools
import prepare_native113_tools
import prepare_native114_tools
import prepare_migration_inputs


class ToolDiagnosticsTests(unittest.TestCase):
    payload = 'original beginning\n' + 'build progress\n' * 100 + 'fixture failure detail\n'

    def test_rejected_download_tries_next_address_before_writing(self):
        valid=b'checksum-locked fixture'
        expected=hashlib.sha256(valid).hexdigest().upper()
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'fixture.jar'
            with patch('urllib.request.urlopen',side_effect=[io.BytesIO(b'incomplete'),io.BytesIO(valid)]) as fetch, contextlib.redirect_stdout(io.StringIO()):
                prepare_migration_inputs.fetch('https://example.invalid/fixture.jar',target,expected)
            self.assertEqual(2,fetch.call_count)
            self.assertEqual(valid,target.read_bytes())

    def test_every_rejected_download_is_checked_and_existing_file_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'fixture.jar';target.write_bytes(b'previous verified fixture')
            with patch('urllib.request.urlopen',side_effect=[io.BytesIO(b'bad') for _ in range(3)]) as fetch, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError,'checksum mismatch'):
                    prepare_migration_inputs.fetch('https://example.invalid/fixture.jar',target,'0'*64)
            self.assertEqual(3,fetch.call_count)
            self.assertEqual(b'previous verified fixture',target.read_bytes())

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

    def test_native114_writer_failure_keeps_full_log_and_explains_it_in_ci(self):
        self.check_writer(prepare_native114_tools)

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
