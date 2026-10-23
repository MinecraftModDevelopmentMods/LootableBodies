package cyano.lootable.buildtest;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import cyano.lootable.entities.EntityLootableBody;

/** Real Forge data-fixer gates. Synthetic inputs complement sealed old-jar worlds. */
final class UpgradeChecks {
    static void registerOwnFixer() {
        if(!System.getProperty("lootablebodies.phase","").equals("upgrade-contracts")) return;
        net.minecraftforge.common.util.ModFixs own=FMLCommonHandler.instance().getDataFixer().init("lootablebodiesbuildtest",1);
        own.registerFix(FixTypes.ITEM_INSTANCE,new net.minecraft.util.datafix.IFixableData() {
            public int getFixVersion(){return 1;}
            public NBTTagCompound fixTagCompound(NBTTagCompound item){NBTTagCompound tag=item.getCompoundTag("tag");if(tag.hasKey("upgrade_counter",3))tag.setInteger("upgrade_counter",tag.getInteger("upgrade_counter")+1);return item;}
        });
    }
    private interface Check { void run() throws Exception; }
    private static int failures,sequence;
    private static File output;
    private static WorldServer world;
    static void run(MinecraftServer server) throws Exception {
        failures=0;sequence=0;world=server.getWorld(0);
        output=new File(world.getSaveHandler().getWorldDirectory().getParentFile(),"upgrade-contract-fixtures");
        check("legacy_id_before_entity_construction",()->conversion("lootablebodies.Corpse",new NBTTagInt(512)));
        check("native_111_identity_survives",()->conversion("lootablebodies:corpse",new NBTTagInt(922)));
        check("missing_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",null));
        check("wrong_type_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",new NBTTagString("damaged")));
        check("nonfinite_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",new NBTTagDouble(Double.NaN)));
        check("negative_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",new NBTTagInt(-1)));
        check("incorrect_new_stamp_does_not_skip_legacy_items",()->conversion("lootablebodies.Corpse",new NBTTagInt(922)));
        check("unknown_items_keep_source_context",UpgradeChecks::missingItem);
        check("repeated_fixing_and_loading_never_duplicates",UpgradeChecks::repeated);
        check("old_recovery_payload_is_not_mutated",UpgradeChecks::recovery);
        check("full_save_does_not_reapply_original_item_versions",UpgradeChecks::savedCounter);
        check("damaged_stamp_on_migrated_corpse_preserves_inventory",UpgradeChecks::migratedDamagedStamp);
        check("forged_mod_stamp_does_not_bypass_preservation",UpgradeChecks::forgedModStamp);
        check("reused_output_compound_cannot_resurrect_old_payload",UpgradeChecks::reusedOutput);
        check("all_current_stamps_do_not_hide_legacy_identity",UpgradeChecks::allCurrentStamps);
        check("falsely_current_stamps_still_convert_vanilla_container_items",UpgradeChecks::vanillaFalseStamp);
        check("native112_bed_colour_is_preserved",()->bed(1343,0,0));
        check("maintained111_still_receives112_item_fixers",()->bed(922,1,14));
        check("current112_marker_never_reapplies_older_item_fixers",()->bed(1343,2,0));
        check("legacy_xp_sound_remaps_without_ignoring_unrelated_entries",UpgradeChecks::soundMapping);
        check("unstamped_native112_chunk_keeps_white_bed_in_vanilla_chest",()->nativeChest(false));
        check("legacy_corpse_does_not_downgrade_native112_chunk_items",()->nativeChest(true));
        check("bed_with_no_source_evidence_stays_raw_and_retriable",UpgradeChecks::ambiguousBed);
        System.out.println("LOOTABLE_BODIES_UPGRADE_CONTRACT_RESULT checks=23 failures="+failures);
        RuntimeChecks.require(failures==0,"Upgrade contracts: "+failures);
    }
    private static void check(String name,Check check) {
        try {check.run();System.out.println("UPGRADE_CONTRACT_PASS "+name);}
        catch(Throwable error){failures++;System.out.println("UPGRADE_CONTRACT_FAIL "+name+": "+error);error.printStackTrace();}
    }
    private static void nativeChest(boolean legacy) throws Exception {
        NBTTagCompound entity=body(legacy?"lootablebodies.Corpse":"lootablebodies:corpse");
        if(!legacy)entity.getTagList("Equipment",10).getCompoundTagAt(40).getCompoundTag("tag").getCompoundTag("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound input=chunk(entity,legacy?new NBTTagInt(1343):null);
        NBTTagCompound chest=new NBTTagCompound();chest.setString("id","minecraft:chest");chest.setInteger("x",2);chest.setInteger("y",80);chest.setInteger("z",2);
        NBTTagCompound bed=new NBTTagCompound();bed.setString("id","minecraft:bed");bed.setByte("Count",(byte)1);bed.setShort("Damage",(short)0);bed.setByte("Slot",(byte)0);
        NBTTagList items=new NBTTagList();items.appendTag(bed);chest.setTag("Items",items);NBTTagList tiles=new NBTTagList();tiles.appendTag(chest);input.getCompoundTag("Level").setTag("TileEntities",tiles);
        NBTTagCompound fixed=processChunk(input,"1.12.2");
        NBTTagCompound actual=fixed.getCompoundTag("Level").getTagList("TileEntities",10).getCompoundTagAt(0).getTagList("Items",10).getCompoundTagAt(0);
        RuntimeChecks.require(NameLifetimeChecks.same(bed,actual),"Native white bed changed under chunk conversion: "+actual);
        NBTTagCompound converted=saved(fixed.getCompoundTag("Level").getTagList("Entities",10).getCompoundTagAt(0));
        RuntimeChecks.require(inventory(converted).size()==48,"Legacy/native corpse inventory changed while correcting vanilla context");
    }
    private static void ambiguousBed() throws Exception {
        NBTTagCompound input=body("lootablebodies:corpse"),item=new NBTTagCompound();item.setString("id","minecraft:bed");item.setByte("Count",(byte)1);item.setShort("Damage",(short)0);item.setByte("Slot",(byte)6);
        NBTTagList items=new NBTTagList();items.appendTag(item);input.setTag("Equipment",items);
        NBTTagCompound once=saved(process(chunk(input,null))),record=once.getTagList("LootableBodiesUnresolved",10).getCompoundTagAt(0);
        RuntimeChecks.require(once.getTagList("LootableBodiesUnresolved",10).tagCount()==1 && NameLifetimeChecks.same(item,record.getTag("Data")) && record.getCompoundTag("SourceVersions").getBoolean("Ambiguous"),"Ambiguous bed was guessed or lost");
        EntityLootableBody again=new EntityLootableBody(world);again.readEntityFromNBT(once);
        RuntimeChecks.require(!again.isEmpty(),"Raw recoverable bed allowed empty-corpse decay");
        NBTTagCompound twice=new NBTTagCompound();again.writeEntityToNBT(twice);
        RuntimeChecks.require(NameLifetimeChecks.same(once.getTag("LootableBodiesUnresolved"),twice.getTag("LootableBodiesUnresolved")),"Ambiguous bed duplicated or changed during reload");
    }
    private static void soundMapping() throws Exception {
        net.minecraftforge.registries.IForgeRegistry<net.minecraft.util.SoundEvent> registry=net.minecraftforge.fml.common.registry.ForgeRegistries.SOUND_EVENTS;
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<net.minecraft.util.SoundEvent> old=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("minecraft:entity.experience_orb.touch"),196);
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<net.minecraft.util.SoundEvent> other=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("absentfixture:other_sound"),197);
        net.minecraftforge.event.RegistryEvent.MissingMappings<net.minecraft.util.SoundEvent> event=new net.minecraftforge.event.RegistryEvent.MissingMappings<>(new net.minecraft.util.ResourceLocation("minecraft:soundevents"),registry,Arrays.asList(old,other));
        Class.forName("cyano.lootable.upgrade.LegacyRegistryMappings").getMethod("sounds",net.minecraftforge.event.RegistryEvent.MissingMappings.class).invoke(null,event);
        RuntimeChecks.require(old.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.REMAP && old.getTarget()==registry.getValue(new net.minecraft.util.ResourceLocation("minecraft:entity.experience_orb.pickup")),"Known vanilla sound rename not remapped");
        RuntimeChecks.require(other.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.DEFAULT,"Unrelated missing mappings must retain Forge's normal handling");
    }
    private static void bed(int source,int marker,int damage) throws Exception {
        NBTTagCompound input=body("lootablebodies:corpse");
        input.getTagList("Equipment",10).getCompoundTagAt(40).getCompoundTag("tag").getCompoundTag("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound item=new NBTTagCompound();item.setString("id","minecraft:bed");item.setByte("Count",(byte)2);item.setShort("Damage",(short)0);item.setByte("Slot",(byte)41);input.getTagList("Equipment",10).set(41,item);
        if(marker>0)input.setInteger("LootableBodiesDataVersion",marker);
        NBTTagCompound result=saved(process(chunk(input,new NBTTagInt(source))));
        NBTTagList equipment=result.getTagList("Equipment",10);NBTTagCompound found=null;
        for(int i=0;i<equipment.tagCount();i++)if(equipment.getCompoundTagAt(i).getByte("Slot")==41)found=equipment.getCompoundTagAt(i);
        int count=0;
        for(String key:Arrays.asList("Equipment","Aux")) {
            NBTTagList items=result.getTagList(key,10);
            for(int i=0;i<items.tagCount();i++) {
                NBTTagCompound entry=items.getCompoundTagAt(i);
                if(entry.getString("id").equals("minecraft:bed")) {
                    RuntimeChecks.require(entry.getShort("Damage")==damage,"Bed colour changed: "+entry);
                    count+=entry.getByte("Count")&255;
                }
            }
        }
        RuntimeChecks.require(found!=null && found.getString("id").equals("minecraft:bed") && found.getShort("Damage")==damage && found.getByte("Count")==1 && count==2,"Bed count/slot/overflow changed: "+result);
    }
    private static NBTTagCompound body(String identity) {
        NBTTagCompound entity=new NBTTagCompound();new EntityLootableBody(world).writeToNBTOptional(entity);
        entity.removeTag("LootableBodiesDataVersion");entity.removeTag("LootableBodiesSourceVersions");entity.setString("id",identity);
        entity.setUniqueId("UUID",UUID.fromString("00000000-1111-4111-8111-000000000111"));
        NBTTagList equipment=new NBTTagList();
        for(int i=0;i<48;i++){NBTTagCompound item=RuntimeChecks.stack(i).writeToNBT(new NBTTagCompound());item.setByte("Slot",(byte)i);equipment.appendTag(item);}
        // A vanilla 1.10 spawn egg needs the 1.11 entity-name item conversion.
        NBTTagCompound egg=new NBTTagCompound();egg.setString("id","minecraft:spawn_egg");egg.setByte("Count",(byte)3);egg.setShort("Damage",(short)0);egg.setByte("Slot",(byte)40);
        NBTTagCompound tag=new NBTTagCompound(),pig=new NBTTagCompound();pig.setString("id","Pig");pig.setString("CustomName","Saved pig");tag.setTag("EntityTag",pig);tag.setString("keepsake","old egg");egg.setTag("tag",tag);
        equipment.set(40,egg);entity.setTag("Equipment",equipment);entity.setString("Name","Saved Owner");entity.setDouble("FallDistance",Double.NaN);return entity;
    }
    private static NBTTagCompound chunk(NBTTagCompound entity,NBTBase version) {
        NBTTagCompound root=new NBTTagCompound(),level=new NBTTagCompound();root.setTag("Level",level);
        if(version!=null)root.setTag("DataVersion",version.copy());
        level.setInteger("xPos",0);level.setInteger("zPos",0);NBTTagList entities=new NBTTagList();entities.appendTag(entity);level.setTag("Entities",entities);return root;
    }
    private static NBTTagCompound process(NBTTagCompound source) throws Exception {
        return processChunk(source).getCompoundTag("Level").getTagList("Entities",10).getCompoundTagAt(0);
    }
    private static NBTTagCompound processChunk(NBTTagCompound source) throws Exception {return processChunk(source,null);}
    private static NBTTagCompound processChunk(NBTTagCompound source,String minecraft) throws Exception {
        Path path=new File(output,"case-"+(sequence++)).toPath();Files.createDirectories(path.resolve("region"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(source,data);}
        int sectors=(bytes.size()+5+4095)/4096;java.nio.ByteBuffer region=java.nio.ByteBuffer.allocate(8192+sectors*4096);region.putInt((2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());Files.write(path.resolve("region/r.0.0.mca"),region.array());
        if(minecraft!=null) {
            NBTTagCompound root=new NBTTagCompound(),fml=new NBTTagCompound(),mc=new NBTTagCompound(),mod=new NBTTagCompound();
            mc.setString("ModId","minecraft");mc.setString("ModVersion",minecraft);mod.setString("ModId","lootablebodies");mod.setString("ModVersion","2.4.0");NBTTagList mods=new NBTTagList();mods.appendTag(mc);mods.appendTag(mod);fml.setTag("ModList",mods);root.setTag("FML",fml);root.setTag("Data",new NBTTagCompound());
            try(OutputStream file=Files.newOutputStream(path.resolve("level.dat"))){CompressedStreamTools.writeCompressed(root,file);}
        }
        Class<?> preservation=null;Object previous=null;Field active=null;
        try {
            // The red run exercises the real unprotected Forge pipeline. Once
            // implemented, activate the same preflight used before world loading.
            try {preservation=Class.forName("cyano.lootable.upgrade.UpgradePreservation");active=preservation.getDeclaredField("journal");active.setAccessible(true);previous=active.get(null);preservation.getMethod("prepare",Path.class).invoke(null,path);}
            catch(ClassNotFoundException beforeImplementation) { }
            NBTTagCompound input=source.copy();
            if(preservation!=null)try{input=(NBTTagCompound)preservation.getMethod("chunkContext",NBTTagCompound.class).invoke(null,input);}catch(NoSuchMethodException beforeBoundaryFix){}
            NBTTagCompound result=FMLCommonHandler.instance().getDataFixer().process(FixTypes.CHUNK,input);
            if(preservation!=null)try{preservation.getMethod("clearChunkContext",NBTTagCompound.class).invoke(null,result);}catch(NoSuchMethodException beforeBoundaryFix){}
            return result;
        } finally {if(active!=null)active.set(null,previous);}
    }
    private static NBTTagCompound saved(NBTTagCompound fixed) {
        RuntimeChecks.require(fixed.getString("id").equals("lootablebodies:corpse"),"legacy identity was not converted before construction");
        Entity entity=net.minecraft.entity.EntityList.createEntityFromNBT(fixed.copy(),world);
        RuntimeChecks.require(entity instanceof EntityLootableBody,"corpse factory returned no body");
        NBTTagCompound saved=new NBTTagCompound();((EntityLootableBody)entity).writeEntityToNBT(saved);return saved;
    }
    private static Map<String,Integer> inventory(NBTTagCompound body) {
        Map<String,Integer> counts=new TreeMap<>();for(String key:Arrays.asList("Equipment","Aux")) {
            NBTTagList list=body.getTagList(key,10);for(int i=0;i<list.tagCount();i++){NBTTagCompound item=list.getCompoundTagAt(i).copy();int count=item.getByte("Count")&255;item.removeTag("Count");item.removeTag("Slot");String id=item.toString();counts.put(id,counts.getOrDefault(id,0)+count);}
        }return counts;
    }
    private static void conversion(String identity,NBTBase version) throws Exception {
        NBTTagCompound input=body(identity),expected=input.copy();
        if(identity.equals("lootablebodies:corpse"))input.getTagList("Equipment",10).getCompoundTagAt(40).getCompoundTag("tag").getCompoundTag("EntityTag").setString("id","minecraft:pig");
        expected.getTagList("Equipment",10).getCompoundTagAt(40).getCompoundTag("tag").getCompoundTag("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound output=saved(process(chunk(input,version)));
        RuntimeChecks.require(inventory(expected).equals(inventory(output)),"complete inventory or vanilla spawn-egg conversion changed");
        RuntimeChecks.require(output.getInteger("LootableBodiesDataVersion")==2,"missing per-corpse migration marker");
        RuntimeChecks.require(output.getTagList("LootableBodiesInvalidFields",10).tagCount()>0,"malformed original fields were lost in vanilla fixing");
    }
    private static void missingItem() throws Exception {
        NBTTagCompound body=body("lootablebodies.Corpse"),raw=body.getTagList("Equipment",10).getCompoundTagAt(20);raw.setString("id","absentfixture:keepsake");
        NBTTagCompound result=saved(process(chunk(body,new NBTTagInt(512))));NBTTagList recovery=result.getTagList("LootableBodiesUnresolved",10);
        RuntimeChecks.require(recovery.tagCount()==1,"unavailable item not retained exactly once");NBTTagCompound record=recovery.getCompoundTagAt(0);
        RuntimeChecks.require(NameLifetimeChecks.same(raw,record.getTag("Data")) && record.getCompoundTag("SourceVersions").getInteger("DataVersion")==512,"unavailable item lost its original payload or source context");
    }
    private static void repeated() throws Exception {
        NBTTagCompound raw=body("lootablebodies.Corpse"),fixed=process(chunk(raw,new NBTTagInt(512))),once=saved(fixed);
        NBTTagCompound again=FMLCommonHandler.instance().getDataFixer().process(FixTypes.CHUNK,chunk(fixed.copy(),new NBTTagInt(512))).getCompoundTag("Level").getTagList("Entities",10).getCompoundTagAt(0);
        NBTTagCompound twice=saved(again);RuntimeChecks.require(inventory(once).equals(inventory(twice)),"repeated data fixing duplicated or lost items");
    }
    private static void recovery() throws Exception {
        NBTTagCompound body=body("lootablebodies.Corpse"),raw=body.getTagList("Equipment",10).getCompoundTagAt(20).copy();raw.setString("id","absentfixture:keepsake");
        NBTTagCompound record=new NBTTagCompound();record.setString("Source","Aux");record.setTag("Data",raw.copy());NBTTagList records=new NBTTagList();records.appendTag(record);body.setTag("LootableBodiesUnresolved",records);
        NBTTagCompound output=saved(process(chunk(body,new NBTTagInt(512))));NBTTagCompound kept=output.getTagList("LootableBodiesUnresolved",10).getCompoundTagAt(0);
        RuntimeChecks.require(kept.getString("Source").equals("Aux") && NameLifetimeChecks.same(raw,kept.getTag("Data")),"existing Source/Data payload changed");
        RuntimeChecks.require(kept.getCompoundTag("SourceVersions").getInteger("DataVersion")==512,"existing recovery extension lost source version context");
    }
    private static NBTTagCompound fullSave(NBTTagCompound fixed) {
        Entity entity=net.minecraft.entity.EntityList.createEntityFromNBT(fixed.copy(),world);RuntimeChecks.require(entity instanceof EntityLootableBody,"Missing body for full save");
        NBTTagCompound result=new NBTTagCompound();entity.writeToNBTOptional(result);return result;
    }
    private static void savedCounter() throws Exception {
        NBTTagCompound source=body("lootablebodies.Corpse");source.getTagList("Equipment",10).getCompoundTagAt(20).getCompoundTag("tag").setInteger("upgrade_counter",0);
        NBTTagCompound once=fullSave(process(chunk(source,new NBTTagInt(512))));
        RuntimeChecks.require(once.getTagList("Equipment",10).getCompoundTagAt(20).getCompoundTag("tag").getInteger("upgrade_counter")==1,"Item fixer did not run exactly once on first load");
        NBTTagCompound twice=fullSave(process(chunk(once,new NBTTagInt(922))));RuntimeChecks.require(inventory(once).equals(inventory(twice)),"Old item version was applied again after saving migrated items");
    }
    private static NBTTagCompound currentStamps(NBTTagCompound chunk) {FMLCommonHandler.instance().getDataFixer().writeVersionData(chunk);chunk.getCompoundTag("ForgeDataVersion").setInteger("minecraft",-1);return chunk;}
    private static void migratedDamagedStamp() throws Exception {
        NBTTagCompound source=body("lootablebodies:corpse");source.setInteger("LootableBodiesDataVersion",1);source.getTagList("Equipment",10).getCompoundTagAt(40).getCompoundTag("tag").getCompoundTag("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound result=saved(process(currentStamps(chunk(source,null))));RuntimeChecks.require(inventory(source).equals(inventory(result)),"Damaged version stamp truncated an already migrated Equipment list");
    }
    private static void forgedModStamp() throws Exception {
        NBTTagCompound source=body("lootablebodies.Corpse"),expected=source.copy();expected.getTagList("Equipment",10).getCompoundTagAt(40).getCompoundTag("tag").getCompoundTag("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound input=currentStamps(chunk(source,new NBTTagInt(512)));input.getCompoundTag("ForgeDataVersion").setInteger("minecraft",512);
        RuntimeChecks.require(inventory(expected).equals(inventory(saved(process(input)))),"Incorrect mod stamp bypassed custom inventory preservation");
    }
    private static void reusedOutput() throws Exception {
        NBTTagCompound fixed=process(chunk(body("lootablebodies.Corpse"),new NBTTagInt(512)));
        EntityLootableBody entity=(EntityLootableBody)net.minecraft.entity.EntityList.createEntityFromNBT(fixed.copy(),world);entity.clear();entity.writeEntityToNBT(fixed);
        EntityLootableBody reloaded=(EntityLootableBody)net.minecraft.entity.EntityList.createEntityFromNBT(fixed.copy(),world);
        RuntimeChecks.require(reloaded.isEmpty(),"Reused output compound resurrected a consumed upgrade payload");
    }
    private static void allCurrentStamps() throws Exception {
        NBTTagCompound input=chunk(body("lootablebodies.Corpse"),new NBTTagInt(922)),versions=new NBTTagCompound();
        versions.setInteger("minecraft",922);versions.setInteger("lootablebodies",1);versions.setInteger("lootablebodiesbuildtest",1);input.setTag("ForgeDataVersion",versions);
        saved(process(input));
    }
    private static void vanillaFalseStamp() throws Exception {
        NBTTagCompound input=chunk(body("lootablebodies.Corpse"),new NBTTagInt(922)),versions=new NBTTagCompound();
        versions.setInteger("minecraft",922);versions.setInteger("lootablebodies",1);versions.setInteger("lootablebodiesbuildtest",1);input.setTag("ForgeDataVersion",versions);
        NBTTagCompound chest=new NBTTagCompound();chest.setString("id","Chest");chest.setInteger("x",2);chest.setInteger("y",80);chest.setInteger("z",2);
        NBTTagCompound banner=new NBTTagCompound();banner.setString("id","minecraft:banner");banner.setByte("Count",(byte)2);banner.setShort("Damage",(short)0);banner.setByte("Slot",(byte)0);
        NBTTagCompound tag=new NBTTagCompound(),block=new NBTTagCompound();block.setInteger("Base",5);tag.setTag("BlockEntityTag",block);banner.setTag("tag",tag);NBTTagList items=new NBTTagList();items.appendTag(banner);chest.setTag("Items",items);NBTTagList tiles=new NBTTagList();tiles.appendTag(chest);input.getCompoundTag("Level").setTag("TileEntities",tiles);
        NBTTagCompound fixed=processChunk(input).getCompoundTag("Level").getTagList("TileEntities",10).getCompoundTagAt(0).getTagList("Items",10).getCompoundTagAt(0);
        RuntimeChecks.require(fixed.getShort("Damage")==5 && fixed.getByte("Count")==2 && !fixed.getCompoundTag("tag").getCompoundTag("BlockEntityTag").hasKey("Base"),"False version stamps skipped the vanilla banner conversion");
    }
}
