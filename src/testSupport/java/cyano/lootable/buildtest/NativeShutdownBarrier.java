package cyano.lootable.buildtest;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Private-fixture shutdown readiness; never part of the mod. */
public final class NativeShutdownBarrier {
    private NativeShutdownBarrier() { }

    public static boolean ready(List<? extends CompletableFuture<?>> pending, long nativeTicks,
            LongSupplier clock, long deadline) {
        if (clock.getAsLong() >= deadline)
            throw new IllegalStateException("Native fixture shutdown readiness deadline");
        boolean completed = true;
        for (CompletableFuture<?> task : pending) {
            if (task.isDone()) task.join(); // Propagate real failure/cancellation, never invent completion.
            else completed = false;
        }
        return nativeTicks > 0 && completed;
    }
}
