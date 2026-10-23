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
import net.minecraft.util.datafix.TypeReferences;
import cyano.lootable.upgrade.UpgradePreservation;
import net.minecraft.world.WorldServer;

import cyano.lootable.entities.EntityLootableBody;

/** Real Forge data-fixer gates. Synthetic inputs complement sealed old-jar worlds. */
final class UpgradeChecks {
    static void registerOwnFixer() {
        final com.mojang.datafixers.DataFixer nativeFixer=net.minecraft.util.datafix.DataFixesManager.getDataFixer();
        UpgradePreservation.register(new com.mojang.datafixers.DataFixer() {
            public com.mojang.datafixers.schemas.Schema getSchema(int key){return nativeFixer.getSchema(key);}
            public <T>com.mojang.datafixers.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.datafixers.Dynamic<T> input,int source,int target) {
                com.mojang.datafixers.Dynamic<T> result=nativeFixer.update(type,input,source,target);
                if(type.typeName().equals("item_stack") && source<target && target==1631 && result.getValue() instanceof NBTTagCompound) {
                    NBTTagCompound tag=((NBTTagCompound)result.getValue()).getCompound("tag");if(tag.contains("upgrade_counter",3))tag.setInt("upgrade_counter",tag.getInt("upgrade_counter")+1);
                }
                return result;
            }
        });
    }
    private static NBTTagCompound nativeChunk(NBTTagCompound input) {
        return NBTUtil.update(UpgradePreservation.wrapper(world.getServer().getDataFixer()),TypeReferences.CHUNK,input,input.getInt("DataVersion"),1631);
    }
    private interface Check { void run() throws Exception; }
    private static int failures,sequence;
    private static File output;
    private static WorldServer world;
    static void run(MinecraftServer server) throws Exception {
        failures=0;sequence=0;world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));registerOwnFixer();
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
        if(!legacy)entity.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound input=chunk(entity,legacy?new NBTTagInt(1343):null);
        NBTTagCompound chest=new NBTTagCompound();chest.setString("id","minecraft:chest");chest.setInt("x",2);chest.setInt("y",80);chest.setInt("z",2);
        NBTTagCompound bed=new NBTTagCompound();bed.setString("id","minecraft:bed");bed.setByte("Count",(byte)1);bed.setShort("Damage",(short)0);bed.setByte("Slot",(byte)0);
        NBTTagList items=new NBTTagList();items.add(bed);chest.setTag("Items",items);NBTTagList tiles=new NBTTagList();tiles.add(chest);input.getCompound("Level").setTag("TileEntities",tiles);
        NBTTagCompound fixed=processChunk(input,"1.12.2");
        NBTTagCompound actual=fixed.getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        NBTTagCompound expected=bed.copy();expected.setString("id","minecraft:white_bed");expected.removeTag("Damage");
        RuntimeChecks.require(NameLifetimeChecks.same(expected,actual),"Native white bed changed under chunk conversion: "+actual);
        NBTTagCompound converted=saved(fixed.getCompound("Level").getList("Entities",10).getCompound(0));
        RuntimeChecks.require(inventory(converted).size()==48,"Legacy/native corpse inventory changed while correcting vanilla context");
    }
    private static void ambiguousBed() throws Exception {
        NBTTagCompound input=body("lootablebodies:corpse"),item=new NBTTagCompound();item.setString("id","minecraft:bed");item.setByte("Count",(byte)1);item.setShort("Damage",(short)0);item.setByte("Slot",(byte)6);
        NBTTagList items=new NBTTagList();items.add(item);input.setTag("Equipment",items);
        NBTTagCompound once=saved(process(chunk(input,null))),record=once.getList("LootableBodiesUnresolved",10).getCompound(0);
        RuntimeChecks.require(once.getList("LootableBodiesUnresolved",10).size()==1 && NameLifetimeChecks.same(item,record.getTag("Data")) && record.getCompound("SourceVersions").getBoolean("Ambiguous"),"Ambiguous bed was guessed or lost");
        EntityLootableBody again=new EntityLootableBody(world);again.readAdditional(once);
        RuntimeChecks.require(!again.isEmpty(),"Raw recoverable bed allowed empty-corpse decay");
        NBTTagCompound twice=new NBTTagCompound();again.writeAdditional(twice);
        RuntimeChecks.require(NameLifetimeChecks.same(once.getTag("LootableBodiesUnresolved"),twice.getTag("LootableBodiesUnresolved")),"Ambiguous bed duplicated or changed during reload");
    }
    private static void soundMapping() throws Exception {
        net.minecraftforge.registries.IForgeRegistry<net.minecraft.util.SoundEvent> registry=net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS;
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<net.minecraft.util.SoundEvent> old=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("minecraft:entity.experience_orb.touch"),196);
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<net.minecraft.util.SoundEvent> other=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("absentfixture:other_sound"),197);
        net.minecraftforge.event.RegistryEvent.MissingMappings<net.minecraft.util.SoundEvent> event=new net.minecraftforge.event.RegistryEvent.MissingMappings<>(new net.minecraft.util.ResourceLocation("minecraft:soundevents"),registry,Arrays.asList(old,other));
        Class.forName("cyano.lootable.upgrade.LegacyRegistryMappings").getMethod("sounds",net.minecraftforge.event.RegistryEvent.MissingMappings.class).invoke(null,event);
        RuntimeChecks.require(old.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.REMAP && old.getTarget()==registry.getValue(new net.minecraft.util.ResourceLocation("minecraft:entity.experience_orb.pickup")),"Known vanilla sound rename not remapped");
        RuntimeChecks.require(other.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.DEFAULT,"Unrelated missing mappings must retain Forge's normal handling");
    }
    private static void bed(int source,int marker,int damage) throws Exception {
        NBTTagCompound input=body("lootablebodies:corpse");
        input.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound item=new NBTTagCompound();item.setString("id","minecraft:bed");item.setByte("Count",(byte)2);item.setShort("Damage",(short)0);item.setByte("Slot",(byte)41);input.getList("Equipment",10).set(41,item);
        if(marker>0)input.setInt("LootableBodiesDataVersion",marker);
        NBTTagCompound result=saved(process(chunk(input,new NBTTagInt(source))));
        NBTTagList equipment=result.getList("Equipment",10);NBTTagCompound found=null;
        for(int i=0;i<equipment.size();i++)if(equipment.getCompound(i).getByte("Slot")==41)found=equipment.getCompound(i);
        int count=0;
        for(String key:Arrays.asList("Equipment","Aux")) {
            NBTTagList items=result.getList(key,10);
            for(int i=0;i<items.size();i++) {
                NBTTagCompound entry=items.getCompound(i);
                if(entry.getString("id").equals(damage==14?"minecraft:red_bed":"minecraft:white_bed")) {
                    RuntimeChecks.require(!entry.hasKey("Damage"),"Bed retained obsolete damage: "+entry);
                    count+=entry.getByte("Count")&255;
                }
            }
        }
        RuntimeChecks.require(found!=null && found.getString("id").equals(damage==14?"minecraft:red_bed":"minecraft:white_bed") && found.getByte("Count")==1 && count==2,"Bed count/slot/overflow changed");
    }
    private static NBTTagCompound body(String identity) {
        NBTTagCompound entity=new NBTTagCompound();new EntityLootableBody(world).writeUnlessPassenger(entity);
        entity.removeTag("LootableBodiesDataVersion");entity.removeTag("LootableBodiesSourceVersions");entity.setString("id",identity);
        entity.setUniqueId("UUID",UUID.fromString("00000000-1111-4111-8111-000000000111"));
        NBTTagList equipment=new NBTTagList();
        // Deliberate old-format synthetic data, not a native stack mislabeled
        // as 1.10. Sealed old-writer worlds provide the historical qualification.
        for(int i=0;i<48;i++) {
            NBTTagCompound item=new NBTTagCompound(),tag=new NBTTagCompound(),display=new NBTTagCompound();
            item.setString("id","minecraft:stone");item.setByte("Count",(byte)(i%32+1));item.setShort("Damage",(short)(i%2));item.setByte("Slot",(byte)i);
            display.setString("Name","Fixture "+i);tag.setTag("display",display);tag.setInt("typed",i);item.setTag("tag",tag);equipment.add(item);
        }
        // A vanilla 1.10 spawn egg needs the 1.11 entity-name item conversion.
        NBTTagCompound egg=new NBTTagCompound();egg.setString("id","minecraft:spawn_egg");egg.setByte("Count",(byte)3);egg.setShort("Damage",(short)0);egg.setByte("Slot",(byte)40);
        NBTTagCompound tag=new NBTTagCompound(),pig=new NBTTagCompound();pig.setString("id","Pig");pig.setString("CustomName","Saved pig");tag.setTag("EntityTag",pig);tag.setString("keepsake","old egg");egg.setTag("tag",tag);
        equipment.set(40,egg);entity.setTag("Equipment",equipment);entity.setString("Name","Saved Owner");entity.setDouble("FallDistance",Double.NaN);return entity;
    }
    private static NBTTagCompound chunk(NBTTagCompound entity,INBTBase version) {
        NBTTagCompound root=new NBTTagCompound(),level=new NBTTagCompound();root.setTag("Level",level);
        if(version!=null)root.setTag("DataVersion",version.copy());
        level.setInt("xPos",0);level.setInt("zPos",0);NBTTagList entities=new NBTTagList();entities.add(entity);level.setTag("Entities",entities);return root;
    }
    private static NBTTagCompound process(NBTTagCompound source) throws Exception {
        return processChunk(source).getCompound("Level").getList("Entities",10).getCompound(0);
    }
    private static NBTTagCompound processChunk(NBTTagCompound source) throws Exception {return processChunk(source,null);}
    private static NBTTagCompound processChunk(NBTTagCompound source,String minecraft) throws Exception {
        Path path=new File(output,"case-"+(sequence++)).toPath();Files.createDirectories(path.resolve("region"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(source,data);}
        int sectors=(bytes.size()+5+4095)/4096;java.nio.ByteBuffer region=java.nio.ByteBuffer.allocate(8192+sectors*4096);region.putInt((2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());Files.write(path.resolve("region/r.0.0.mca"),region.array());
        if(minecraft!=null) {
            NBTTagCompound root=new NBTTagCompound(),fml=new NBTTagCompound(),mc=new NBTTagCompound(),mod=new NBTTagCompound();
            mc.setString("ModId","minecraft");mc.setString("ModVersion",minecraft);mod.setString("ModId","lootablebodies");mod.setString("ModVersion","2.4.0");NBTTagList mods=new NBTTagList();mods.add(mc);mods.add(mod);fml.setTag("ModList",mods);root.setTag("FML",fml);root.setTag("Data",new NBTTagCompound());
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
            NBTTagCompound result=nativeChunk(input);
            if(preservation!=null)try{preservation.getMethod("clearChunkContext",NBTTagCompound.class).invoke(null,result);}catch(NoSuchMethodException beforeBoundaryFix){}
            return result;
        } finally {if(active!=null)active.set(null,previous);}
    }
    private static NBTTagCompound saved(NBTTagCompound fixed) {
        RuntimeChecks.require(fixed.getString("id").equals("lootablebodies:corpse"),"legacy identity was not converted before construction");
        Entity entity=net.minecraft.entity.EntityType.create(fixed.copy(),world);
        RuntimeChecks.require(entity instanceof EntityLootableBody,"corpse factory returned no body");
        NBTTagCompound saved=new NBTTagCompound();((EntityLootableBody)entity).writeAdditional(saved);return saved;
    }
    private static Map<String,Integer> inventory(NBTTagCompound body) {
        Map<String,Integer> counts=new TreeMap<>();for(String key:Arrays.asList("Equipment","Aux")) {
            NBTTagList list=body.getList(key,10);for(int i=0;i<list.size();i++){NBTTagCompound item=list.getCompound(i).copy();int count=item.getByte("Count")&255;item.removeTag("Count");item.removeTag("Slot");String id=TypedNbt.canonical(item);counts.put(id,counts.getOrDefault(id,0)+count);}
        }return counts;
    }
    private static void conversion(String identity,INBTBase version) throws Exception {
        NBTTagCompound input=body(identity);
        if(identity.equals("lootablebodies:corpse"))input.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound expected=input.copy();
        NBTTagCompound output=saved(process(chunk(input,version)));
        RuntimeChecks.require(expectedInventory(expected,identity.equals("lootablebodies.Corpse")?512:922).equals(inventory(output)),"complete inventory or vanilla spawn-egg conversion changed");
        RuntimeChecks.require(output.getInt("LootableBodiesDataVersion")==3,"missing per-corpse migration marker");
        RuntimeChecks.require(output.getList("LootableBodiesInvalidFields",10).size()>0,"malformed original fields were lost in vanilla fixing");
    }
    private static void missingItem() throws Exception {
        NBTTagCompound body=body("lootablebodies.Corpse"),raw=body.getList("Equipment",10).getCompound(20);raw.setString("id","absentfixture:keepsake");
        NBTTagCompound result=saved(process(chunk(body,new NBTTagInt(512))));NBTTagList recovery=result.getList("LootableBodiesUnresolved",10);
        RuntimeChecks.require(recovery.size()==1,"unavailable item not retained exactly once");NBTTagCompound record=recovery.getCompound(0);
        RuntimeChecks.require(NameLifetimeChecks.same(raw,record.getTag("Data")) && record.getCompound("SourceVersions").getInt("DataVersion")==512,"unavailable item lost its original payload or source context");
    }
    private static void repeated() throws Exception {
        NBTTagCompound raw=body("lootablebodies.Corpse"),fixed=process(chunk(raw,new NBTTagInt(512))),once=saved(fixed);
        NBTTagCompound again=nativeChunk(chunk(fixed.copy(),new NBTTagInt(512))).getCompound("Level").getList("Entities",10).getCompound(0);
        NBTTagCompound twice=saved(again);RuntimeChecks.require(inventory(once).equals(inventory(twice)),"repeated data fixing duplicated or lost items");
    }
    private static void recovery() throws Exception {
        NBTTagCompound body=body("lootablebodies.Corpse"),raw=body.getList("Equipment",10).getCompound(20).copy();raw.setString("id","absentfixture:keepsake");
        NBTTagCompound record=new NBTTagCompound();record.setString("Source","Aux");record.setTag("Data",raw.copy());NBTTagList records=new NBTTagList();records.add(record);body.setTag("LootableBodiesUnresolved",records);
        NBTTagCompound output=saved(process(chunk(body,new NBTTagInt(512))));NBTTagCompound kept=output.getList("LootableBodiesUnresolved",10).getCompound(0);
        RuntimeChecks.require(kept.getString("Source").equals("Aux") && NameLifetimeChecks.same(raw,kept.getTag("Data")),"existing Source/Data payload changed");
        RuntimeChecks.require(kept.getCompound("SourceVersions").getInt("DataVersion")==512,"existing recovery extension lost source version context");
    }
    private static NBTTagCompound fullSave(NBTTagCompound fixed) {
        Entity entity=net.minecraft.entity.EntityType.create(fixed.copy(),world);RuntimeChecks.require(entity instanceof EntityLootableBody,"Missing body for full save");
        NBTTagCompound result=new NBTTagCompound();entity.writeUnlessPassenger(result);return result;
    }
    private static void savedCounter() throws Exception {
        NBTTagCompound source=body("lootablebodies.Corpse");source.getList("Equipment",10).getCompound(20).getCompound("tag").setInt("upgrade_counter",0);
        NBTTagCompound once=fullSave(process(chunk(source,new NBTTagInt(512))));
        NBTTagCompound counted=null;
        for(INBTBase entry:once.getList("Equipment",10))if(((NBTTagCompound)entry).getByte("Slot")==20)counted=(NBTTagCompound)entry;
        RuntimeChecks.require(counted!=null && counted.getCompound("tag").getInt("upgrade_counter")==1,"Item fixer did not run exactly once on first load: "+counted);
        NBTTagCompound twice=fullSave(process(chunk(once,new NBTTagInt(922))));RuntimeChecks.require(inventory(once).equals(inventory(twice)),"Old item version was applied again after saving migrated items");
    }
    private static NBTTagCompound currentStamps(NBTTagCompound chunk) {chunk.setInt("DataVersion",1631);chunk.setTag("ForgeDataVersion",new NBTTagCompound());chunk.getCompound("ForgeDataVersion").setInt("minecraft",-1);return chunk;}
    private static void migratedDamagedStamp() throws Exception {
        NBTTagCompound source=body("lootablebodies:corpse");source.setInt("LootableBodiesDataVersion",1);source.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").setString("id","minecraft:pig");
        NBTTagCompound result=saved(process(currentStamps(chunk(source,null))));RuntimeChecks.require(expectedInventory(source,922).equals(inventory(result)),"Damaged version stamp truncated an already migrated Equipment list");
    }
    private static void forgedModStamp() throws Exception {
        NBTTagCompound source=body("lootablebodies.Corpse"),expected=source.copy();
        NBTTagCompound input=currentStamps(chunk(source,new NBTTagInt(512)));input.getCompound("ForgeDataVersion").setInt("minecraft",512);
        RuntimeChecks.require(expectedInventory(expected,512).equals(inventory(saved(process(input)))),"Incorrect mod stamp bypassed custom inventory preservation");
    }
    private static void reusedOutput() throws Exception {
        NBTTagCompound fixed=process(chunk(body("lootablebodies.Corpse"),new NBTTagInt(512)));
        EntityLootableBody entity=(EntityLootableBody)net.minecraft.entity.EntityType.create(fixed.copy(),world);entity.clear();entity.writeAdditional(fixed);
        EntityLootableBody reloaded=(EntityLootableBody)net.minecraft.entity.EntityType.create(fixed.copy(),world);
        RuntimeChecks.require(reloaded.isEmpty(),"Reused output compound resurrected a consumed upgrade payload");
    }
    private static void allCurrentStamps() throws Exception {
        NBTTagCompound input=chunk(body("lootablebodies.Corpse"),new NBTTagInt(922)),versions=new NBTTagCompound();
        versions.setInt("minecraft",922);versions.setInt("lootablebodies",1);versions.setInt("lootablebodiesbuildtest",1);input.setTag("ForgeDataVersion",versions);
        saved(process(input));
    }
    private static void vanillaFalseStamp() throws Exception {
        NBTTagCompound input=chunk(body("lootablebodies.Corpse"),new NBTTagInt(922)),versions=new NBTTagCompound();
        versions.setInt("minecraft",922);versions.setInt("lootablebodies",1);versions.setInt("lootablebodiesbuildtest",1);input.setTag("ForgeDataVersion",versions);
        NBTTagCompound chest=new NBTTagCompound();chest.setString("id","Chest");chest.setInt("x",2);chest.setInt("y",80);chest.setInt("z",2);
        NBTTagCompound banner=new NBTTagCompound();banner.setString("id","minecraft:banner");banner.setByte("Count",(byte)2);banner.setShort("Damage",(short)0);banner.setByte("Slot",(byte)0);
        NBTTagCompound tag=new NBTTagCompound(),block=new NBTTagCompound();block.setInt("Base",5);tag.setTag("BlockEntityTag",block);banner.setTag("tag",tag);NBTTagList items=new NBTTagList();items.add(banner);chest.setTag("Items",items);NBTTagList tiles=new NBTTagList();tiles.add(chest);input.getCompound("Level").setTag("TileEntities",tiles);
        NBTTagCompound fixed=processChunk(input).getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        RuntimeChecks.require(fixed.getString("id").equals("minecraft:purple_banner") && !fixed.hasKey("Damage") && fixed.getByte("Count")==2,"False version stamps skipped the vanilla banner conversion: "+fixed);
    }
    private static Map<String,Integer> expectedInventory(NBTTagCompound source,int version) {
        NBTTagCompound expected=source.copy();
        for(String key:Arrays.asList("Equipment","Aux")) {
            NBTTagList items=expected.getList(key,10);
            for(int i=0;i<items.size();i++)items.set(i,WorldUpgradeChecks.expectedItem(items.getCompound(i),version));
        }
        return inventory(expected);
    }
}
