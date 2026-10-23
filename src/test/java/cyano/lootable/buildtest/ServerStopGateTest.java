package cyano.lootable.buildtest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerStopGateTest {
    @Test void aClearedRunningFlagDoesNotFinishTheFinalSave() throws Exception {
        CountDownLatch finalSaveStarted = new CountDownLatch(1);
        CountDownLatch finishSave = new CountDownLatch(1);
        Thread server = new Thread(() -> {
            finalSaveStarted.countDown();
            try { finishSave.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try {
            assertTrue(finalSaveStarted.await(5, TimeUnit.SECONDS));
            assertFalse(ServerStopGate.ready(true, false, server),
                    "Reopening now would replace session.lock while the retiring server is still saving");
        } finally {
            finishSave.countDown();
            server.join(5000);
        }
        assertFalse(server.isAlive());
        assertTrue(ServerStopGate.ready(true, false, server));
    }

    @Test void anActiveClientWorldOrServerStillBlocksReopening() {
        assertFalse(ServerStopGate.ready(false, false, null));
        assertFalse(ServerStopGate.ready(true, true, null));
    }

    @Test void remoteDisconnectDoesNotRequireAnIntegratedServerThread() {
        assertTrue(ServerStopGate.ready(true, false, null));
    }
}
