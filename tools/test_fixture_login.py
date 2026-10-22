import json
import tempfile
import unittest
from pathlib import Path
from fixture_login import LoginMonitor, write_receipts, archive_live_logs
from audit_runtime_logs import audit

FAILURE = "[12:00:00] [Server thread/INFO] [minecraft/NetHandlerPlayServer]: ContainerA lost connection: Internal Exception: io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet\n"
JOIN = "[12:00:02] [Server thread/INFO] [minecraft/DedicatedServer]: ContainerA joined the game\n"

class FixtureLoginTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);(self.root/'sync').mkdir()
        self.log=self.root/'server.log';self.log.write_text('')
        self.monitor=LoginMonitor(self.root)
    def test_live_log_receipts_survive_the_cold_reload(self):
        self.log.write_text(FAILURE+JOIN);self.monitor.poll()
        (self.root/'sync/login-retried-A').write_text('1')
        latest=self.root/'server/logs/latest.log';latest.parent.mkdir(parents=True)
        latest.write_text(FAILURE+JOIN);write_receipts(self.root)
        archive_live_logs(self.root)
        latest.write_text('[12:01:00] [Server thread/INFO] [FML]: Cold reload passed\n')
        audit(latest,fixture_logins=True)
        audit(self.root/'server-live-latest.log',fixture_logins=True)
        self.assertEqual((self.root/'server-live-latest.log').read_text(),FAILURE+JOIN)

    def test_only_exact_stock_failure_grants_retry(self):
        self.log.write_text(FAILURE);self.monitor.poll()
        self.assertEqual((self.root/'sync/login-retry-A').read_text(),'1')
    def test_unrelated_login_error_never_grants_retry(self):
        self.log.write_text(FAILURE.replace("Can't serialize unregistered packet",'Other failure'))
        with self.assertRaises(RuntimeError):self.monitor.poll()
        self.assertFalse((self.root/'sync/login-retry-A').exists())
    def test_retries_are_bounded(self):
        self.log.write_text(FAILURE*4)
        with self.assertRaises(RuntimeError):self.monitor.poll()
    def test_receipt_requires_recovered_connection_and_consumed_permit(self):
        self.log.write_text(FAILURE);self.monitor.poll()
        with self.assertRaises(RuntimeError):write_receipts(self.root)
        self.log.write_text(FAILURE+JOIN)
        with self.assertRaises(RuntimeError):write_receipts(self.root)
        (self.root/'sync/login-retried-A').write_text('1')
        write_receipts(self.root)
        audit(self.log,fixture_logins=True)
        with self.assertRaises(RuntimeError):audit(self.log)
    def test_receipt_cannot_accept_changed_logs_or_other_errors(self):
        self.log.write_text(FAILURE+JOIN);self.monitor.poll()
        (self.root/'sync/login-retried-A').write_text('1');write_receipts(self.root)
        self.log.write_text(FAILURE+JOIN+'[12:00:03] [Server thread/ERROR] [FML]: Bad inventory\n')
        with self.assertRaises(RuntimeError):audit(self.log,fixture_logins=True)
    def test_retry_after_a_play_session_is_not_allowed(self):
        self.log.write_text(JOIN+FAILURE);self.monitor.poll()
        (self.root/'sync/login-retried-A').write_text('1')
        with self.assertRaises(RuntimeError):write_receipts(self.root)
    def test_expected_disconnect_then_reconnect_can_be_retried(self):
        left='[12:00:01] [Server thread/INFO] [minecraft/DedicatedServer]: ContainerA left the game\n'
        self.log.write_text(JOIN+left+FAILURE+JOIN);self.monitor.poll()
        (self.root/'sync/login-retried-A').write_text('1');write_receipts(self.root)
        audit(self.log,fixture_logins=True)

if __name__=='__main__':unittest.main()
