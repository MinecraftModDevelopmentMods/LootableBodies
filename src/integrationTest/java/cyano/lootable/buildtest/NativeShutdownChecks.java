package cyano.lootable.buildtest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;

/** Private historical fixture lifecycle; no production hooks or native-state clearing. */
final class NativeShutdownChecks {
    private static MinecraftServer server;
    private static List<CompletableFuture<?>> pending;
    private static long deadline;
    private static int ticks, quietRounds, barriers;

    static void register() {
        TickEvent.ServerTickEvent.Post.BUS.addListener(NativeShutdownChecks::tick);
    }

    static void request(MinecraftServer current) {
        RuntimeChecks.require(current.isSameThread() && server == null, "Historical shutdown must be requested once on the native server thread");
        server = current;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        // Let the normal server loop service native lighting/unload work. Freezing
        // simulation preserves the exact historical positions already asserted.
        current.tickRateManager().setFrozen(true);
        pending = queueNativeBarriers();
        System.out.println("NATIVE_HISTORICAL_SHUTDOWN_REQUEST barriers=" + barriers + " frozen=" + current.tickRateManager().isFrozen());
    }

    private static List<CompletableFuture<?>> queueNativeBarriers() {
        List<CompletableFuture<?>> result = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            Set<ChunkPos> loaded = new HashSet<>();
            // Observe the actual admitted/generating holders. Reading these maps
            // neither loads deferred chunks nor changes their tickets or storage.
            for (String name : List.of("visibleChunkMap", "updatingChunkMap")) {
                try {
                    Field field = ChunkMap.class.getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(level.getChunkSource().chunkMap);
                    if (!(value instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> holders))
                        throw new IllegalStateException("Unexpected native chunk-holder map " + name);
                    for (Object holder : holders.values()) {
                        if (!(holder instanceof ChunkHolder chunk))
                            throw new IllegalStateException("Unexpected native chunk holder");
                        loaded.add(chunk.getPos());
                    }
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException("Cannot inspect native shutdown work", error);
                }
            }
            var light = level.getChunkSource().getLightEngine();
            for (ChunkPos pos : loaded) result.add(light.waitForPendingTasks(pos.x(), pos.z()));
            light.tryScheduleUpdate();
        }
        RuntimeChecks.require(!result.isEmpty(), "Historical shutdown must observe real loaded native chunks");
        barriers += result.size();
        return result;
    }

    private static void tick(TickEvent.ServerTickEvent.Post event) {
        if (server == null) return;
        MinecraftServer current = server;
        try {
            ticks++;
            RuntimeChecks.require(current.isSameThread() && current.tickRateManager().isFrozen(), "Historical shutdown changed native simulation state");
            if (!NativeShutdownBarrier.ready(pending, ticks, System::nanoTime, deadline)) return;
            for (ServerLevel level : current.getAllLevels()) {
                var light = level.getChunkSource().getLightEngine();
                if (light.hasLightWork()) {
                    light.tryScheduleUpdate();
                    return;
                }
            }
            // A second barrier round includes work enqueued by that real native
            // tick, rather than mistaking completion of an earlier batch for idle.
            if (++quietRounds < 2) {
                pending = queueNativeBarriers();
                return;
            }
            System.out.println("NATIVE_HISTORICAL_SHUTDOWN_READY ticks=" + ticks + " rounds=" + quietRounds + " barriers=" + barriers + " frozen=" + current.tickRateManager().isFrozen());
            server = null;
            current.halt(false);
        } catch (Throwable error) {
            server = null;
            if (RuntimeChecks.failure == null) RuntimeChecks.failure = error;
            else RuntimeChecks.failure.addSuppressed(error);
            error.printStackTrace();
            current.halt(false);
        }
    }
}
