package cyano.lootable.buildtest;
import net.minecraft.item.Items;
import net.minecraft.inventory.container.Slot;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.IntNBT;
import net.minecraft.nbt.DoubleNBT;
import net.minecraft.nbt.CompoundNBT;

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
import net.minecraft.world.server.ServerWorld;

import cyano.lootable.entities.EntityLootableBody;

/** Real Forge data-fixer gates. Synthetic inputs complement sealed old-jar worlds. */
final class UpgradeChecks {
    static void registerOwnFixer() {
        final com.mojang.datafixers.DataFixer nativeFixer=net.minecraft.util.datafix.DataFixesManager.getDataFixer();
        UpgradePreservation.register(new com.mojang.datafixers.DataFixer() {
            public com.mojang.datafixers.schemas.Schema getSchema(int key){return nativeFixer.getSchema(key);}
            public <T>com.mojang.datafixers.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.datafixers.Dynamic<T> input,int source,int target) {
                com.mojang.datafixers.Dynamic<T> result=nativeFixer.update(type,input,source,target);
                if(type.typeName().equals("item_stack") && source<target && target==UpgradePreservation.MINECRAFT_VERSION && result.getValue() instanceof CompoundNBT) {
                    CompoundNBT tag=((CompoundNBT)result.getValue()).getCompound("tag");if(tag.contains("upgrade_counter",3))tag.putInt("upgrade_counter",tag.getInt("upgrade_counter")+1);
                }
                return result;
            }
        });
    }
    private static CompoundNBT nativeChunk(CompoundNBT input) {
        return cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(world.getServer().getDataFixer()),TypeReferences.CHUNK,input,input.getInt("DataVersion"),UpgradePreservation.MINECRAFT_VERSION);
    }
    private interface Check { void run() throws Exception; }
    private static int failures,sequence;
    private static File output;
    private static ServerWorld world;
    static void run(MinecraftServer server) throws Exception {
        failures=0;sequence=0;world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));registerOwnFixer();
        output=new File(world.getSaveHandler().getWorldDirectory().getParentFile(),"upgrade-contract-fixtures");
        check("legacy_id_before_entity_construction",()->conversion("lootablebodies.Corpse",IntNBT.valueOf(512)));
        check("native_111_identity_survives",()->conversion("lootablebodies:corpse",IntNBT.valueOf(922)));
        check("missing_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",null));
        check("wrong_type_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",StringNBT.valueOf("damaged")));
        check("nonfinite_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",DoubleNBT.valueOf(Double.NaN)));
        check("negative_chunk_version_preserves_48_slots",()->conversion("lootablebodies.Corpse",IntNBT.valueOf(-1)));
        check("incorrect_new_stamp_does_not_skip_legacy_items",()->conversion("lootablebodies.Corpse",IntNBT.valueOf(922)));
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
        check("native114_registry_rename_events_reach_the_bridge",UpgradeChecks::nativeRenames);
        check("retired_vanilla_professions_are_read_only_compatibility",UpgradeChecks::legacyProfessions);
        check("native114_tracking_range_preserves32blocks",()-> {
            RuntimeChecks.require(cyano.lootable.LootableBodies.CORPSE.getTrackingRange()*16==32,"Native tracking range changed from32blocks");
            RuntimeChecks.require(cyano.lootable.LootableBodies.CORPSE.getUpdateFrequency()==1 && cyano.lootable.LootableBodies.CORPSE.shouldSendVelocityUpdates(),"Tracking update settings changed");
        });
        check("unstamped_native112_chunk_keeps_white_bed_in_vanilla_chest",()->nativeChest(false));
        check("legacy_corpse_does_not_downgrade_native112_chunk_items",()->nativeChest(true));
        check("bed_with_no_source_evidence_stays_raw_and_retriable",UpgradeChecks::ambiguousBed);
        System.out.println("LOOTABLE_BODIES_UPGRADE_CONTRACT_RESULT checks=26 failures="+failures);
        RuntimeChecks.require(failures==0,"Upgrade contracts: "+failures);
    }
    private static void check(String name,Check check) {
        try {check.run();System.out.println("UPGRADE_CONTRACT_PASS "+name);}
        catch(Throwable error){failures++;System.out.println("UPGRADE_CONTRACT_FAIL "+name+": "+error);error.printStackTrace();}
    }
    private static void nativeChest(boolean legacy) throws Exception {
        CompoundNBT entity=body(legacy?"lootablebodies.Corpse":"lootablebodies:corpse");
        if(!legacy)entity.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").putString("id","minecraft:pig");
        CompoundNBT input=chunk(entity,legacy?IntNBT.valueOf(1343):null);
        CompoundNBT chest=new CompoundNBT();chest.putString("id","minecraft:chest");chest.putInt("x",2);chest.putInt("y",80);chest.putInt("z",2);
        CompoundNBT bed=new CompoundNBT();bed.putString("id","minecraft:bed");bed.putByte("Count",(byte)1);bed.putShort("Damage",(short)0);bed.putByte("Slot",(byte)0);
        ListNBT items=new ListNBT();items.add(bed);chest.put("Items",items);ListNBT tiles=new ListNBT();tiles.add(chest);input.getCompound("Level").put("TileEntities",tiles);
        CompoundNBT fixed=processChunk(input,"1.12.2");
        CompoundNBT actual=fixed.getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        CompoundNBT expected=bed.copy();expected.putString("id","minecraft:white_bed");expected.remove("Damage");
        RuntimeChecks.require(NameLifetimeChecks.same(expected,actual),"Native white bed changed under chunk conversion: "+actual);
        CompoundNBT converted=saved(fixed.getCompound("Level").getList("Entities",10).getCompound(0));
        RuntimeChecks.require(inventory(converted).size()==48,"Legacy/native corpse inventory changed while correcting vanilla context");
    }
    private static void ambiguousBed() throws Exception {
        CompoundNBT input=body("lootablebodies:corpse"),item=new CompoundNBT();item.putString("id","minecraft:bed");item.putByte("Count",(byte)1);item.putShort("Damage",(short)0);item.putByte("Slot",(byte)6);
        ListNBT items=new ListNBT();items.add(item);input.put("Equipment",items);
        CompoundNBT once=saved(process(chunk(input,null))),record=once.getList("LootableBodiesUnresolved",10).getCompound(0);
        RuntimeChecks.require(once.getList("LootableBodiesUnresolved",10).size()==1 && NameLifetimeChecks.same(item,record.get("Data")) && record.getCompound("SourceVersions").getBoolean("Ambiguous"),"Ambiguous bed was guessed or lost");
        EntityLootableBody again=new EntityLootableBody(world);again.readAdditional(once);
        RuntimeChecks.require(!again.isEmpty(),"Raw recoverable bed allowed empty-corpse decay");
        CompoundNBT twice=new CompoundNBT();again.writeAdditional(twice);
        RuntimeChecks.require(NameLifetimeChecks.same(once.get("LootableBodiesUnresolved"),twice.get("LootableBodiesUnresolved")),"Ambiguous bed duplicated or changed during reload");
    }
    private static void soundMapping() throws Exception {
        net.minecraftforge.registries.IForgeRegistry<net.minecraft.util.SoundEvent> registry=net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS;
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<net.minecraft.util.SoundEvent> old=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("minecraft:entity.experience_orb.touch"),196);
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<net.minecraft.util.SoundEvent> other=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("absentfixture:other_sound"),197);
        net.minecraftforge.event.RegistryEvent.MissingMappings<net.minecraft.util.SoundEvent> event=new net.minecraftforge.event.RegistryEvent.MissingMappings<>(new net.minecraft.util.ResourceLocation("minecraft:soundevents"),registry,Arrays.asList(old,other));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
        RuntimeChecks.require(old.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.REMAP && old.getTarget()==registry.getValue(new net.minecraft.util.ResourceLocation("minecraft:entity.experience_orb.pickup")),"Known vanilla sound rename not remapped");
        RuntimeChecks.require(other.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.DEFAULT,"Unrelated missing mappings must retain Forge's normal handling");
    }
    private static void nativeRenames() {
        rename(net.minecraftforge.registries.ForgeRegistries.ITEMS,"sign","oak_sign");
        rename(net.minecraftforge.registries.ForgeRegistries.ITEMS,"rose_red","red_dye");
        rename(net.minecraftforge.registries.ForgeRegistries.ITEMS,"cactus_green","green_dye");
        rename(net.minecraftforge.registries.ForgeRegistries.ITEMS,"dandelion_yellow","yellow_dye");
        rename(net.minecraftforge.registries.ForgeRegistries.BLOCKS,"sign","oak_sign");
        rename(net.minecraftforge.registries.ForgeRegistries.BLOCKS,"wall_sign","oak_wall_sign");
    }
    private static <T extends net.minecraftforge.registries.IForgeRegistryEntry<T>> void rename(net.minecraftforge.registries.IForgeRegistry<T> registry,String oldName,String current) {
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<T> old=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("minecraft",oldName),30000);
        net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<T> other=new net.minecraftforge.event.RegistryEvent.MissingMappings.Mapping<>(registry,registry,new net.minecraft.util.ResourceLocation("absentfixture",oldName),30001);
        net.minecraftforge.event.RegistryEvent.MissingMappings<T> event=new net.minecraftforge.event.RegistryEvent.MissingMappings<>(registry.getRegistryName(),registry,Arrays.asList(old,other));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
        RuntimeChecks.require(old.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.REMAP && old.getTarget()==registry.getValue(new net.minecraft.util.ResourceLocation("minecraft",current)),"Native registry rename event not remapped: "+oldName);
        RuntimeChecks.require(other.getAction()==net.minecraftforge.event.RegistryEvent.MissingMappings.Action.DEFAULT,"Foreign registry rename was consumed");
    }
    private static void legacyProfessions() {
        net.minecraft.util.ResourceLocation name=new net.minecraft.util.ResourceLocation("minecraft:villagerprofessions");
        net.minecraftforge.registries.IForgeRegistry<?> registry=net.minecraftforge.registries.RegistryManager.ACTIVE.getRegistry(name);
        RuntimeChecks.require(registry!=null && registry.getKeys().size()==6,"Retired vanilla profession registry was not preserved");
        RuntimeChecks.require(!registry.containsKey(new net.minecraft.util.ResourceLocation("absentfixture:profession")),"Unknown professions must retain native missing-mapping handling");
        RuntimeChecks.require(!net.minecraftforge.registries.RegistryManager.ACTIVE.takeSnapshot(true).containsKey(name),"Retired registry must not be written into new saves");
    }
    private static void bed(int source,int marker,int damage) throws Exception {
        CompoundNBT input=body("lootablebodies:corpse");
        input.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").putString("id","minecraft:pig");
        CompoundNBT item=new CompoundNBT();item.putString("id","minecraft:bed");item.putByte("Count",(byte)2);item.putShort("Damage",(short)0);item.putByte("Slot",(byte)41);input.getList("Equipment",10).set(41,item);
        if(marker>0)input.putInt("LootableBodiesDataVersion",marker);
        CompoundNBT result=saved(process(chunk(input,IntNBT.valueOf(source))));
        ListNBT equipment=result.getList("Equipment",10);CompoundNBT found=null;
        for(int i=0;i<equipment.size();i++)if(equipment.getCompound(i).getByte("Slot")==41)found=equipment.getCompound(i);
        int count=0;
        for(String key:Arrays.asList("Equipment","Aux")) {
            ListNBT items=result.getList(key,10);
            for(int i=0;i<items.size();i++) {
                CompoundNBT entry=items.getCompound(i);
                if(entry.getString("id").equals(damage==14?"minecraft:red_bed":"minecraft:white_bed")) {
                    RuntimeChecks.require(!entry.contains("Damage"),"Bed retained obsolete damage: "+entry);
                    count+=entry.getByte("Count")&255;
                }
            }
        }
        RuntimeChecks.require(found!=null && found.getString("id").equals(damage==14?"minecraft:red_bed":"minecraft:white_bed") && found.getByte("Count")==1 && count==2,"Bed count/slot/overflow changed");
    }
    private static CompoundNBT body(String identity) {
        CompoundNBT entity=new CompoundNBT();new EntityLootableBody(world).writeUnlessPassenger(entity);
        entity.remove("LootableBodiesDataVersion");entity.remove("LootableBodiesSourceVersions");entity.putString("id",identity);
        entity.putUniqueId("UUID",UUID.fromString("00000000-1111-4111-8111-000000000111"));
        ListNBT equipment=new ListNBT();
        // Deliberate old-format synthetic data, not a native stack mislabeled
        // as 1.10. Sealed old-writer worlds provide the historical qualification.
        for(int i=0;i<48;i++) {
            CompoundNBT item=new CompoundNBT(),tag=new CompoundNBT(),display=new CompoundNBT();
            item.putString("id","minecraft:stone");item.putByte("Count",(byte)(i%32+1));item.putShort("Damage",(short)(i%2));item.putByte("Slot",(byte)i);
            display.putString("Name","Fixture "+i);tag.put("display",display);tag.putInt("typed",i);item.put("tag",tag);equipment.add(item);
        }
        // A vanilla 1.10 spawn egg needs the 1.11 entity-name item conversion.
        CompoundNBT egg=new CompoundNBT();egg.putString("id","minecraft:spawn_egg");egg.putByte("Count",(byte)3);egg.putShort("Damage",(short)0);egg.putByte("Slot",(byte)40);
        CompoundNBT tag=new CompoundNBT(),pig=new CompoundNBT();pig.putString("id","Pig");pig.putString("CustomName","Saved pig");tag.put("EntityTag",pig);tag.putString("keepsake","old egg");egg.put("tag",tag);
        equipment.set(40,egg);entity.put("Equipment",equipment);entity.putString("Name","Saved Owner");entity.putDouble("FallDistance",Double.NaN);return entity;
    }
    private static CompoundNBT chunk(CompoundNBT entity,INBT version) {
        CompoundNBT root=new CompoundNBT(),level=new CompoundNBT();root.put("Level",level);
        if(version!=null)root.put("DataVersion",version.copy());
        level.putInt("xPos",0);level.putInt("zPos",0);ListNBT entities=new ListNBT();entities.add(entity);level.put("Entities",entities);return root;
    }
    private static CompoundNBT process(CompoundNBT source) throws Exception {
        return processChunk(source).getCompound("Level").getList("Entities",10).getCompound(0);
    }
    private static CompoundNBT processChunk(CompoundNBT source) throws Exception {return processChunk(source,null);}
    private static CompoundNBT processChunk(CompoundNBT source,String minecraft) throws Exception {
        Path path=new File(output,"case-"+(sequence++)).toPath();Files.createDirectories(path.resolve("region"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream data=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(source,data);}
        int sectors=(bytes.size()+5+4095)/4096;java.nio.ByteBuffer region=java.nio.ByteBuffer.allocate(8192+sectors*4096);region.putInt((2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());Files.write(path.resolve("region/r.0.0.mca"),region.array());
        if(minecraft!=null) {
            CompoundNBT root=new CompoundNBT(),fml=new CompoundNBT(),mc=new CompoundNBT(),mod=new CompoundNBT();
            mc.putString("ModId","minecraft");mc.putString("ModVersion",minecraft);mod.putString("ModId","lootablebodies");mod.putString("ModVersion","2.4.0");ListNBT mods=new ListNBT();mods.add(mc);mods.add(mod);fml.put("ModList",mods);root.put("FML",fml);root.put("Data",new CompoundNBT());
            try(OutputStream file=Files.newOutputStream(path.resolve("level.dat"))){CompressedStreamTools.writeCompressed(root,file);}
        }
        Class<?> preservation=null;Object previous=null;Field active=null;
        try {
            // The red run exercises the real unprotected Forge pipeline. Once
            // implemented, activate the same preflight used before world loading.
            try {preservation=Class.forName("cyano.lootable.upgrade.UpgradePreservation");active=preservation.getDeclaredField("journal");active.setAccessible(true);previous=active.get(null);preservation.getMethod("prepare",Path.class).invoke(null,path);}
            catch(ClassNotFoundException beforeImplementation) { }
            CompoundNBT input=source.copy();
            if(preservation!=null)try{input=(CompoundNBT)preservation.getMethod("chunkContext",CompoundNBT.class).invoke(null,input);}catch(NoSuchMethodException beforeBoundaryFix){}
            CompoundNBT result=nativeChunk(input);
            if(preservation!=null)try{preservation.getMethod("clearChunkContext",CompoundNBT.class).invoke(null,result);}catch(NoSuchMethodException beforeBoundaryFix){}
            return result;
        } finally {if(active!=null)active.set(null,previous);}
    }
    private static CompoundNBT saved(CompoundNBT fixed) {
        RuntimeChecks.require(fixed.getString("id").equals("lootablebodies:corpse"),"legacy identity was not converted before construction");
        Entity entity=FixtureApi.loadEntity(fixed.copy(),world);
        RuntimeChecks.require(entity instanceof EntityLootableBody,"corpse factory returned no body");
        CompoundNBT saved=new CompoundNBT();((EntityLootableBody)entity).writeAdditional(saved);return saved;
    }
    private static Map<String,Integer> inventory(CompoundNBT body) {
        Map<String,Integer> counts=new TreeMap<>();for(String key:Arrays.asList("Equipment","Aux")) {
            ListNBT list=body.getList(key,10);for(int i=0;i<list.size();i++){CompoundNBT item=list.getCompound(i).copy();int count=item.getByte("Count")&255;item.remove("Count");item.remove("Slot");String id=TypedNbt.canonical(item);counts.put(id,counts.getOrDefault(id,0)+count);}
        }return counts;
    }
    private static void conversion(String identity,INBT version) throws Exception {
        CompoundNBT input=body(identity);
        if(identity.equals("lootablebodies:corpse"))input.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").putString("id","minecraft:pig");
        CompoundNBT expected=input.copy();
        CompoundNBT output=saved(process(chunk(input,version)));
        RuntimeChecks.require(expectedInventory(expected,identity.equals("lootablebodies.Corpse")?512:922).equals(inventory(output)),"complete inventory or vanilla spawn-egg conversion changed");
        RuntimeChecks.require(output.getInt("LootableBodiesDataVersion")==UpgradePreservation.DATA_VERSION,"missing per-corpse migration marker");
        RuntimeChecks.require(output.getList("LootableBodiesInvalidFields",10).size()>0,"malformed original fields were lost in vanilla fixing");
    }
    private static void missingItem() throws Exception {
        CompoundNBT body=body("lootablebodies.Corpse"),raw=body.getList("Equipment",10).getCompound(20);raw.putString("id","absentfixture:keepsake");
        CompoundNBT result=saved(process(chunk(body,IntNBT.valueOf(512))));ListNBT recovery=result.getList("LootableBodiesUnresolved",10);
        RuntimeChecks.require(recovery.size()==1,"unavailable item not retained exactly once");CompoundNBT record=recovery.getCompound(0);
        RuntimeChecks.require(NameLifetimeChecks.same(raw,record.get("Data")) && record.getCompound("SourceVersions").getInt("DataVersion")==512,"unavailable item lost its original payload or source context");
    }
    private static void repeated() throws Exception {
        CompoundNBT raw=body("lootablebodies.Corpse"),fixed=process(chunk(raw,IntNBT.valueOf(512))),once=saved(fixed);
        CompoundNBT again=nativeChunk(chunk(fixed.copy(),IntNBT.valueOf(512))).getCompound("Level").getList("Entities",10).getCompound(0);
        CompoundNBT twice=saved(again);RuntimeChecks.require(inventory(once).equals(inventory(twice)),"repeated data fixing duplicated or lost items");
    }
    private static void recovery() throws Exception {
        CompoundNBT body=body("lootablebodies.Corpse"),raw=body.getList("Equipment",10).getCompound(20).copy();raw.putString("id","absentfixture:keepsake");
        CompoundNBT record=new CompoundNBT();record.putString("Source","Aux");record.put("Data",raw.copy());ListNBT records=new ListNBT();records.add(record);body.put("LootableBodiesUnresolved",records);
        CompoundNBT output=saved(process(chunk(body,IntNBT.valueOf(512))));CompoundNBT kept=output.getList("LootableBodiesUnresolved",10).getCompound(0);
        RuntimeChecks.require(kept.getString("Source").equals("Aux") && NameLifetimeChecks.same(raw,kept.get("Data")),"existing Source/Data payload changed");
        RuntimeChecks.require(kept.getCompound("SourceVersions").getInt("DataVersion")==512,"existing recovery extension lost source version context");
    }
    private static CompoundNBT fullSave(CompoundNBT fixed) {
        Entity entity=FixtureApi.loadEntity(fixed.copy(),world);RuntimeChecks.require(entity instanceof EntityLootableBody,"Missing body for full save");
        CompoundNBT result=new CompoundNBT();entity.writeUnlessPassenger(result);return result;
    }
    private static void savedCounter() throws Exception {
        CompoundNBT source=body("lootablebodies.Corpse");source.getList("Equipment",10).getCompound(20).getCompound("tag").putInt("upgrade_counter",0);
        CompoundNBT once=fullSave(process(chunk(source,IntNBT.valueOf(512))));
        CompoundNBT counted=null;
        for(INBT entry:once.getList("Equipment",10))if(((CompoundNBT)entry).getByte("Slot")==20)counted=(CompoundNBT)entry;
        RuntimeChecks.require(counted!=null && counted.getCompound("tag").getInt("upgrade_counter")==1,"Item fixer did not run exactly once on first load: "+counted);
        CompoundNBT twice=fullSave(process(chunk(once,IntNBT.valueOf(922))));RuntimeChecks.require(inventory(once).equals(inventory(twice)),"Old item version was applied again after saving migrated items");
    }
    private static CompoundNBT currentStamps(CompoundNBT chunk) {chunk.putInt("DataVersion",UpgradePreservation.MINECRAFT_VERSION);chunk.put("ForgeDataVersion",new CompoundNBT());chunk.getCompound("ForgeDataVersion").putInt("minecraft",-1);return chunk;}
    private static void migratedDamagedStamp() throws Exception {
        CompoundNBT source=body("lootablebodies:corpse");source.putInt("LootableBodiesDataVersion",1);source.getList("Equipment",10).getCompound(40).getCompound("tag").getCompound("EntityTag").putString("id","minecraft:pig");
        CompoundNBT result=saved(process(currentStamps(chunk(source,null))));RuntimeChecks.require(expectedInventory(source,922).equals(inventory(result)),"Damaged version stamp truncated an already migrated Equipment list");
    }
    private static void forgedModStamp() throws Exception {
        CompoundNBT source=body("lootablebodies.Corpse"),expected=source.copy();
        CompoundNBT input=currentStamps(chunk(source,IntNBT.valueOf(512)));input.getCompound("ForgeDataVersion").putInt("minecraft",512);
        RuntimeChecks.require(expectedInventory(expected,512).equals(inventory(saved(process(input)))),"Incorrect mod stamp bypassed custom inventory preservation");
    }
    private static void reusedOutput() throws Exception {
        CompoundNBT fixed=process(chunk(body("lootablebodies.Corpse"),IntNBT.valueOf(512)));
        EntityLootableBody entity=(EntityLootableBody)FixtureApi.loadEntity(fixed.copy(),world);entity.clear();entity.writeAdditional(fixed);
        EntityLootableBody reloaded=(EntityLootableBody)FixtureApi.loadEntity(fixed.copy(),world);
        RuntimeChecks.require(reloaded.isEmpty(),"Reused output compound resurrected a consumed upgrade payload");
    }
    private static void allCurrentStamps() throws Exception {
        CompoundNBT input=chunk(body("lootablebodies.Corpse"),IntNBT.valueOf(922)),versions=new CompoundNBT();
        versions.putInt("minecraft",922);versions.putInt("lootablebodies",1);versions.putInt("lootablebodiesbuildtest",1);input.put("ForgeDataVersion",versions);
        saved(process(input));
    }
    private static void vanillaFalseStamp() throws Exception {
        CompoundNBT input=chunk(body("lootablebodies.Corpse"),IntNBT.valueOf(922)),versions=new CompoundNBT();
        versions.putInt("minecraft",922);versions.putInt("lootablebodies",1);versions.putInt("lootablebodiesbuildtest",1);input.put("ForgeDataVersion",versions);
        CompoundNBT chest=new CompoundNBT();chest.putString("id","Chest");chest.putInt("x",2);chest.putInt("y",80);chest.putInt("z",2);
        CompoundNBT banner=new CompoundNBT();banner.putString("id","minecraft:banner");banner.putByte("Count",(byte)2);banner.putShort("Damage",(short)0);banner.putByte("Slot",(byte)0);
        CompoundNBT tag=new CompoundNBT(),block=new CompoundNBT();block.putInt("Base",5);tag.put("BlockEntityTag",block);banner.put("tag",tag);ListNBT items=new ListNBT();items.add(banner);chest.put("Items",items);ListNBT tiles=new ListNBT();tiles.add(chest);input.getCompound("Level").put("TileEntities",tiles);
        CompoundNBT fixed=processChunk(input).getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        RuntimeChecks.require(fixed.getString("id").equals("minecraft:purple_banner") && !fixed.contains("Damage") && fixed.getByte("Count")==2,"False version stamps skipped the vanilla banner conversion: "+fixed);
    }
    private static Map<String,Integer> expectedInventory(CompoundNBT source,int version) {
        CompoundNBT expected=source.copy();
        for(String key:Arrays.asList("Equipment","Aux")) {
            ListNBT items=expected.getList(key,10);
            for(int i=0;i<items.size();i++)items.set(i,WorldUpgradeChecks.expectedItem(items.getCompound(i),version));
        }
        return inventory(expected);
    }
}
