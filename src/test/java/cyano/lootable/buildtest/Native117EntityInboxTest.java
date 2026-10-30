package cyano.lootable.buildtest;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real native manager/inbox; controlled storage futures are fixture seams. */
class Native117EntityInboxTest {
    private static final ChunkPos POS = new ChunkPos(-4, 9);
    private static final class Saved implements EntityAccess {
        final UUID id = UUID.fromString("00000000-1171-4171-8171-000000000088");
        public int getId() { return 88; }
        public UUID getUUID() { return id; }
        public BlockPos blockPosition() { return new BlockPos(-63, 80, 145); }
        public AABB getBoundingBox() { return new AABB(blockPosition()); }
        public void setLevelCallback(EntityInLevelCallback callback) { }
        public Stream<Saved> getSelfAndPassengers() { return Stream.of(this); }
        public Stream<Saved> getPassengersAndSelf() { return Stream.of(this); }
        public void setRemoved(Entity.RemovalReason reason) { }
        public boolean shouldBeSaved() { return true; }
        public boolean isAlwaysTicking() { return false; }
    }
    private static final class Storage implements EntityPersistentStorage<Saved> {
        final CompletableFuture<ChunkEntities<Saved>> read = new CompletableFuture<>();
        int loads, stores;
        public CompletableFuture<ChunkEntities<Saved>> loadEntities(ChunkPos pos) {
            assertEquals(POS, pos); loads++; return read;
        }
        public void storeEntities(ChunkEntities<Saved> data) { stores++; }
        public void flush(boolean sync) { }
    }
    private static final class Fixture {
        final Storage storage = new Storage();
        final AtomicInteger tracked = new AtomicInteger();
        final PersistentEntitySectionManager<Saved> manager = new PersistentEntitySectionManager<>(
                Saved.class, new LevelCallback<Saved>() {
                    public void onCreated(Saved e) { }
                    public void onDestroyed(Saved e) { }
                    public void onTickingStart(Saved e) { }
                    public void onTickingEnd(Saved e) { }
                    public void onTrackingStart(Saved e) { tracked.incrementAndGet(); }
                    public void onTrackingEnd(Saved e) { }
                }, storage);
        Fixture() { manager.updateChunkStatus(POS, Visibility.TRACKED); }
    }
    @Test void completedReadRequiresActualNativeInboxAdmission() {
        Fixture f = new Fixture(); Saved entity = new Saved();
        f.storage.read.complete(new ChunkEntities<>(POS, Collections.singletonList(entity)));
        assertFalse(f.manager.areEntitiesLoaded(POS.toLong()));
        NativeEntityInbox.await(f.manager, POS.toLong(), () -> false, () -> 0, 10);
        assertTrue(f.manager.areEntitiesLoaded(POS.toLong()));
        assertEquals(1, f.tracked.get()); assertEquals(1, f.storage.loads); assertEquals(0, f.storage.stores);
    }
    @Test void delayedReadProcessesNativeInboxAfterPollingTasks() {
        Fixture f = new Fixture(); AtomicInteger polls = new AtomicInteger(); AtomicLong clock = new AtomicLong();
        NativeEntityInbox.await(f.manager, POS.toLong(), () -> {
            polls.incrementAndGet(); f.storage.read.complete(new ChunkEntities<>(POS, Collections.singletonList(new Saved()))); return true;
        }, clock::getAndIncrement, 10);
        assertEquals(1, polls.get()); assertEquals(1, f.tracked.get()); assertTrue(f.manager.areEntitiesLoaded(POS.toLong()));
    }
    @Test void pendingReadRefusesAtDeadlineWithoutInventingEmptySuccessOrSaving() {
        Fixture f = new Fixture(); AtomicLong clock = new AtomicLong();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                NativeEntityInbox.await(f.manager, POS.toLong(), () -> false, clock::getAndIncrement, 3));
        assertTrue(failure.getMessage().contains("deadline"));
        assertFalse(f.manager.areEntitiesLoaded(POS.toLong())); assertFalse(f.storage.read.isDone());
        assertEquals(1, f.storage.loads); assertEquals(0, f.storage.stores); assertEquals(0, f.tracked.get());
    }
    @Test void genuineEmptyStorageStillPassesThroughNativeInbox() {
        Fixture f = new Fixture(); f.storage.read.complete(new ChunkEntities<>(POS, Collections.emptyList()));
        assertFalse(f.manager.areEntitiesLoaded(POS.toLong()));
        NativeEntityInbox.await(f.manager, POS.toLong(), () -> false, () -> 0, 10);
        assertTrue(f.manager.areEntitiesLoaded(POS.toLong())); assertEquals(0, f.tracked.get()); assertEquals(0, f.storage.stores);
    }
    private static final class BeforeGameLoop extends net.minecraft.util.thread.BlockableEventLoop<Runnable> {
        private final Thread owner=Thread.currentThread();
        BeforeGameLoop(){super("NativeFixtureBeforeGameLoop");}
        protected Runnable wrapRunnable(Runnable task){return task;}
        protected boolean shouldRun(Runnable task){return false;}
        protected Thread getRunningThread(){return owner;}
    }
    @Test void nativeManagedBlockAdmitsCompletionsGatedBeforeTheGameLoop() {
        Fixture f=new Fixture();BeforeGameLoop loop=new BeforeGameLoop();AtomicLong clock=new AtomicLong();
        loop.tell(()->f.storage.read.complete(new ChunkEntities<>(POS,Collections.singletonList(new Saved()))));
        assertFalse(loop.pollTask(),"Native event-loop gate must reproduce the pre-loop condition");
        NativeEntityInbox.await(f.manager,POS.toLong(),loop::pollTask,clock::getAndIncrement,10,loop::managedBlock);
        assertTrue(f.manager.areEntitiesLoaded(POS.toLong()));assertEquals(1,f.tracked.get());assertEquals(0,loop.getPendingTasksCount());
        loop.tell(()->{});assertFalse(loop.pollTask(),"Managed blocking must not leave the native task gate bypassed");
    }
    @Test void nativeManagedBlockRestoresItsGateAfterAPendingDeadline() {
        Fixture f=new Fixture();BeforeGameLoop loop=new BeforeGameLoop();AtomicLong clock=new AtomicLong();
        assertThrows(IllegalStateException.class,()->NativeEntityInbox.await(f.manager,POS.toLong(),loop::pollTask,
                clock::getAndIncrement,3,loop::managedBlock));
        loop.tell(()->{});assertFalse(loop.pollTask());assertFalse(f.manager.areEntitiesLoaded(POS.toLong()));assertEquals(0,f.storage.stores);
    }
}
