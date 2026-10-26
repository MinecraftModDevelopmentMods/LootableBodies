import json
import tempfile
import unittest
from pathlib import Path
from fixture_login import LoginMonitor, write_receipts, archive_live_logs, start_clients
from audit_runtime_logs import audit

FAILURE = "[12:00:00] [Server thread/INFO] [minecraft/NetHandlerPlayServer]: ContainerA lost connection: Internal Exception: io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet\n"
JOIN = "[12:00:02] [Server thread/INFO] [minecraft/DedicatedServer]: ContainerA joined the game\n"

class FixtureLoginTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);(self.root/'sync').mkdir()
        self.log=self.root/'server.log';self.log.write_text('')
        self.monitor=LoginMonitor(self.root)
    def test_planned_owner_and_observer_disconnects_do_not_abort_reconnect(self):
        for phase,role in [('8','A'),('10','B')]:
            with self.subTest(phase=phase,role=role):
                reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer').replace('ContainerA','Container'+role)
                (self.root/('client'+role+'.log')).write_text('VISIBILITY_CLIENT_PHASE '+role+' '+phase+'\n')
                self.log.write_text('VISIBILITY_PHASE '+phase+'\n'+reset+'VISIBILITY_PHASE '+str(int(phase)+1)+'\n')
                LoginMonitor(self.root).poll()
                self.assertFalse((self.root/('sync/login-retry-'+role)).exists())
    def test_planned_disconnect_rejects_wrong_player_or_missing_client_phase(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        self.log.write_text('VISIBILITY_PHASE 8\n'+reset)
        with self.assertRaises(RuntimeError):LoginMonitor(self.root).poll()
        (self.root/'clientA.log').write_text('VISIBILITY_CLIENT_PHASE A 8\n')
        self.log.write_text('VISIBILITY_PHASE 10\n'+reset)
        with self.assertRaises(RuntimeError):LoginMonitor(self.root).poll()
    def test_old_disconnect_phase_cannot_explain_a_later_active_reset(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        (self.root/'clientA.log').write_text('VISIBILITY_CLIENT_PHASE A 8\n')
        self.log.write_text('VISIBILITY_PHASE 8\nVISIBILITY_PHASE 12\n'+reset)
        with self.assertRaises(RuntimeError):LoginMonitor(self.root).poll()

    def test_completed_respawn_client_may_close_its_transport(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        self.log.write_text(reset)
        client=self.root/'clientA';client.mkdir();(client/'respawn-client.pass').touch()
        (self.root/'clientA.log').write_text('LOOTABLE_BODIES_RESPAWN_CLIENT_PASS A\n')
        (self.root/'sync/state').write_text('16 9 none 0')
        self.monitor.poll()
        self.assertFalse((self.root/'sync/login-retry-A').exists())
    def test_completed_visibility_client_may_close_its_transport(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        self.log.write_text(reset);client=self.root/'clientA';client.mkdir()
        (client/'visibility-client.pass').touch()
        (self.root/'clientA.log').write_text('LOOTABLE_BODIES_VISIBILITY_CLIENT_PASS role=A\n')
        (self.root/'sync/phase').write_text('18');self.monitor.poll()
    def test_completed_shared_inventory_client_may_close_its_transport(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        self.log.write_text(reset);client=self.root/'clientA';client.mkdir()
        (client/'multiplayer-client.pass').touch()
        (self.root/'clientA.log').write_text('LOOTABLE_BODIES_MULTIPLAYER_CLIENT_PASS role=A\n')
        (self.root/'sync/phase').write_text('8');self.monitor.poll()

    def test_reset_before_client_pass_or_in_active_phase_still_fails(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        self.log.write_text(reset)
        with self.assertRaises(RuntimeError):self.monitor.poll()
        client=self.root/'clientA';client.mkdir();(client/'respawn-client.pass').touch()
        (self.root/'clientA.log').write_text('LOOTABLE_BODIES_RESPAWN_CLIENT_PASS A\n')
        (self.root/'sync/state').write_text('15 3 none 0')
        with self.assertRaises(RuntimeError):self.monitor.poll()
    def test_client_marker_without_actual_completed_client_log_still_fails(self):
        reset=FAILURE.replace("io.netty.handler.codec.EncoderException: java.io.IOException: Can't serialize unregistered packet",'io.netty.channel.unix.Errors$NativeIoException: syscall:read(..) failed: Connection reset by peer')
        self.log.write_text(reset)
        client=self.root/'clientA';client.mkdir();(client/'respawn-client.pass').touch()
        (self.root/'clientA.log').write_text('RESPAWN_CLIENT_PHASE A 16 9\n')
        (self.root/'sync/state').write_text('16 9 none 0')
        with self.assertRaises(RuntimeError):self.monitor.poll()

    def test_clients_boot_together_but_only_a_join_opens_b_connection_gate(self):
        starts=[]
        start_clients(starts.append)
        self.assertEqual(starts,['clientA','clientB'])
        self.assertFalse((self.root/'sync/client-A-joined').exists())
        self.log.write_text(JOIN);self.monitor.poll()
        self.assertTrue((self.root/'sync/client-A-joined').is_file())

    def test_native_coloured_join_opens_the_second_client_gate(self):
        self.log.write_text('\x1b[m\x1b[32m'+JOIN+'\x1b[m')
        self.monitor.poll()
        self.assertTrue((self.root/'sync/client-A-joined').is_file())

    def test_coloured_retry_keeps_original_bytes_in_the_receipt_hash(self):
        original='\x1b[32m'+FAILURE+'\x1b[m'+JOIN
        self.log.write_text(original);self.monitor.poll()
        self.assertEqual('1',(self.root/'sync/login-retry-A').read_text())
        (self.root/'sync/login-retried-A').write_text('1')
        write_receipts(self.root);audit(self.log,fixture_logins=True)
        self.assertEqual(original,self.log.read_text())

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
