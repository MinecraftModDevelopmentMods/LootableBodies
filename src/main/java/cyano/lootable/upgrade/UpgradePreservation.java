package cyano.lootable.upgrade;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import com.mojang.datafixers.DataFixer;
/** World-local originals and the native conversion boundary. */
public final class UpgradePreservation {
    public static final int DATA_VERSION=7;
    public static final int MINECRAFT_VERSION=2730;
    public static final String VERSION="LootableBodiesDataVersion";
    public static final String SOURCES="LootableBodiesSourceVersions";
    public static final String ITEMS_PENDING="LootableBodiesInventorySource";
    private static final String PAYLOAD="LootableBodiesUpgradePayload";
    private static UpgradeJournal journal;
    private static Path earlyWorld;
    private UpgradePreservation() { }
    public static synchronized void register(DataFixer fixer) {
        DataFixer safe=wrapper(fixer);
        // Server-about-to-start is too late for 1.16 level settings. Forge
        // exposes this single native field through our access transformer.
        net.minecraft.util.datafix.DataFixers.DATA_FIXER=safe;
        ItemMigration.register(safe);
    }
    public static synchronized UpgradeJournal prepare(Path world)throws IOException {
        UpgradeJournal complete=UpgradeJournal.scan(world);
        validateEntityChunks(complete);
        // Do not expose an incomplete new world to concurrent native readers.
        journal=complete;earlyWorld=null;return complete;
    }
    static synchronized UpgradeJournal prepareEarly(Path world)throws IOException {
        UpgradeJournal complete=prepare(world);earlyWorld=world.toRealPath();return complete;
    }
    public static synchronized UpgradeJournal prepareForStart(Path world)throws IOException {
        if(journal!=null && world.toRealPath().equals(earlyWorld)){
            // The server's actual fixer can differ from the early level reader.
            // Reuse preserved bytes, but validate that current converter too.
            validateEntityChunks(journal);earlyWorld=null;return journal;
        }
        return prepare(world);
    }
    private static void validateEntityChunks(UpgradeJournal complete) throws IOException {
        DataFixer delegate=net.minecraft.util.datafix.DataFixers.getDataFixer();
        int depth=0;
        while(delegate instanceof NativeSafeFixer) {
            if(++depth>32)throw new IOException("Recursive native converter during entity preflight");
            delegate=((NativeSafeFixer)delegate).delegate;
        }
        complete.validateEntityChunks(new NativeSafeFixer(delegate,null,complete));
    }
    public static synchronized void close(){journal=null;earlyWorld=null;}
    static UpgradeJournal journal(){return journal;}
    public static DataFixer wrapper(DataFixer delegate){return delegate instanceof NativeSafeFixer?delegate:new NativeSafeFixer(delegate,null);}
    public static CompoundTag chunkContext(CompoundTag chunk){return chunk;}
    public static void clearChunkContext(CompoundTag chunk){chunk.remove("LootableBodiesChunkContext");}
    public static CompoundTag restore(CompoundTag input) {
        if(input.contains(PAYLOAD,10))return input.getCompound(PAYLOAD).copy();return input;
    }
    public static void clearTransient(CompoundTag output){output.remove(PAYLOAD);output.remove(ITEMS_PENDING);}
    static int chunkSourceVersion(CompoundTag versions) {
        if(versions.contains("VerifiedChunkDataVersion",3))return versions.getInt("VerifiedChunkDataVersion");
        int version=versions.getInt("DataVersion"),family=-1;
        ListTag mods=modList(versions.getCompound("WorldFML"));
        for(int m=0;m<mods.size();m++) {
            CompoundTag mod=mods.getCompound(m);if(!mod.getString("ModId").equals("minecraft"))continue;
            String release=mod.getString("ModVersion");
            family=release.equals("1.17.1")?2730:release.equals("1.16.5")?2586:release.equals("1.15.2")?2230:release.equals("1.14.4")?1976:release.equals("1.13.2")?1631:release.equals("1.12.2")?1343:release.equals("1.11.2")?922:release.equals("1.10.2")?512:-1;
        }
        int stamped=versions.contains("ChunkDataVersion",3)?versions.getInt("ChunkDataVersion"):-1;
        int writer=versions.contains("CorpseWriterDataVersion",3)?versions.getInt("CorpseWriterDataVersion"):-1;
        if(writer>=512 && writer<=MINECRAFT_VERSION) {
            // Forge can save the current level header before scanning old chunks.
            // A preserved owned writer marker proves this chunk is at least that
            // old format; a plausible older outer stamp must not downgrade it.
            int upper=Math.max(family,writer);
            return stamped>=512 && stamped<=MINECRAFT_VERSION?Math.min(Math.max(stamped,writer),upper):writer;
        }
        if(family>0) {
            version=stamped>=512 && stamped<=MINECRAFT_VERSION?Math.min(stamped,family):family;
        }
        return version;
    }
    static ListTag modList(CompoundTag fml) {
        // Forge14+ uses LoadingModList; earlier worlds use ModList. Keep the
        // retained header intact and do not select a conflicting duplicate.
        if(fml.contains("ModList") && fml.contains("LoadingModList")
                && !fml.get("ModList").equals(fml.get("LoadingModList")))return new ListTag();
        return fml.getList(fml.contains("ModList")?"ModList":"LoadingModList",10);
    }

}
