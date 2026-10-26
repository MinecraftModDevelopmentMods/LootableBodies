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

    def test_login_encoding_failure_at_info_level_is_rejected(self):
        text = ("[12:00:00] [Server thread/INFO] [minecraft/NetHandlerPlayServer]: "
                "ContainerA lost connection: Internal Exception: io.netty.handler.codec.EncoderException: "
                "java.io.IOException: Can't serialize unregistered packet\n")
        for options in ({}, {'development': True}, {'upgrade': True}):
            with self.subTest(options=options), self.assertRaises(RuntimeError):
                self.check_log(text, **options)

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

    def test_native_forge25_control_diagnostics_require_native_mode(self):
        lines=[
            '[23Oct2026 05:33:14.287] [Thread-4/FATAL] [net.minecraftforge.fml.packs.ModFileResourcePack/]: Failed to clean up tempdir C:\\Users\\fixture\\AppData\\Local\\Temp\\modpacktmp5761462687549030506',
            '[05:33:14.287] [Client thread/FATAL] [net.minecraftforge.common.ForgeConfig]: Forge config just got changed on the file system!']
        lines += [line.replace('05:33:14.287','05:33:14') for line in lines]
        lines += [lines[1].replace('net.minecraftforge.common.ForgeConfig]', 'ne.mi.co.ForgeConfig/CORE]')]
        for line in lines:
            with self.subTest(line=line):
                self.check_log(line+'\n',native=True)
                with self.assertRaises(RuntimeError):self.check_log(line+'\n')
                for changed in [line+' additional failure',line.replace('/FATAL]','/ERROR]'),line.replace('ModFileResourcePack','OtherPack').replace('ForgeConfig','OtherConfig')]:
                    with self.assertRaises(RuntimeError):self.check_log(changed+'\n',native=True)
        with self.assertRaises(RuntimeError):
            self.check_log('[05:33:14.287] [Server thread/ERROR] [net.minecraft.entity.EntityTracker]: Entity is already tracked!\n',native=True)

    def test_native_openal_shutdown_diagnostic_is_exact(self):
        known='AL lib: (EE) alc_cleanup: 1 device not closed\n'
        self.check_log(known,native=True)
        for text in (known,known.replace('1 device','2 devices'),known.replace('alc_cleanup','other_failure')):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.check_log(text,native=text!=known)

    def test_native_terminal_colours_do_not_change_log_classification(self):
        known='[15:16:49] [Thread-1/FATAL] [ne.mi.co.ForgeConfig/CORE]: Forge config just got changed on the file system!'
        self.check_log('\x1b[m\x1b[1;31m'+known+'\x1b[m\n',native=True)
        for text in [known+' extra failure',known.replace('ForgeConfig','OtherConfig'),
                     '[15:16:49] [Server thread/ERROR] Unexpected error']:
            with self.subTest(text=text),self.assertRaises(RuntimeError):
                self.check_log('\x1b[1;31m'+text+'\x1b[m\n',native=True)

    def test_native_forge28_empty_handshake_control_is_narrow(self):
        known='[15:49:16] [Client thread/ERROR] [net.minecraftforge.fml.network.simple.IndexedMessageCodec]: Received empty payload on channel fml:handshake'
        self.check_log(known+'\n',native=True)
        for text in [known, known+' extra failure', known.replace('fml:handshake','other:channel'),
                     known.replace('IndexedMessageCodec','OtherCodec')]:
            with self.subTest(text=text),self.assertRaises(RuntimeError):
                self.check_log(text+'\n',native=text!=known)

    def test_native_forge28_tcp_command_tree_diagnostics_are_exact(self):
        lines=[
            '[16:19:28] [Netty Server IO #1/ERROR] [minecraft/ArgumentTypes]: Could not serialize net.minecraftforge.server.command.ModIdArgument@62e9cbe6 (class net.minecraftforge.server.command.ModIdArgument) - will not be sent to client!',
            '[16:19:28.111] [Netty Server IO #2/ERROR] [net.minecraft.command.arguments.ArgumentTypes/]: Could not serialize net.minecraftforge.server.command.EnumArgument@d8352c6 (class net.minecraftforge.server.command.EnumArgument) - will not be sent to client!',
            '[16:19:28] [Netty Client IO #1/ERROR] [net.minecraft.command.arguments.ArgumentTypes]: Could not deserialize minecraft:',
            '[16:19:30] [Server thread/ERROR] [ne.mi.fm.ne.si.IndexedMessageCodec/SIMPLENET]: Received empty payload on channel fml:handshake']
        for line in lines:
            with self.subTest(line=line):
                self.check_log(line+'\n',native=True)
                with self.assertRaises(RuntimeError):self.check_log(line+'\n')
                for changed in [line+' extra failure',line.replace('ModIdArgument','OtherArgument').replace('EnumArgument','OtherArgument').replace('deserialize minecraft:','deserialize other:').replace('fml:handshake','other:channel')]:
                    with self.assertRaises(RuntimeError):self.check_log(changed+'\n',native=True)

    def forge14_control(self):
        return (Path(__file__).parent / 'fixtures/forge14-bootstrap-diagnostics.txt').read_text(encoding='utf-8')

    def test_control_proven_forge14_development_blocks(self):
        self.check_log(self.forge14_control(), development=True)

    def test_development_control_diagnostics_require_development_mode(self):
        with self.assertRaises(RuntimeError): self.check_log(self.forge14_control())

    def test_control_does_not_hide_new_errors_or_unrelated_stacks(self):
        control=self.forge14_control()
        for extra in ['[12:00:01] [Server thread/ERROR] [FML]: Unexpected error\n',
                      'Caused by: java.lang.IllegalArgumentException\n',
                      'Caused by: java.lang.NullPointerException\n']:
            with self.subTest(extra=extra), self.assertRaises(RuntimeError):
                self.check_log(control+'[12:00:01] [Server thread/INFO] [FML]: Saving\n'+extra, development=True)

    def test_unknown_scanned_jar_and_changed_control_cause_are_rejected(self):
        for text in [self.forge14_control().replace('slime-launcher-0.2.2.jar','other-mod.jar'),
                     self.forge14_control().replace('Caused by: java.lang.IllegalArgumentException','Caused by: java.lang.IllegalStateException')]:
            with self.subTest(text=text), self.assertRaises(RuntimeError): self.check_log(text,development=True)


if __name__ == '__main__':
    unittest.main()
