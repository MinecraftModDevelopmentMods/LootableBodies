package cyano.lootable.upgrade;

import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.TypeReferences;
import net.minecraft.util.text.*;

/** Shields owned payloads while vanilla converts its own chunk and item schemas. */
final class NativeSafeFixer implements DataFixer {
    final DataFixer delegate;
    private final UnaryOperator<CompoundNBT> structures;
    NativeSafeFixer(DataFixer delegate,UnaryOperator<CompoundNBT> structures){this.delegate=delegate;this.structures=structures;}
    @Override public Schema getSchema(int key){return delegate.getSchema(key);}
    @Override @SuppressWarnings("unchecked") public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int version,int target) {
        if(!(input.getValue() instanceof CompoundNBT) || input.getOps()!=NBTDynamicOps.INSTANCE)return delegate.update(type,input,version,target);
        CompoundNBT raw=(CompoundNBT)input.getValue();String kind=type.typeName();
        try {
        int source=kind.equals("chunk")?chunkVersion(raw,version):version;
        CompoundNBT result;
        if(kind.equals("chunk") && source<1493 && target>1493) {
            result=convert(type,raw,source,1493);
            if(result.getCompound("Level").getBoolean("hasLegacyStructureData") && structures!=null)result=structures.apply(result);
            result=convert(type,result,1493,target);
        } else result=convert(type,raw,source,target);
        return (Dynamic<T>)new Dynamic<>(NBTDynamicOps.INSTANCE,result);
        }catch(RuntimeException failure) {
            // ServerChunkProvider catches Exception and regenerates a missing
            // chunk. Preservation failure must escape that fallback and stop.
            if(kind.equals("chunk"))throw new UnsafeChunkUpgrade(failure);
            throw failure;
        }
    }
    private static final class UnsafeChunkUpgrade extends Error {
        private static final long serialVersionUID=1L;
        UnsafeChunkUpgrade(Throwable cause){super("Lootable Bodies stopped an unsafe chunk conversion. Keep the original world and upgrade journal.",cause);}
    }
    private CompoundNBT convert(DSL.TypeReference type,CompoundNBT input,int source,int target) {
        CompoundNBT copy=input.copy();Tokens tokens=new Tokens();String kind=type.typeName();
        if(kind.equals("chunk")) {
            CompoundNBT level=copy.getCompound("Level");
            // Vanilla discards typed Sections/TileEntities from incomplete old
            // chunks. Keep stored terrain as well as entities and containers.
            if(source<1493 && (nonempty(level,"Sections") || nonempty(level,"Entities") || nonempty(level,"TileEntities"))) {
                level.putBoolean("TerrainPopulated",true);level.putBoolean("LightPopulated",true);
            }
            shieldList(level.getList("Entities",10),source,tokens,level.getInt("xPos"),level.getInt("zPos"),true);
        } else if(kind.equals("entity") || kind.equals("entity_tree"))copy=shieldEntity(copy,source,tokens,0,0,false);
        else if(kind.equals("player")) {
            CompoundNBT vehicle=copy.getCompound("RootVehicle");
            if(vehicle.contains("Entity",10))vehicle.put("Entity",shieldEntity(vehicle.getCompound("Entity"),source,tokens,0,0,false));
            for(String shoulder:new String[]{"ShoulderEntityLeft","ShoulderEntityRight"})if(copy.contains(shoulder,10))copy.put(shoulder,shieldEntity(copy.getCompound(shoulder),source,tokens,0,0,false));
        }
        protectEggs(copy,source,tokens);
        CompoundNBT result;
        if(source<704 && target>=705) {
            // The old item hook writes Chest/Banner/Pig names back while the
            // 704/705 schemas expect namespaced IDs. Cross that boundary on
            // copies before the later item and flattening fixes run.
            result=cyano.lootable.upgrade.NativeNbt.update(delegate,type,copy,source,703);
            bridgeIdentifiers(result,kind);
            result=cyano.lootable.upgrade.NativeNbt.update(delegate,type,result,705,target);
        } else result=source<target?cyano.lootable.upgrade.NativeNbt.update(delegate,type,copy,source,target):copy;
        result=(CompoundNBT)restore(result,tokens);
        for(String token:tokens.values.keySet())if(!Integer.valueOf(1).equals(tokens.restored.get(token)))throw new IllegalStateException("Native conversion lost or duplicated protected Lootable Bodies data");
        if(kind.equals("chunk"))result.putInt("DataVersion",Math.max(source,target));
        return result;
    }
    private void bridgeIdentifiers(CompoundNBT nbt,String kind) {
        switch(kind) {
        case "chunk":
            CompoundNBT level=nbt.getCompound("Level");
            bridgeList(level,"Entities","entity_tree");bridgeList(level,"TileEntities","block_entity");break;
        case "player":
            bridgeList(nbt,"Inventory","item_stack");bridgeList(nbt,"EnderItems","item_stack");
            bridgeField(nbt.getCompound("RootVehicle"),"Entity","entity_tree");
            bridgeField(nbt,"ShoulderEntityLeft","entity_tree");bridgeField(nbt,"ShoulderEntityRight","entity_tree");break;
        case "item_stack":
            CompoundNBT tag=nbt.getCompound("tag");
            bridgeField(tag,"EntityTag","entity_tree");bridgeField(tag,"BlockEntityTag","block_entity");break;
        case "entity": case "entity_tree":
            renameIdentifier(nbt,TypeReferences.ENTITY_TYPE);
            bridgeList(nbt,"Passengers","entity_tree");bridgeField(nbt,"Riding","entity_tree");
            for(String key:new String[]{"Equipment","ArmorItems","HandItems","Items","Inventory"})bridgeList(nbt,key,"item_stack");
            for(String key:new String[]{"Item","FireworksItem","Potion","SaddleItem","ArmorItem","DecorItem"})bridgeField(nbt,key,"item_stack");
            bridgeField(nbt,"TileEntityData","block_entity");
            for(INBT entry:nbt.getCompound("Offers").getList("Recipes",10))
                for(String key:new String[]{"buy","buyB","sell"})bridgeField((CompoundNBT)entry,key,"item_stack");
            bridgeSpawner(nbt);break;
        case "block_entity":
            renameIdentifier(nbt,TypeReferences.BLOCK_ENTITY);
            bridgeList(nbt,"Items","item_stack");bridgeField(nbt,"RecordItem","item_stack");bridgeSpawner(nbt);break;
        case "structure":
            for(INBT entry:nbt.getList("entities",10))bridgeField((CompoundNBT)entry,"nbt","entity_tree");
            for(INBT entry:nbt.getList("blocks",10))bridgeField((CompoundNBT)entry,"nbt","block_entity");break;
        default: break;
        }
    }
    private void renameIdentifier(CompoundNBT nbt,DSL.TypeReference type) {
        if(!nbt.contains("id",8))return;
        CompoundNBT id=new CompoundNBT();id.putString("id",nbt.getString("id"));
        CompoundNBT fixed=cyano.lootable.upgrade.NativeNbt.update(delegate,type,id,703,705);
        if(!fixed.contains("id",8))throw new IllegalStateException("Vanilla identifier conversion lost its type");
        nbt.putString("id",fixed.getString("id"));
    }
    private void bridgeField(CompoundNBT parent,String key,String kind) {
        if(parent.contains(key,10))bridgeIdentifiers(parent.getCompound(key),kind);
    }
    private void bridgeList(CompoundNBT parent,String key,String kind) {
        for(INBT entry:parent.getList(key,10))bridgeIdentifiers((CompoundNBT)entry,kind);
    }
    private void bridgeSpawner(CompoundNBT nbt) {
        bridgeField(nbt,"SpawnData","entity_tree");
        for(INBT entry:nbt.getList("SpawnPotentials",10))bridgeField((CompoundNBT)entry,"Entity","entity_tree");
    }
    private static boolean nonempty(CompoundNBT nbt,String key){return nbt.contains(key,9) && !nbt.getList(key,10).isEmpty();}
    private void shieldList(ListNBT entities,int source,Tokens tokens,int x,int z,boolean saved) {
        for(int i=0;i<entities.size();i++)entities.set(i,shieldEntity(entities.getCompound(i),source,tokens,x,z,saved));
    }
    private CompoundNBT shieldEntity(CompoundNBT entity,int source,Tokens tokens,int x,int z,boolean saved) {
        if(UpgradeJournal.corpse(entity)) {
            CompoundNBT context=sourceContext(entity,source,x,z,saved);
            CompoundNBT safe=stageCorpse(entity,context);
            ListNBT riders=safe.getList("Passengers",10);
            for(int i=0;i<riders.size();i++) {
                CompoundNBT child=riders.getCompound(i);
                CompoundNBT converted;
                if(UpgradeJournal.corpse(child)) {
                    Tokens nested=new Tokens();CompoundNBT placeholder=shieldEntity(child,source,nested,x,z,saved);
                    converted=(CompoundNBT)restore(placeholder,nested);
                } else converted=cyano.lootable.upgrade.NativeNbt.update(this,TypeReferences.ENTITY_TYPE,child.copy(),source,UpgradePreservation.MINECRAFT_VERSION);
                riders.set(i,converted);
            }
            String token=tokens.put(safe,false);CompoundNBT placeholder=new CompoundNBT();placeholder.putString("id",source<704?"ArmorStand":"minecraft:armor_stand");placeholder.putString(tokens.key,token);return placeholder;
        }
        shieldList(entity.getList("Passengers",10),source,tokens,x,z,saved);return entity;
    }
    private static CompoundNBT sourceContext(CompoundNBT entity,int version,int x,int z,boolean saved) {
        if(entity.getString("id").equals("lootablebodies:corpse") && entity.contains(UpgradePreservation.VERSION,3) && entity.getInt(UpgradePreservation.VERSION)==UpgradePreservation.DATA_VERSION){CompoundNBT context=new CompoundNBT();context.putInt("DataVersion",UpgradePreservation.MINECRAFT_VERSION);return context;}
        UpgradeJournal journal=UpgradePreservation.journal();
        if(saved) {
            try {
                CompoundNBT original=journal==null || !entity.hasUniqueId("UUID")?null:journal.originalFor(entity.getUniqueId("UUID"),x,z);
                if(original==null)throw new IllegalStateException("Unpreserved corpse in saved chunk; refusing unsafe conversion");
                return original.getCompound("SourceVersions").copy();
            }catch(IOException damaged){throw new IllegalStateException("Corpse preservation could not be verified",damaged);}
        }
        CompoundNBT context=new CompoundNBT();int marker=entity.getInt(UpgradePreservation.VERSION);
        context.putInt("DataVersion",entity.getString("id").equals("lootablebodies.Corpse")?512:marker==1?922:marker==2?1343:marker==3?1631:version);return context;
    }
    static CompoundNBT stageCorpse(CompoundNBT raw,CompoundNBT context) {
        if(raw.contains(UpgradePreservation.VERSION,3) && raw.getInt(UpgradePreservation.VERSION)>UpgradePreservation.DATA_VERSION)throw new IllegalStateException("This corpse was saved by a newer Lootable Bodies format");
        CompoundNBT safe=raw.copy();boolean current=raw.getString("id").equals("lootablebodies:corpse") && raw.contains(UpgradePreservation.VERSION,3) && raw.getInt(UpgradePreservation.VERSION)==UpgradePreservation.DATA_VERSION;
        safe.putString("id","lootablebodies:corpse");safe.putInt(UpgradePreservation.VERSION,UpgradePreservation.DATA_VERSION);
        if(current)return safe;
        safe.put(UpgradePreservation.SOURCES,context.copy());
        ListNBT records=new ListNBT();boolean custom=list(raw.get("Equipment"));
        retainInventory(records,"Equipment",raw.get("Equipment"),context);
        retainInventory(records,"Aux",raw.get("Aux"),context);
        if(!custom) {
            retainMirror(records,raw.getList("ArmorItems",10),new int[]{3,2,1,0},context);
            retainMirror(records,raw.getList("HandItems",10),new int[]{4,5},context);
        }
        INBT recovery=raw.get("LootableBodiesUnresolved");
        if(list(recovery))for(INBT value:(ListNBT)recovery) {
            CompoundNBT record=((CompoundNBT)value).copy();
            if(record.contains("Source",8) && record.contains("Data",10) && !record.contains("SourceVersions") && (record.getString("Source").equals("Equipment") || record.getString("Source").equals("Aux")))record.put("SourceVersions",context.copy());
            records.add(record);
        }else if(recovery!=null)retain(records,"Recovery",recovery,context);
        safe.put("Equipment",new ListNBT());safe.remove("Aux");safe.remove("ArmorItems");safe.remove("HandItems");
        if(!records.isEmpty())safe.put("LootableBodiesUnresolved",records);else safe.remove("LootableBodiesUnresolved");
        // These are durable existing recovery records, not a transient envelope:
        // Anvil may save this compound before an entity reader runs.
        safe.remove("LootableBodiesUpgradePayload");safe.remove("LootableBodiesInventorySource");
        if(safe.contains("CustomName",8) && context.getInt("DataVersion")<1514)
            safe.putString("CustomName",ITextComponent.Serializer.toJson(new StringTextComponent(safe.getString("CustomName"))));
        return safe;
    }
    private static boolean list(INBT nbt){return nbt instanceof ListNBT && (((ListNBT)nbt).isEmpty() || ((ListNBT)nbt).getTagType()==10);}
    private static void retainInventory(ListNBT records,String source,INBT data,CompoundNBT context) {
        if(data==null)return;
        if(list(data))for(INBT entry:(ListNBT)data)retain(records,source,entry,context);
        else retain(records,source+"List",data,context);
    }
    private static void retainMirror(ListNBT records,ListNBT items,int[] slots,CompoundNBT context) {
        for(int i=0;i<Math.min(items.size(),slots.length);i++) {
            CompoundNBT item=items.getCompound(i).copy();if(item.isEmpty())continue;item.putByte("Slot",(byte)slots[i]);retain(records,"Equipment",item,context);
        }
    }
    private static void retain(ListNBT records,String source,INBT data,CompoundNBT context) {
        CompoundNBT record=new CompoundNBT();record.putString("Source",source);record.put("Data",data.copy());if(!context.isEmpty())record.put("SourceVersions",context.copy());records.add(record);
    }
    private void protectEggs(INBT value,int source,Tokens tokens) {
        if(value instanceof CompoundNBT) {
            CompoundNBT item=(CompoundNBT)value;
            if(item.getString("id").equals("minecraft:spawn_egg") && item.getCompound("tag").contains("EntityTag",10) && UpgradeJournal.corpse(item.getCompound("tag").getCompound("EntityTag"))) {
                CompoundNBT entity=item.getCompound("tag").getCompound("EntityTag");CompoundNBT context=sourceContext(entity,source,0,0,false);
                CompoundNBT safe=stageCorpse(entity,context);item.putString("id","lootablebodies:corpse_spawn_egg");item.getCompound("tag").remove("EntityTag");item.putString(tokens.key,tokens.put(safe,true));
            }
            for(String key:new ArrayList<>(item.keySet()))protectEggs(item.get(key),source,tokens);
        }else if(value instanceof ListNBT)for(INBT entry:(ListNBT)value)protectEggs(entry,source,tokens);
    }
    private INBT restore(INBT value,Tokens tokens) {
        if(value instanceof CompoundNBT) {
            CompoundNBT nbt=(CompoundNBT)value;
            if(nbt.contains(tokens.key,8)) {
                String id=nbt.getString(tokens.key);Protected original=tokens.values.get(id);if(original==null)throw new IllegalStateException("Unknown preservation token");
                tokens.restored.merge(id,1,Integer::sum);
                if(!original.egg)return original.data.copy();
                nbt.remove(tokens.key);CompoundNBT tag=nbt.getCompound("tag");tag.put("EntityTag",original.data.copy());nbt.put("tag",tag);
            }
            for(String key:new ArrayList<>(nbt.keySet()))nbt.put(key,restore(nbt.get(key),tokens));return nbt;
        }
        if(value instanceof ListNBT){ListNBT list=(ListNBT)value;for(int i=0;i<list.size();i++)list.set(i,restore(list.get(i),tokens));}return value;
    }
    private static final class Protected {final CompoundNBT data;final boolean egg;Protected(CompoundNBT data,boolean egg){this.data=data;this.egg=egg;}}
    private static final class Tokens {
        final String key="LootableBodiesMigration_"+UUID.randomUUID().toString().replace("-","");
        final Map<String,Protected> values=new HashMap<>();final Map<String,Integer> restored=new HashMap<>();
        String put(CompoundNBT data,boolean egg){String id=Integer.toString(values.size());values.put(id,new Protected(data,egg));return id;}
    }
    private static boolean sections(CompoundNBT root,String key,int type) {
        ListNBT sections=root.getCompound("Level").getList("Sections",10);for(int i=0;i<sections.size();i++)if(sections.getCompound(i).contains(key,type))return true;return false;
    }
    private static boolean compatible(int version,boolean legacy,boolean palette) {
        return version>=512 && version<=UpgradePreservation.MINECRAFT_VERSION && (!legacy || version<1451) && (!palette || version>=1451);
    }
    private static int chunkVersion(CompoundNBT chunk,int requested) {
        boolean legacy=sections(chunk,"Blocks",7),palette=sections(chunk,"Palette",9);
        if(legacy && palette)throw new IllegalStateException("Mixed old and flattened sections cannot be converted safely");
        int source=chunk.contains("DataVersion",3)?chunk.getInt("DataVersion"):requested;
        UpgradeJournal journal=UpgradePreservation.journal();CompoundNBT level=chunk.getCompound("Level");
        int preserved=-1;
        for(UpgradeJournal.LocatedCorpse located:UpgradeJournal.corpses(level.getList("Entities",10))) {
            try {
                CompoundNBT original=journal==null || !located.entity.hasUniqueId("UUID")?null:journal.originalFor(located.entity.getUniqueId("UUID"),level.getInt("xPos"),level.getInt("zPos"));
                if(original!=null) {
                    CompoundNBT context=original.getCompound("SourceVersions");int candidate=UpgradePreservation.chunkSourceVersion(context);
                    if(!context.getBoolean("Ambiguous") && compatible(candidate,legacy,palette)) {
                        if(preserved>=0 && preserved!=candidate)throw new IllegalStateException("Conflicting preserved chunk versions");
                        preserved=candidate;
                    }
                }
            }catch(IOException failure){throw new IllegalStateException("Chunk source context could not be verified",failure);}
        }
        // Journal context also corrects plausible false stamps. Restrict it to
        // the matching section schema so a saved native chunk is never flattened twice.
        if(preserved>=0)source=preserved;
        else if(journal!=null) {
            CompoundNBT world=journal.worldVersions();int candidate=UpgradePreservation.chunkSourceVersion(world);
            if(!compatible(candidate,legacy,palette))candidate=world.getInt("WorldDataVersion");
            if(!compatible(candidate,legacy,palette) && world.getCompound("WorldVersion").contains("Id",3))candidate=world.getCompound("WorldVersion").getInt("Id");
            if(compatible(candidate,legacy,palette) && (!compatible(source,legacy,palette) || candidate<source))source=candidate;
        }
        if(!compatible(source,legacy,palette)) {
            if(palette)source=1631;
            else if(legacy)throw new IllegalStateException("Stored legacy terrain has no safe source-version context; originals have been preserved");
            else source=requested;
        }
        return source;
    }
}
