package cyano.lootable.upgrade;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.*;
import com.mojang.datafixers.DataFixer;
/** World-local originals and the native conversion boundary. */
public final class UpgradePreservation {
    public static final int DATA_VERSION=3;
    public static final int MINECRAFT_VERSION=1631;
    public static final String VERSION="LootableBodiesDataVersion";
    public static final String SOURCES="LootableBodiesSourceVersions";
    public static final String ITEMS_PENDING="LootableBodiesInventorySource";
    private static final String PAYLOAD="LootableBodiesUpgradePayload";
    private static UpgradeJournal journal;
    private UpgradePreservation() { }
    public static void register(DataFixer fixer){ItemMigration.register(wrapper(fixer));}
    public static UpgradeJournal prepare(Path world)throws IOException {UpgradeJournal complete=UpgradeJournal.scan(world);journal=complete;return complete;}
    public static void close(){journal=null;}
    static UpgradeJournal journal(){return journal;}
    public static DataFixer wrapper(DataFixer delegate){return delegate instanceof NativeSafeFixer?delegate:new NativeSafeFixer(delegate,null);}
    public static NBTTagCompound chunkContext(NBTTagCompound chunk){return chunk;}
    public static void clearChunkContext(NBTTagCompound chunk){chunk.removeTag("LootableBodiesChunkContext");}
    public static NBTTagCompound restore(NBTTagCompound input) {
        if(input.contains(PAYLOAD,10))return input.getCompound(PAYLOAD).copy();return input;
    }
    public static void clearTransient(NBTTagCompound output){output.removeTag(PAYLOAD);output.removeTag(ITEMS_PENDING);}
    static int chunkSourceVersion(NBTTagCompound versions) {
        if(versions.contains("VerifiedChunkDataVersion",3))return versions.getInt("VerifiedChunkDataVersion");
        int version=versions.getInt("DataVersion"),family=-1;
        NBTTagList mods=versions.getCompound("WorldFML").getList("ModList",10);
        for(int m=0;m<mods.size();m++) {
            NBTTagCompound mod=mods.getCompound(m);if(!mod.getString("ModId").equals("minecraft"))continue;
            String release=mod.getString("ModVersion");
            family=release.equals("1.13.2")?1631:release.equals("1.12.2")?1343:release.equals("1.11.2")?922:release.equals("1.10.2")?512:-1;
        }
        if(family>0) {
            int stamped=versions.contains("ChunkDataVersion",3)?versions.getInt("ChunkDataVersion"):-1;
            version=stamped>=512 && stamped<=1631?Math.min(stamped,family):family;
        }
        return version;
    }

}
