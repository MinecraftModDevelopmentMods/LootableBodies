package cyano.lootable.upgrade;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fmllegacy.WorldPersistenceHooks;

/** Preserve old chunks before Forge rewrites the level header on startup. */
public final class EarlyWorldPreservation implements WorldPersistenceHooks.WorldPersistenceHook {
    private static boolean installed;
    private final Map<LevelStorageSource.LevelStorageAccess,CompoundTag> metadata=Collections.synchronizedMap(new WeakHashMap<>());
    private EarlyWorldPreservation(){ }
    public static synchronized void install() {
        if(!installed){WorldPersistenceHooks.addHook(new EarlyWorldPreservation());installed=true;}
    }
    @Override public String getModId(){return "lootablebodies";}
    @Override public void readData(LevelStorageSource.LevelStorageAccess save,WorldData info,CompoundTag tag) {
        metadata.put(save,tag.copy());
        try {UpgradePreservation.prepareEarly(save.getLevelPath(LevelResource.ROOT));}
        catch(IOException unsafe){throw new UnsafeWorldUpgrade(unsafe);}
    }
    @Override public CompoundTag getDataForWriting(LevelStorageSource.LevelStorageAccess save,WorldData info) {
        CompoundTag original=metadata.get(save);return original==null?new CompoundTag():original.copy();
    }
    private static final class UnsafeWorldUpgrade extends Error {
        private static final long serialVersionUID=1L;
        UnsafeWorldUpgrade(Throwable cause) {
            // The native level reader catches Exception and falls back to an
            // older header. Preservation failure must escape that fallback.
            super("Lootable Bodies could not preserve this world's corpses. World loading has been stopped; keep the original world and recovery journal.",cause);
        }
    }
}
