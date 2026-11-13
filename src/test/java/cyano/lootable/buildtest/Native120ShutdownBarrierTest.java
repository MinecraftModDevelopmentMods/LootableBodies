package cyano.lootable.buildtest;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native120ShutdownBarrierTest {
    @Test void completedWorkDoesNotPermitPreLoopShutdown() {
        assertFalse(NativeShutdownBarrier.ready(List.of(CompletableFuture.completedFuture(null)), 0, () -> 0, 30));
        assertTrue(NativeShutdownBarrier.ready(List.of(CompletableFuture.completedFuture(null)), 1, () -> 0, 30));
    }

    @Test void waitsForEveryActualCompletion() {
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        List<CompletableFuture<Void>> work = List.of(CompletableFuture.completedFuture(null), delayed);
        assertFalse(NativeShutdownBarrier.ready(work, 1, () -> 0, 30));
        assertFalse(delayed.isDone());
        delayed.complete(null);
        assertTrue(NativeShutdownBarrier.ready(work, 2, () -> 0, 30));
    }

    @Test void nativeWorkerFailureCannotBecomeSuccessfulShutdown() {
        CompletableFuture<Void> failed = CompletableFuture.failedFuture(new IllegalStateException("controlled native task failure"));
        CompletionException failure = assertThrows(CompletionException.class,
                () -> NativeShutdownBarrier.ready(List.of(failed), 1, () -> 0, 30));
        assertEquals("controlled native task failure", failure.getCause().getMessage());
    }

    @Test void unfinishedWorkFailsAtOriginalInnerDeadline() {
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> NativeShutdownBarrier.ready(List.of(delayed), 1, () -> 30, 30));
        assertTrue(failure.getMessage().contains("deadline"));
        assertFalse(delayed.isDone());
    }

    @Test void canceledNativeWorkIsNotPositiveEvidence() {
        CompletableFuture<Void> canceled = new CompletableFuture<>();
        canceled.cancel(false);
        assertThrows(CancellationException.class,
                () -> NativeShutdownBarrier.ready(List.of(canceled), 1, () -> 0, 30));
    }
}
