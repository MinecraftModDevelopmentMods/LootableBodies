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
    private final UnaryOperator<NBTTagCompound> structures;
    NativeSafeFixer(DataFixer delegate,UnaryOperator<NBTTagCompound> structures){this.delegate=delegate;this.structures=structures;}
    @Override public Schema getSchema(int key){return delegate.getSchema(key);}
    @Override @SuppressWarnings("unchecked") public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int version,int target) {
        if(!(input.getValue() instanceof NBTTagCompound) || input.getOps()!=NBTDynamicOps.INSTANCE)return delegate.update(type,input,version,target);
        NBTTagCompound raw=(NBTTagCompound)input.getValue();String kind=type.typeName();
        try {
        int source=kind.equals("chunk")?chunkVersion(raw,version):version;
        NBTTagCompound result;
        if(kind.equals("chunk") && source<1493 && target>1493) {
            result=convert(type,raw,source,1493);
            if(result.getCompound("Level").getBoolean("hasLegacyStructureData") && structures!=null)result=structures.apply(result);
            result=convert(type,result,1493,target);
        } else result=convert(type,raw,source,target);
        return (Dynamic<T>)new Dynamic<>(NBTDynamicOps.INSTANCE,result);
        }catch(RuntimeException failure) {
            // ChunkProviderServer catches Exception and regenerates a missing
            // chunk. Preservation failure must escape that fallback and stop.
            if(kind.equals("chunk"))throw new UnsafeChunkUpgrade(failure);
            throw failure;
        }
    }
    private static final class UnsafeChunkUpgrade extends Error {
        private static final long serialVersionUID=1L;
        UnsafeChunkUpgrade(Throwable cause){super("Lootable Bodies stopped an unsafe chunk conversion. Keep the original world and upgrade journal.",cause);}
    }
    private NBTTagCompound convert(DSL.TypeReference type,NBTTagCompound input,int source,int target) {
        NBTTagCompound copy=input.copy();Tokens tokens=new Tokens();String kind=type.typeName();
        if(kind.equals("chunk")) {
            NBTTagCompound level=copy.getCompound("Level");
            // Vanilla discards typed Sections/TileEntities from incomplete old
            // chunks. Keep stored terrain as well as entities and containers.
            if(source<1493 && (nonempty(level,"Sections") || nonempty(level,"Entities") || nonempty(level,"TileEntities"))) {
                level.setBoolean("TerrainPopulated",true);level.setBoolean("LightPopulated",true);
            }
            shieldList(level.getList("Entities",10),source,tokens,level.getInt("xPos"),level.getInt("zPos"),true);
        } else if(kind.equals("entity") || kind.equals("entity_tree"))copy=shieldEntity(copy,source,tokens,0,0,false);
        else if(kind.equals("player")) {
            NBTTagCompound vehicle=copy.getCompound("RootVehicle");
            if(vehicle.contains("Entity",10))vehicle.setTag("Entity",shieldEntity(vehicle.getCompound("Entity"),source,tokens,0,0,false));
            for(String shoulder:new String[]{"ShoulderEntityLeft","ShoulderEntityRight"})if(copy.contains(shoulder,10))copy.setTag(shoulder,shieldEntity(copy.getCompound(shoulder),source,tokens,0,0,false));
        }
        protectEggs(copy,source,tokens);
        NBTTagCompound result;
        if(source<704 && target>=705) {
            // The old item hook writes Chest/Banner/Pig names back while the
            // 704/705 schemas expect namespaced IDs. Cross that boundary on
            // copies before the later item and flattening fixes run.
            result=NBTUtil.update(delegate,type,copy,source,703);
            bridgeIdentifiers(result,kind);
            result=NBTUtil.update(delegate,type,result,705,target);
        } else result=source<target?NBTUtil.update(delegate,type,copy,source,target):copy;
        result=(NBTTagCompound)restore(result,tokens);
        for(String token:tokens.values.keySet())if(!Integer.valueOf(1).equals(tokens.restored.get(token)))throw new IllegalStateException("Native conversion lost or duplicated protected Lootable Bodies data");
        if(kind.equals("chunk"))result.setInt("DataVersion",Math.max(source,target));
        return result;
    }
    private void bridgeIdentifiers(NBTTagCompound nbt,String kind) {
        switch(kind) {
        case "chunk":
            NBTTagCompound level=nbt.getCompound("Level");
            bridgeList(level,"Entities","entity_tree");bridgeList(level,"TileEntities","block_entity");break;
        case "player":
            bridgeList(nbt,"Inventory","item_stack");bridgeList(nbt,"EnderItems","item_stack");
            bridgeField(nbt.getCompound("RootVehicle"),"Entity","entity_tree");
            bridgeField(nbt,"ShoulderEntityLeft","entity_tree");bridgeField(nbt,"ShoulderEntityRight","entity_tree");break;
        case "item_stack":
            NBTTagCompound tag=nbt.getCompound("tag");
            bridgeField(tag,"EntityTag","entity_tree");bridgeField(tag,"BlockEntityTag","block_entity");break;
        case "entity": case "entity_tree":
            renameIdentifier(nbt,TypeReferences.ENTITY_TYPE);
            bridgeList(nbt,"Passengers","entity_tree");bridgeField(nbt,"Riding","entity_tree");
            for(String key:new String[]{"Equipment","ArmorItems","HandItems","Items","Inventory"})bridgeList(nbt,key,"item_stack");
            for(String key:new String[]{"Item","FireworksItem","Potion","SaddleItem","ArmorItem","DecorItem"})bridgeField(nbt,key,"item_stack");
            bridgeField(nbt,"TileEntityData","block_entity");
            for(INBTBase entry:nbt.getCompound("Offers").getList("Recipes",10))
                for(String key:new String[]{"buy","buyB","sell"})bridgeField((NBTTagCompound)entry,key,"item_stack");
            bridgeSpawner(nbt);break;
        case "block_entity":
            renameIdentifier(nbt,TypeReferences.BLOCK_ENTITY);
            bridgeList(nbt,"Items","item_stack");bridgeField(nbt,"RecordItem","item_stack");bridgeSpawner(nbt);break;
        case "structure":
            for(INBTBase entry:nbt.getList("entities",10))bridgeField((NBTTagCompound)entry,"nbt","entity_tree");
            for(INBTBase entry:nbt.getList("blocks",10))bridgeField((NBTTagCompound)entry,"nbt","block_entity");break;
        default: break;
        }
    }
    private void renameIdentifier(NBTTagCompound nbt,DSL.TypeReference type) {
        if(!nbt.contains("id",8))return;
        NBTTagCompound id=new NBTTagCompound();id.setString("id",nbt.getString("id"));
        NBTTagCompound fixed=NBTUtil.update(delegate,type,id,703,705);
        if(!fixed.contains("id",8))throw new IllegalStateException("Vanilla identifier conversion lost its type");
        nbt.setString("id",fixed.getString("id"));
    }
    private void bridgeField(NBTTagCompound parent,String key,String kind) {
        if(parent.contains(key,10))bridgeIdentifiers(parent.getCompound(key),kind);
    }
    private void bridgeList(NBTTagCompound parent,String key,String kind) {
        for(INBTBase entry:parent.getList(key,10))bridgeIdentifiers((NBTTagCompound)entry,kind);
    }
    private void bridgeSpawner(NBTTagCompound nbt) {
        bridgeField(nbt,"SpawnData","entity_tree");
        for(INBTBase entry:nbt.getList("SpawnPotentials",10))bridgeField((NBTTagCompound)entry,"Entity","entity_tree");
    }
    private static boolean nonempty(NBTTagCompound nbt,String key){return nbt.contains(key,9) && !nbt.getList(key,10).isEmpty();}
    private void shieldList(NBTTagList entities,int source,Tokens tokens,int x,int z,boolean saved) {
        for(int i=0;i<entities.size();i++)entities.set(i,shieldEntity(entities.getCompound(i),source,tokens,x,z,saved));
    }
    private NBTTagCompound shieldEntity(NBTTagCompound entity,int source,Tokens tokens,int x,int z,boolean saved) {
        if(UpgradeJournal.corpse(entity)) {
            NBTTagCompound context=sourceContext(entity,source,x,z,saved);
            NBTTagCompound safe=stageCorpse(entity,context);
            NBTTagList riders=safe.getList("Passengers",10);
            for(int i=0;i<riders.size();i++) {
                NBTTagCompound child=riders.getCompound(i);
                NBTTagCompound converted;
                if(UpgradeJournal.corpse(child)) {
                    Tokens nested=new Tokens();NBTTagCompound placeholder=shieldEntity(child,source,nested,x,z,saved);
                    converted=(NBTTagCompound)restore(placeholder,nested);
                } else converted=NBTUtil.update(this,TypeReferences.ENTITY_TYPE,child.copy(),source,UpgradePreservation.MINECRAFT_VERSION);
                riders.set(i,converted);
            }
            String token=tokens.put(safe,false);NBTTagCompound placeholder=new NBTTagCompound();placeholder.setString("id",source<704?"ArmorStand":"minecraft:armor_stand");placeholder.setString(tokens.key,token);return placeholder;
        }
        shieldList(entity.getList("Passengers",10),source,tokens,x,z,saved);return entity;
    }
    private static NBTTagCompound sourceContext(NBTTagCompound entity,int version,int x,int z,boolean saved) {
        if(entity.getString("id").equals("lootablebodies:corpse") && entity.contains(UpgradePreservation.VERSION,3) && entity.getInt(UpgradePreservation.VERSION)==3){NBTTagCompound context=new NBTTagCompound();context.setInt("DataVersion",1631);return context;}
        UpgradeJournal journal=UpgradePreservation.journal();
        if(saved) {
            try {
                NBTTagCompound original=journal==null || !entity.hasUniqueId("UUID")?null:journal.originalFor(entity.getUniqueId("UUID"),x,z);
                if(original==null)throw new IllegalStateException("Unpreserved corpse in saved chunk; refusing unsafe conversion");
                return original.getCompound("SourceVersions").copy();
            }catch(IOException damaged){throw new IllegalStateException("Corpse preservation could not be verified",damaged);}
        }
        NBTTagCompound context=new NBTTagCompound();int marker=entity.getInt(UpgradePreservation.VERSION);
        context.setInt("DataVersion",entity.getString("id").equals("lootablebodies.Corpse")?512:marker==1?922:marker==2?1343:version);return context;
    }
    static NBTTagCompound stageCorpse(NBTTagCompound raw,NBTTagCompound context) {
        if(raw.contains(UpgradePreservation.VERSION,3) && raw.getInt(UpgradePreservation.VERSION)>3)throw new IllegalStateException("This corpse was saved by a newer Lootable Bodies format");
        NBTTagCompound safe=raw.copy();boolean current=raw.getString("id").equals("lootablebodies:corpse") && raw.contains(UpgradePreservation.VERSION,3) && raw.getInt(UpgradePreservation.VERSION)==3;
        safe.setString("id","lootablebodies:corpse");safe.setInt(UpgradePreservation.VERSION,3);
        if(current)return safe;
        safe.setTag(UpgradePreservation.SOURCES,context.copy());
        NBTTagList records=new NBTTagList();boolean custom=list(raw.getTag("Equipment"));
        retainInventory(records,"Equipment",raw.getTag("Equipment"),context);
        retainInventory(records,"Aux",raw.getTag("Aux"),context);
        if(!custom) {
            retainMirror(records,raw.getList("ArmorItems",10),new int[]{3,2,1,0},context);
            retainMirror(records,raw.getList("HandItems",10),new int[]{4,5},context);
        }
        INBTBase recovery=raw.getTag("LootableBodiesUnresolved");
        if(list(recovery))for(INBTBase value:(NBTTagList)recovery) {
            NBTTagCompound record=((NBTTagCompound)value).copy();
            if(record.contains("Source",8) && record.contains("Data",10) && !record.hasKey("SourceVersions") && (record.getString("Source").equals("Equipment") || record.getString("Source").equals("Aux")))record.setTag("SourceVersions",context.copy());
            records.add(record);
        }else if(recovery!=null)retain(records,"Recovery",recovery,context);
        safe.setTag("Equipment",new NBTTagList());safe.removeTag("Aux");safe.removeTag("ArmorItems");safe.removeTag("HandItems");
        if(!records.isEmpty())safe.setTag("LootableBodiesUnresolved",records);else safe.removeTag("LootableBodiesUnresolved");
        // These are durable existing recovery records, not a transient envelope:
        // Anvil may save this compound before an entity reader runs.
        safe.removeTag("LootableBodiesUpgradePayload");safe.removeTag("LootableBodiesInventorySource");
        if(safe.contains("CustomName",8) && context.getInt("DataVersion")<1514)
            safe.setString("CustomName",ITextComponent.Serializer.toJson(new TextComponentString(safe.getString("CustomName"))));
        return safe;
    }
    private static boolean list(INBTBase nbt){return nbt instanceof NBTTagList && (((NBTTagList)nbt).isEmpty() || ((NBTTagList)nbt).getTagType()==10);}
    private static void retainInventory(NBTTagList records,String source,INBTBase data,NBTTagCompound context) {
        if(data==null)return;
        if(list(data))for(INBTBase entry:(NBTTagList)data)retain(records,source,entry,context);
        else retain(records,source+"List",data,context);
    }
    private static void retainMirror(NBTTagList records,NBTTagList items,int[] slots,NBTTagCompound context) {
        for(int i=0;i<Math.min(items.size(),slots.length);i++) {
            NBTTagCompound item=items.getCompound(i).copy();if(item.isEmpty())continue;item.setByte("Slot",(byte)slots[i]);retain(records,"Equipment",item,context);
        }
    }
    private static void retain(NBTTagList records,String source,INBTBase data,NBTTagCompound context) {
        NBTTagCompound record=new NBTTagCompound();record.setString("Source",source);record.setTag("Data",data.copy());if(!context.isEmpty())record.setTag("SourceVersions",context.copy());records.add(record);
    }
    private void protectEggs(INBTBase value,int source,Tokens tokens) {
        if(value instanceof NBTTagCompound) {
            NBTTagCompound item=(NBTTagCompound)value;
            if(item.getString("id").equals("minecraft:spawn_egg") && item.getCompound("tag").contains("EntityTag",10) && UpgradeJournal.corpse(item.getCompound("tag").getCompound("EntityTag"))) {
                NBTTagCompound entity=item.getCompound("tag").getCompound("EntityTag");NBTTagCompound context=sourceContext(entity,source,0,0,false);
                NBTTagCompound safe=stageCorpse(entity,context);item.setString("id","lootablebodies:corpse_spawn_egg");item.getCompound("tag").removeTag("EntityTag");item.setString(tokens.key,tokens.put(safe,true));
            }
            for(String key:new ArrayList<>(item.keySet()))protectEggs(item.getTag(key),source,tokens);
        }else if(value instanceof NBTTagList)for(INBTBase entry:(NBTTagList)value)protectEggs(entry,source,tokens);
    }
    private INBTBase restore(INBTBase value,Tokens tokens) {
        if(value instanceof NBTTagCompound) {
            NBTTagCompound nbt=(NBTTagCompound)value;
            if(nbt.contains(tokens.key,8)) {
                String id=nbt.getString(tokens.key);Protected original=tokens.values.get(id);if(original==null)throw new IllegalStateException("Unknown preservation token");
                tokens.restored.merge(id,1,Integer::sum);
                if(!original.egg)return original.data.copy();
                nbt.removeTag(tokens.key);NBTTagCompound tag=nbt.getCompound("tag");tag.setTag("EntityTag",original.data.copy());nbt.setTag("tag",tag);
            }
            for(String key:new ArrayList<>(nbt.keySet()))nbt.setTag(key,restore(nbt.getTag(key),tokens));return nbt;
        }
        if(value instanceof NBTTagList){NBTTagList list=(NBTTagList)value;for(int i=0;i<list.size();i++)list.set(i,restore(list.get(i),tokens));}return value;
    }
    private static final class Protected {final NBTTagCompound data;final boolean egg;Protected(NBTTagCompound data,boolean egg){this.data=data;this.egg=egg;}}
    private static final class Tokens {
        final String key="LootableBodiesMigration_"+UUID.randomUUID().toString().replace("-","");
        final Map<String,Protected> values=new HashMap<>();final Map<String,Integer> restored=new HashMap<>();
        String put(NBTTagCompound data,boolean egg){String id=Integer.toString(values.size());values.put(id,new Protected(data,egg));return id;}
    }
    private static boolean sections(NBTTagCompound root,String key,int type) {
        NBTTagList sections=root.getCompound("Level").getList("Sections",10);for(int i=0;i<sections.size();i++)if(sections.getCompound(i).contains(key,type))return true;return false;
    }
    private static boolean compatible(int version,boolean legacy,boolean palette) {
        return version>=512 && version<=1631 && (!legacy || version<1451) && (!palette || version>=1451);
    }
    private static int chunkVersion(NBTTagCompound chunk,int requested) {
        boolean legacy=sections(chunk,"Blocks",7),palette=sections(chunk,"Palette",9);
        if(legacy && palette)throw new IllegalStateException("Mixed old and flattened sections cannot be converted safely");
        int source=chunk.contains("DataVersion",3)?chunk.getInt("DataVersion"):requested;
        UpgradeJournal journal=UpgradePreservation.journal();NBTTagCompound level=chunk.getCompound("Level");
        int preserved=-1;
        for(UpgradeJournal.LocatedCorpse located:UpgradeJournal.corpses(level.getList("Entities",10))) {
            try {
                NBTTagCompound original=journal==null || !located.entity.hasUniqueId("UUID")?null:journal.originalFor(located.entity.getUniqueId("UUID"),level.getInt("xPos"),level.getInt("zPos"));
                if(original!=null) {
                    NBTTagCompound context=original.getCompound("SourceVersions");int candidate=UpgradePreservation.chunkSourceVersion(context);
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
            NBTTagCompound world=journal.worldVersions();int candidate=UpgradePreservation.chunkSourceVersion(world);
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
