package cyano.lootable.upgrade;

import static cyano.lootable.upgrade.NativeNbt.hasType;
import static cyano.lootable.upgrade.NativeNbt.typedList;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import com.mojang.datafixers.DataFixer;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.ValueOutput;
/** World-local originals and the native conversion boundary. */
public final class UpgradePreservation {
    public static final int DATA_VERSION=14;
    public static final int MINECRAFT_VERSION=4790;
    public static final String VERSION="LootableBodiesDataVersion";
    public static final String SOURCES="LootableBodiesSourceVersions";
    public static final String ITEMS_PENDING="LootableBodiesInventorySource";
    private static final String PAYLOAD="LootableBodiesUpgradePayload";
    private static UpgradeJournal journal;
    private static Path earlyWorld;
    private UpgradePreservation() { }
    public static synchronized void register(DataFixer fixer) {
        ItemMigration.register(wrapper(fixer));
    }
    public static synchronized UpgradeJournal prepare(Path world)throws IOException {
        return activate(UpgradeJournal.scan(world),null,net.minecraft.util.datafix.DataFixers.getDataFixer());
    }
    static synchronized UpgradeJournal prepareEarly(Path world,Path header)throws IOException {
        return prepareEarly(world,header,net.minecraft.util.datafix.DataFixers.getDataFixer());
    }
    static synchronized UpgradeJournal prepareEarly(Path world,Path header,DataFixer fixer)throws IOException {
        return activate(UpgradeJournal.scan(world,header),world.toRealPath(),fixer);
    }
    static synchronized UpgradeJournal prepareAfterLayoutMove(Path world, Path header, UpgradeJournal before) throws IOException {
        return activate(UpgradeJournal.scanAfterLayoutMove(world, header, before), world.toRealPath(),
                net.minecraft.util.datafix.DataFixers.getDataFixer());
    }
    private static UpgradeJournal activate(UpgradeJournal complete,Path openedWorld,DataFixer fixer)throws IOException {
        validateEntityChunks(complete,fixer);
        // Do not expose an incomplete new world to concurrent native readers.
        journal=complete;earlyWorld=openedWorld;return complete;
    }
    public static synchronized UpgradeJournal prepareForStart(Path world,DataFixer fixer)throws IOException {
        if(journal!=null && world.toRealPath().equals(earlyWorld)){
            // The server's actual fixer can differ from the early level reader.
            // Reuse preserved bytes, but validate that current converter too.
            validateEntityChunks(journal,fixer);earlyWorld=null;return journal;
        }
        return activate(UpgradeJournal.scan(world),null,fixer);
    }
    private static void validateEntityChunks(UpgradeJournal complete,DataFixer delegate) throws IOException {
        int depth=0;
        while(delegate instanceof NativeSafeFixer) {
            if(++depth>32)throw new IOException("Recursive native converter during entity preflight");
            delegate=((NativeSafeFixer)delegate).delegate;
        }
        complete.validateEntityChunks(new NativeSafeFixer(delegate,null,complete));
    }
    public static synchronized void close(){journal=null;earlyWorld=null;}
    static UpgradeJournal journal(){return journal;}
    private static DataFixer nativeWrapper;

    /** Called by the native accessor before save readers retain their fixer. */
    public static synchronized DataFixer wrapper(DataFixer delegate) {
        if(delegate instanceof NativeSafeFixer)return delegate;
        if(!(nativeWrapper instanceof NativeSafeFixer safe) || safe.delegate!=delegate)
            nativeWrapper=new NativeSafeFixer(delegate,null);
        return nativeWrapper;
    }
    /** Attests that the region read seam is installed before any world opens. */
    public interface RegionAttached { }

    public static CompoundTag upgradeRegion(DataFixer fixer, DataFixTypes kind, CompoundTag input,
            int fallback, CompoundTag context, UnaryOperator<CompoundTag> structures) {
        return upgradeRegion(fixer, kind, input, fallback, context, MINECRAFT_VERSION, structures);
    }

    public static CompoundTag upgradeRegion(DataFixer fixer, DataFixTypes kind, CompoundTag input,
            int fallback, CompoundTag context, int target, UnaryOperator<CompoundTag> structures) {
        CompoundTag copy = input.copy();
        SimpleRegionStorage.injectDatafixingContext(copy, context);
        DataFixer delegate = fixer instanceof NativeSafeFixer safe ? safe.delegate : fixer;
        boolean terrain = kind == DataFixTypes.CHUNK;
        CompoundTag converted = NativeNbt.update(new NativeSafeFixer(delegate, terrain ? structures : null),
                terrain ? References.CHUNK : References.ENTITY_CHUNK, copy,
                NbtUtils.getDataVersion(copy, fallback), target);
        converted.remove("__context");
        return converted;
    }

    /** Adapt the old Forge capability payload to the extra codec field. */
    public static CompoundTag upgradeLevelCapabilities(CompoundTag input) {
        if (!hasType(input, "data", 10)) return input;
        if (input.contains("DataVersion")) {
            if (!hasType(input, "DataVersion", 3)) return input;
            int source = input.getIntOr("DataVersion", -1);
            if (source < 512 || source >= 4671) return input;
        } else if (!input.keySet().equals(java.util.Set.of("data")) || !input.getCompoundOrEmpty("data").isEmpty()) {
            // The original Forge writer emitted an unstamped empty capability
            // record. Leave other unversioned shapes to the native codec.
            return input;
        }
        CompoundTag result = input.copy();
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("data", result.getCompoundOrEmpty("data"));
        result.put("data", wrapper);
        return result;
    }

    public static CompoundTag chunkContext(CompoundTag chunk){return chunk;}
    public static void clearChunkContext(CompoundTag chunk){chunk.remove("LootableBodiesChunkContext");}
    public static CompoundTag restore(CompoundTag input) {
        if(hasType(input, PAYLOAD, 10))return input.getCompoundOrEmpty(PAYLOAD).copy();return input;
    }
    public static void clearTransient(ValueOutput output) {
        output.discard(PAYLOAD);
        output.discard(ITEMS_PENDING);
    }
    static int chunkSourceVersion(CompoundTag versions) {
        if(hasType(versions, "VerifiedChunkDataVersion", 3))return versions.getIntOr("VerifiedChunkDataVersion", 0);
        int version=versions.getIntOr("DataVersion", 0),family=-1;
        ListTag mods=modList(versions.getCompoundOrEmpty("WorldFML"));
        for(int m=0;m<mods.size();m++) {
            CompoundTag mod=mods.getCompoundOrEmpty(m);if(!mod.getStringOr("ModId", "").equals("minecraft"))continue;
            String release=mod.getStringOr("ModVersion", "");
            family=release.equals("26.1.2")?MINECRAFT_VERSION:release.equals("1.21.11")?4671:release.equals("1.21.1")?3955:release.equals("1.20.6")?3839:release.equals("1.20.1")?3465:release.equals("1.19.4")?3337:release.equals("1.18.2")?2975:release.equals("1.17.1")?2730:release.equals("1.16.5")?2586:release.equals("1.15.2")?2230:release.equals("1.14.4")?1976:release.equals("1.13.2")?1631:release.equals("1.12.2")?1343:release.equals("1.11.2")?922:release.equals("1.10.2")?512:-1;
        }
        int stamped=hasType(versions, "ChunkDataVersion", 3)?versions.getIntOr("ChunkDataVersion", 0):-1;
        int writer=hasType(versions, "CorpseWriterDataVersion", 3)?versions.getIntOr("CorpseWriterDataVersion", 0):-1;
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
        return typedList(fml, fml.contains("ModList")?"ModList":"LoadingModList", 10);
    }

}
