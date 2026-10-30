package cyano.lootable.buildtest;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Isolated fixture readiness at the real native entity-storage boundary. */
public final class NativeEntityInbox {
    private NativeEntityInbox() { }

    public static void await(PersistentEntitySectionManager<?> manager, long chunk,
            BooleanSupplier pollTask, LongSupplier clock, long deadline,
            Consumer<BooleanSupplier> managedBlock) {
        managedBlock.accept(()->{
            if(manager.areEntitiesLoaded(chunk))return true;
            manager.tick();
            if(manager.areEntitiesLoaded(chunk))return true;
            if(clock.getAsLong()>=deadline)
                throw new IllegalStateException("Native entity inbox deadline for "+chunk);
            if(!pollTask.getAsBoolean())Thread.yield();
            return false;
        });
        if(!manager.areEntitiesLoaded(chunk))
            throw new IllegalStateException("Native managed wait returned before entity admission for "+chunk);
    }

    public static void await(PersistentEntitySectionManager<?> manager, long chunk,
            BooleanSupplier pollTask, LongSupplier clock, long deadline) {
        await(manager,chunk,pollTask,clock,deadline,ready->{while(!ready.getAsBoolean())Thread.yield();});
    }
}
