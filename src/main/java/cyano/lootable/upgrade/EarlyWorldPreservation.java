package cyano.lootable.upgrade;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Preserve old chunks before Forge rewrites the level header on startup. */
public final class EarlyWorldPreservation {
    private static volatile boolean installed;
    private static final Map<LevelStorageSource.LevelStorageAccess,Tag> metadata=Collections.synchronizedMap(new WeakHashMap<>());
    private EarlyWorldPreservation(){ }
    public static synchronized void install() {
        if(installed)return;
        verifyAttachment(LevelStorageSource.LevelStorageAccess.class);
        installed=true;
    }
    private static void verifyAttachment(Class<?> reader) {
        try {
            if(!reader.getName().equals(LevelStorageSource.LevelStorageAccess.class.getName()))
                throw new IllegalStateException("Unexpected native save reader");
            java.lang.reflect.Method marker=reader.getDeclaredMethod("lootablebodies$preservationAttached");
            if(marker.getReturnType()!=boolean.class || !marker.isSynthetic() ||
                    !java.lang.reflect.Modifier.isStatic(marker.getModifiers()) ||
                    !java.lang.reflect.Modifier.isPublic(marker.getModifiers()) || !Boolean.TRUE.equals(marker.invoke(null)))
                throw new IllegalStateException("Native save preservation attachment is invalid");
        } catch(ReflectiveOperationException missing) {
            throw new IllegalStateException("Native save preservation could not be attached. World loading has been stopped before reading or rewriting a world.",missing);
        }
    }
    /** Called before Forge reads extra data or native level conversion. */
    public static void beforeRead(LevelStorageSource.LevelStorageAccess save,boolean fallback) {
        try {
            if(!installed)throw new IllegalStateException("Early preservation was not installed before world loading");
            Path world=save.getLevelPath(LevelResource.ROOT);
            Path header=world.resolve(fallback?"level.dat_old":"level.dat");
            if(!Files.isRegularFile(header)) {
                metadata.remove(save);
                return; // The native reader selects or rejects the fallback.
            }
            CompoundTag root;
            try(DataInputStream input=new DataInputStream(new GZIPInputStream(Files.newInputStream(header)))) {
                root=NbtIo.read(input,NbtAccounter.create(64L*1024*1024));
            }
            Tag original=root.get("lootablebodies");
            if(original!=null)metadata.put(save,original.copy());else metadata.remove(save);
            UpgradePreservation.prepareEarly(world,header);
        } catch(IOException | RuntimeException unsafe){throw new UnsafeWorldUpgrade(unsafe);}
    }
    /** Restore only this mod's original field on the same native save access. */
    public static void beforeWrite(LevelStorageSource.LevelStorageAccess save,CompoundTag root) {
        Tag original=metadata.get(save);if(original!=null)root.put("lootablebodies",original.copy());
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
