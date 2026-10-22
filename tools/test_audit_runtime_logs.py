import tempfile
import unittest
from pathlib import Path

from audit_runtime_logs import audit, FORGE_UPGRADE_RENAME


class RuntimeLogAuditTests(unittest.TestCase):
    def check_log(self, text, **options):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'runtime.log'
            path.write_text(text, encoding='utf-8')
            return audit(path, **options)

    def rename(self, debug=False):
        message = FORGE_UPGRADE_RENAME.replace(']:', '/]:') if debug else FORGE_UPGRADE_RENAME
        return '[12:00:00] [Server thread/ERROR] ' + message + '\n'

    def redirect(self, old=False):
        logger, number = ('Forge', 216) if old else ('forge', 227)
        return ('[12:00:00] [Forge Version Check/DEBUG] [ForgeVersionCheck/' + logger +
                ']: Failed to process update information\n'
                'Moved Permanently\n'
                'Caused by: java.lang.IllegalStateException: Expected BEGIN_OBJECT but was STRING at line 1 column 1\n'
                '\tat net.minecraftforge.common.ForgeVersion$1.process(ForgeVersion.java:' + str(number) + ')\n')

    def test_upgrade_warning_in_both_control_proven_logger_formats(self):
        for debug in [False, True]:
            with self.subTest(debug=debug):
                self.check_log(self.rename(debug), upgrade=True)

    def test_upgrade_warning_requires_upgrade_mode(self):
        for debug in [False, True]:
            with self.subTest(debug=debug), self.assertRaises(RuntimeError):
                self.check_log(self.rename(debug))

    def test_other_missing_mod_or_extra_error_is_not_allowed(self):
        for text in [self.rename(True).replace('mod Forge', 'mod Other'),
                     self.rename(True).rstrip() + ' unexpected failure\n',
                     self.rename(True) + '[12:00:01] [Server thread/ERROR] Unexpected failure\n']:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.check_log(text, upgrade=True)

    def test_both_control_proven_updater_stacks(self):
        self.check_log(self.redirect())
        self.check_log(self.redirect(old=True), upgrade=True)

    def test_old_updater_stack_requires_upgrade_mode(self):
        with self.assertRaises(RuntimeError):
            self.check_log(self.redirect(old=True))

    def test_redirect_requires_exact_body_and_frame(self):
        for text in [self.redirect(True).replace('Moved Permanently', 'different response'),
                     self.redirect(True).replace('ForgeVersion.java:216', 'ForgeVersion.java:215')]:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.check_log(text, upgrade=True)

    def test_unrelated_exception_after_updater_still_fails(self):
        text = self.redirect(True) + ('[12:00:01] [Server thread/INFO] Saving\n'
                                     'Caused by: java.lang.IllegalStateException: unexpected\n')
        with self.assertRaises(RuntimeError):
            self.check_log(text, upgrade=True)


if __name__ == '__main__':
    unittest.main()
