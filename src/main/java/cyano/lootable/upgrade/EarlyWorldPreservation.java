package cyano.lootable.upgrade;

import com.mojang.serialization.Dynamic;
import cyano.lootable.ModLog;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.filefix.AbortedFileFixException;
import net.minecraft.util.filefix.FileFixerUpper;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

/** Preserve old chunks before Forge rewrites the level header on startup. */
public final class EarlyWorldPreservation {
    private static volatile boolean installed;
    private static final Map<LevelStorageSource.LevelStorageAccess,Tag> metadata=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LevelStorageSource.LevelStorageAccess,UpgradeJournal> relocating=
            Collections.synchronizedMap(new WeakHashMap<>());
    private EarlyWorldPreservation(){ }
    public static synchronized void install() {
        if(installed)return;
        verifyAttachment(LevelStorageSource.LevelStorageAccess.class);
        if (!UpgradePreservation.RegionAttached.class.isAssignableFrom(net.minecraft.world.level.chunk.storage.SimpleRegionStorage.class))
            throw new IllegalStateException("Native region preservation could not be attached. World loading has been stopped.");
        if (!LayoutAttached.class.isAssignableFrom(FileFixerUpper.class))
            throw new IllegalStateException("Native file relocation preservation could not be attached.");
        if(!(net.minecraft.util.datafix.DataFixers.getDataFixer() instanceof NativeSafeFixer))
            throw new IllegalStateException("Native data fixer preservation could not be attached. World loading has been stopped.");
        installed=true;
    }
    /** Implemented only by the transformed native save access. */
    public interface Attached { }
    public interface LayoutAttached { }

    private static void verifyAttachment(Class<?> reader) {
        if (reader != LevelStorageSource.LevelStorageAccess.class || !Attached.class.isAssignableFrom(reader))
            throw new IllegalStateException("Native save preservation could not be attached. World loading has been stopped before reading or rewriting a world.");
    }

    /** Called before Forge reads extra data or native level conversion. */
    public static void beforeRead(LevelStorageSource.LevelStorageAccess save,boolean fallback) {
        prepareRead(save, fallback);
    }

    private static UpgradeJournal prepareRead(LevelStorageSource.LevelStorageAccess save, boolean fallback) {
        try {
            if(!installed)throw new IllegalStateException("Early preservation was not installed before world loading");
            Path world=save.getLevelPath(LevelResource.ROOT);
            Path header=world.resolve(fallback?"level.dat_old":"level.dat");
            if(!Files.isRegularFile(header)) {
                metadata.remove(save);
                return null; // The native reader selects or rejects the fallback.
            }
            CompoundTag root;
            try(DataInputStream input=new DataInputStream(new GZIPInputStream(Files.newInputStream(header)))) {
                root=NbtIo.read(input,NbtAccounter.create(64L*1024*1024));
            }
            Tag original=root.get("lootablebodies");
            if(original!=null)metadata.put(save,original.copy());else metadata.remove(save);
            return UpgradePreservation.prepareEarly(world,header);
        } catch(IOException | RuntimeException unsafe){throw new UnsafeWorldUpgrade(unsafe);}
    }
    public static void beforeFileFix(LevelStorageSource.LevelStorageAccess save, boolean changesLayout)
            throws AbortedFileFixException {
        try {
            Path world=save.getLevelPath(LevelResource.ROOT);
            if(changesLayout) verifyLayoutCanMove(world);
            UpgradeJournal preserved=prepareRead(save,false);
            if(changesLayout && preserved!=null) relocating.put(save,preserved);
        } catch(IOException unsafe) {
            throw new AbortedFileFixException(new UnsafeFileLayout(new UnsafeWorldUpgrade(unsafe)));
        } catch(UnsafeWorldUpgrade unsafe) {
            throw new AbortedFileFixException(new UnsafeFileLayout(unsafe));
        }
    }

    private static void verifyLayoutCanMove(Path world) throws IOException {
        if(Files.exists(world.resolve("filefix"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("An earlier native file upgrade is incomplete");
        for(String dimension : new String[]{"overworld", "the_nether", "the_end"}) {
            Path old = switch(dimension) {
                case "the_nether" -> world.resolve("DIM-1");
                case "the_end" -> world.resolve("DIM1");
                default -> world;
            };
            Path moved=world.resolve("dimensions/minecraft").resolve(dimension);
            for(String store : new String[]{"region", "entities", "poi"}) {
                requireMoveDestinationFree(old.resolve(store), moved.resolve(store));
            }
            Path oldCapabilities=old.resolve("data/capabilities.dat");
            Path newCapabilities=moved.resolve("data/capabilities.dat");
            requireMoveDestinationFree(oldCapabilities,newCapabilities);
            requireMoveDestinationFree(oldCapabilities,moved.resolve("data/forge/capabilities.dat"));
        }
    }

    private static void requireMoveDestinationFree(Path from, Path to) throws IOException {
        if(Files.exists(from, LinkOption.NOFOLLOW_LINKS) && Files.exists(to, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Both old and new world paths exist: "+from+" and "+to);
    }

    public static void afterFileFix(LevelStorageSource.LevelStorageAccess save) throws AbortedFileFixException {
        UpgradeJournal preserved=relocating.remove(save);
        if(preserved==null)return;
        Path world=save.getLevelPath(LevelResource.ROOT);
        try {
            UpgradePreservation.prepareAfterLayoutMove(world,world.resolve("level.dat"),preserved);
        } catch(IOException | RuntimeException unsafe) {
            throw new AbortedFileFixException(new UnsafeFileLayout(new UnsafeWorldUpgrade(unsafe)));
        }
    }

    private static final class UnsafeFileLayout extends IOException {
        private static final long serialVersionUID=1L;
        UnsafeFileLayout(UnsafeWorldUpgrade cause){super(cause.getMessage(),cause);}
    }

    public static void handleDedicatedFileRefusal(LevelStorageSource.LevelStorageAccess save, AbortedFileFixException failure) {
        if(failure.getCause() instanceof UnsafeFileLayout rejected &&
                rejected.getCause() instanceof UnsafeWorldUpgrade unsafe) stopDedicated(save,unsafe);
    }

    /** The dedicated startup entry point has no shutdown hook before world construction. */
    @FunctionalInterface
    public interface WorldReader {
        Dynamic<?> read() throws IOException;
    }

    public static Dynamic<?> readDedicatedWorld(LevelStorageSource.LevelStorageAccess save, WorldReader reader) throws IOException {
        try {
            return reader.read();
        } catch(UnsafeWorldUpgrade unsafe) {
            stopDedicated(save,unsafe);
            throw unsafe;
        }
    }

    private static void stopDedicated(LevelStorageSource.LevelStorageAccess save, UnsafeWorldUpgrade unsafe) {
        metadata.remove(save);
        relocating.remove(save);
        UpgradePreservation.close();
        try {
            save.close();
        } catch(IOException closeFailure) {
            unsafe.addSuppressed(closeFailure);
        }
        ModLog.log(Level.ERROR,unsafe,unsafe.getMessage());
        LogManager.shutdown();
        System.exit(1);
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
