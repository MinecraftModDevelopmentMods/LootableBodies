package cyano.lootable.upgrade;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.world.storage.FolderName;
import net.minecraft.world.storage.IServerConfiguration;
import net.minecraft.world.storage.SaveFormat;
import net.minecraftforge.fml.WorldPersistenceHooks;

/** Preserve old chunks before Forge rewrites the level header on startup. */
public final class EarlyWorldPreservation implements WorldPersistenceHooks.WorldPersistenceHook {
    private static boolean installed;
    private final Map<SaveFormat.LevelSave,CompoundNBT> metadata=Collections.synchronizedMap(new WeakHashMap<>());
    private EarlyWorldPreservation(){ }
    public static synchronized void install() {
        if(!installed){WorldPersistenceHooks.addHook(new EarlyWorldPreservation());installed=true;}
    }
    @Override public String getModId(){return "lootablebodies";}
    @Override public void readData(SaveFormat.LevelSave save,IServerConfiguration info,CompoundNBT tag) {
        metadata.put(save,tag.copy());
        try {UpgradePreservation.prepareEarly(save.resolveFilePath(FolderName.DOT));}
        catch(IOException unsafe){throw new UnsafeWorldUpgrade(unsafe);}
    }
    @Override public CompoundNBT getDataForWriting(SaveFormat.LevelSave save,IServerConfiguration info) {
        CompoundNBT original=metadata.get(save);return original==null?new CompoundNBT():original.copy();
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
