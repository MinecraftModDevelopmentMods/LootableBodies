package cyano.lootable.upgrade;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.*;
import net.minecraftforge.common.util.CompoundDataFixer;
import net.minecraftforge.common.util.ModFixs;

/** Protects custom corpse data before vanilla entity fixers can consume it. */
public final class UpgradePreservation {
    public static final String VERSION="LootableBodiesDataVersion";
    public static final String SOURCES="LootableBodiesSourceVersions";
    public static final String ITEMS_PENDING="LootableBodiesInventorySource";
    private static final String PAYLOAD="LootableBodiesUpgradePayload";
    private static final String CONTEXT="LootableBodiesChunkContext";
    private static UpgradeJournal journal;
    private static CompoundDataFixer pipeline;
    private UpgradePreservation() { }

    public static void register(CompoundDataFixer fixer) {
        pipeline=fixer;
        ItemMigration.register(fixer);
        ModFixs owned=fixer.init("lootablebodies",1);
        owned.registerFix(FixTypes.CHUNK,new IFixableData() {
            @Override public int getFixVersion(){return 1;}
            @Override public NBTTagCompound fixTagCompound(NBTTagCompound chunk) {
                NBTTagCompound level=chunk.getCompoundTag("Level");NBTTagList entities=level.getTagList("Entities",10);
                for(int i=0;i<entities.tagCount();i++) protect(entities.getCompoundTagAt(i),level.getInteger("xPos"),level.getInteger("zPos"),true);
                return repairContext(chunk);
            }
        });
        owned.registerFix(FixTypes.ENTITY,new IFixableData() {
            @Override public int getFixVersion(){return 1;}
            @Override public NBTTagCompound fixTagCompound(NBTTagCompound entity) {protect(entity,0,0,false);return entity;}
        });
        // Historical worlds cannot carry this new preservation stamp. It also
        // schedules our walker when their older mod/Minecraft stamps are wrong.
        ModFixs preservation=fixer.init("lootablebodies_upgrade",1);
        preservation.registerFix(FixTypes.CHUNK,owned.getFixes(FixTypes.CHUNK).get(0));
        preservation.registerFix(FixTypes.ENTITY,owned.getFixes(FixTypes.ENTITY).get(0));
        // Walkers also run when our own mod stamp is current but Minecraft's
        // stamp is damaged. A current mod stamp must not bypass preservation.
        fixer.registerVanillaWalker(FixTypes.CHUNK,(nested,chunk,version)-> {
            NBTTagCompound level=chunk.getCompoundTag("Level");NBTTagList entities=level.getTagList("Entities",10);
            for(int i=0;i<entities.tagCount();i++) protect(entities.getCompoundTagAt(i),level.getInteger("xPos"),level.getInteger("zPos"),true);
            return repairContext(chunk);
        });
        fixer.registerVanillaWalker(FixTypes.ENTITY,(nested,entity,version)-> {protect(entity,0,0,false);return entity;});
    }

    /** Called by FMLServerAboutToStartEvent, before loadAllWorlds on both sides. */
    public static UpgradeJournal prepare(Path world) throws IOException {
        UpgradeJournal complete=UpgradeJournal.scan(world);
        journal=complete;return complete;
    }
    public static void close() {journal=null;}

    private static NBTTagCompound repairContext(NBTTagCompound chunk) {
        if(journal==null || chunk.getBoolean(CONTEXT)) return chunk;
        NBTTagCompound level=chunk.getCompoundTag("Level");
        NBTTagList entities=level.getTagList("Entities",10);
        int source=-1;
        try {
            for(int i=0;i<entities.tagCount();i++) {
                NBTTagCompound entity=entities.getCompoundTagAt(i);
                if(!UpgradeJournal.corpse(entity) || !entity.hasUniqueId("UUID")) continue;
                NBTTagCompound original=journal.originalFor(entity.getUniqueId("UUID"),level.getInteger("xPos"),level.getInteger("zPos"));
                if(original!=null) source=Math.max(source,original.getCompoundTag("SourceVersions").getInteger("DataVersion"));
            }
        } catch(IOException damaged) {throw new IllegalStateException("Chunk preservation could not be verified",damaged);}
        int stamped=chunk.hasKey("DataVersion",99) ? chunk.getInteger("DataVersion") : -1;
        NBTTagCompound versions=chunk.getCompoundTag("ForgeDataVersion");
        if(versions.hasKey("minecraft",99)) stamped=versions.getInteger("minecraft");
        if(source<0 || stamped<=source) return chunk;

        // A preserved legacy corpse proves this chunk predates the falsely high
        // Minecraft stamp. Repair only that context. Retain the current compound
        // so an earlier mod's fixes are not undone, and mark other mods current
        // in the nested pass so their fixers are not applied a second time.
        NBTTagCompound corrected=chunk.copy();
        pipeline.writeVersionData(corrected);
        corrected.setInteger("DataVersion",source);
        corrected.getCompoundTag("ForgeDataVersion").setInteger("minecraft",source);
        corrected.setBoolean(CONTEXT,true);
        NBTTagCompound result=pipeline.process(FixTypes.CHUNK,corrected);
        result.removeTag(CONTEXT);
        return result;
    }

    private static void protect(NBTTagCompound entity,int chunkX,int chunkZ,boolean checkChunk) {
        if(!UpgradeJournal.corpse(entity)) return;
        if(entity.hasKey(PAYLOAD,10)) return;
        try {
            NBTTagCompound original=journal==null || !entity.hasUniqueId("UUID") ? null : checkChunk ?
                    journal.originalFor(entity.getUniqueId("UUID"),chunkX,chunkZ) : journal.originalFor(entity.getUniqueId("UUID"));
            if(original==null) {
                // Direct entity templates have no region to preserve. World chunks
                // must always have passed the complete preflight boundary.
                if(checkChunk) throw new IllegalStateException("Unpreserved corpse in saved chunk; refusing unsafe conversion");
                entity.setString("id","lootablebodies:corpse");return;
            }
            NBTTagCompound safe=original.getCompoundTag("Entity").copy();
            boolean migrated=safe.hasKey(VERSION,3) && safe.getInteger(VERSION)==1;
            safe.setString("id","lootablebodies:corpse");safe.setInteger(VERSION,1);
            if(!migrated) safe.setTag(SOURCES,original.getCompoundTag("SourceVersions").copy());
            safe.setBoolean(ITEMS_PENDING,!migrated);
            entity.setString("id","lootablebodies:corpse");entity.setInteger(VERSION,1);
            // This envelope is transient. Vanilla never sees its Equipment list;
            // EntityLootableBody consumes it before its normal NBT reader runs.
            entity.setTag(PAYLOAD,safe);entity.removeTag("Equipment");
        } catch(IOException damaged) {throw new IllegalStateException("Corpse preservation could not be verified",damaged);}
    }
    public static NBTTagCompound restore(NBTTagCompound input) {
        if(input.hasKey(PAYLOAD,10)) {
            NBTTagCompound saved=input.getCompoundTag(PAYLOAD);
            if(saved.hasKey(VERSION,3) && saved.getInteger(VERSION)==1 && UpgradeJournal.corpse(saved)) return saved.copy();
            throw new IllegalArgumentException("Invalid protected corpse payload");
        }
        return input;
    }
    public static void clearTransient(NBTTagCompound output) {
        output.removeTag(PAYLOAD);
        output.removeTag(ITEMS_PENDING);
    }
}
