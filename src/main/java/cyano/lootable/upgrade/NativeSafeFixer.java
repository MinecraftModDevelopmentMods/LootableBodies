package cyano.lootable.upgrade;

import com.mojang.datafixers.*;
import com.mojang.serialization.Dynamic;
import com.mojang.datafixers.schemas.Schema;
import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/** Shields owned payloads while vanilla converts its own chunk and item schemas. */
final class NativeSafeFixer implements DataFixer {
    final DataFixer delegate;
    private final UnaryOperator<CompoundTag> structures;
    private final UpgradeJournal preserved;
    NativeSafeFixer(DataFixer delegate,UnaryOperator<CompoundTag> structures){this(delegate,structures,null);}
    NativeSafeFixer(DataFixer delegate,UnaryOperator<CompoundTag> structures,UpgradeJournal preserved){this.delegate=delegate;this.structures=structures;this.preserved=preserved;}
    @Override public Schema getSchema(int key){return delegate.getSchema(key);}
    @Override @SuppressWarnings("unchecked") public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int version,int target) {
        if(!(input.getValue() instanceof CompoundTag))return delegate.update(type,input,version,target);
        CompoundTag raw=(CompoundTag)input.getValue();String kind=type.typeName();
        try {
        int source=kind.equals("chunk")?chunkVersion(raw,version):kind.equals("entity_chunk")?entityChunkVersion(raw):version;
        CompoundTag result;
        if(kind.equals("chunk") && source<1493 && target>1493) {
            result=convert(type,raw,source,1493);
            if(result.getCompound("Level").getBoolean("hasLegacyStructureData") && structures!=null)result=structures.apply(result);
            result=convert(type,result,1493,target);
        } else result=convert(type,raw,source,target);
        return new Dynamic<>(input.getOps(),(T)result);
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
    private CompoundTag convert(DSL.TypeReference type,CompoundTag input,int source,int target) {
        CompoundTag copy=input.copy();Tokens tokens=new Tokens();String kind=type.typeName();
        // Current world settings are already a registry codec payload. Replaying
        // pre-1.16 fixes on a damaged stamp otherwise replaces the seed and
        // dimensions. Shield this field alone; vanilla still fixes the level.
        if(kind.equals("world_gen_settings") && currentWorldSettings(copy))return updateCurrentWorldSettings(copy,source,target);
        Tag currentSettings=kind.equals("level") && copy.contains("WorldGenSettings",10)
                && currentWorldSettings(copy.getCompound("WorldGenSettings"))?copy.get("WorldGenSettings").copy():null;
        if(currentSettings!=null)copy.remove("WorldGenSettings");
        Tag currentBiomes=null;
        if(kind.equals("chunk")) {
            CompoundTag level=copy.getCompound("Level");
            // A damaged stamp can replay the old 2D biome expansion on an
            // already-current 3D array. Protect that field alone so older
            // vanilla entities and containers still receive their own fixes.
            if(source<2202 && target>=2202 && level.contains("Biomes",11)
                    && level.getIntArray("Biomes").length==1024) {
                currentBiomes=level.get("Biomes").copy();level.remove("Biomes");
            }
            // Vanilla discards typed Sections/TileEntities from incomplete old
            // chunks. Keep stored terrain as well as entities and containers.
            if(source<1493 && (nonempty(level,"Sections") || nonempty(level,"Entities") || nonempty(level,"TileEntities"))) {
                level.putBoolean("TerrainPopulated",true);level.putBoolean("LightPopulated",true);
            }
            shieldList(level.getList("Entities",10),source,tokens,level.getInt("xPos"),level.getInt("zPos"),true);
        } else if(kind.equals("entity_chunk")) {
            int[] position=copy.getIntArray("Position");
            shieldList(copy.getList("Entities",10),source,tokens,position[0],position[1],true);
        } else if(kind.equals("entity") || kind.equals("entity_tree"))copy=shieldEntity(copy,source,tokens,0,0,false);
        else if(kind.equals("player")) {
            CompoundTag vehicle=copy.getCompound("RootVehicle");
            if(vehicle.contains("Entity",10))vehicle.put("Entity",shieldEntity(vehicle.getCompound("Entity"),source,tokens,0,0,false));
            for(String shoulder:new String[]{"ShoulderEntityLeft","ShoulderEntityRight"})if(copy.contains(shoulder,10))copy.put(shoulder,shieldEntity(copy.getCompound(shoulder),source,tokens,0,0,false));
        }
        protectEggs(copy,source,tokens);
        CompoundTag result;
        if(source<704 && target>=705) {
            // The old item hook writes Chest/Banner/Pig names back while the
            // 704/705 schemas expect namespaced IDs. Cross that boundary on
            // copies before the later item and flattening fixes run.
            result=cyano.lootable.upgrade.NativeNbt.update(delegate,type,copy,source,703);
            bridgeIdentifiers(result,kind);
            result=cyano.lootable.upgrade.NativeNbt.update(delegate,type,result,705,target);
        } else result=source<target?cyano.lootable.upgrade.NativeNbt.update(delegate,type,copy,source,target):copy;
        result=(CompoundTag)restore(result,tokens);
        if(currentBiomes!=null)result.getCompound("Level").put("Biomes",currentBiomes);
        if(currentSettings!=null)result.put("WorldGenSettings",updateCurrentWorldSettings((CompoundTag)currentSettings,source,target));
        for(String token:tokens.values.keySet())if(!Integer.valueOf(1).equals(tokens.restored.get(token)))throw new IllegalStateException("Native conversion lost or duplicated protected Lootable Bodies data");
        if(kind.equals("chunk")) {
            CompoundTag level=result.getCompound("Level");ListTag entities=level.getList("Entities",10);
            if(!UpgradeJournal.corpses(entities).isEmpty()) {
                if(!level.contains("xPos",3) || !level.contains("zPos",3))throw new IllegalStateException("Saved terrain has no containing coordinates for corpse recovery");
                SavedCorpsePosition.prepare(entities,level.getInt("xPos"),level.getInt("zPos"));
            }
        } else if(kind.equals("entity_chunk")) {
            int[] position=result.getIntArray("Position");SavedCorpsePosition.prepare(result.getList("Entities",10),position[0],position[1]);
        }
        if(kind.equals("chunk") || kind.equals("entity_chunk"))result.putInt("DataVersion",Math.max(source,target));
        return result;
    }
    private static boolean currentWorldSettings(CompoundTag nbt) {
        if(!nbt.contains("seed",4) || !nbt.contains("dimensions",10))return false;
        CompoundTag dimensions=nbt.getCompound("dimensions");
        if(dimensions.isEmpty())return false;
        for(String key:dimensions.getAllKeys()) {
            if(!dimensions.contains(key,10))return false;
            CompoundTag dimension=dimensions.getCompound(key);
            if(!(dimension.contains("type",8) || dimension.contains("type",10))
                    || !dimension.contains("generator",10) || !dimension.getCompound("generator").contains("type",8))return false;
        }
        return true;
    }
    private CompoundTag updateCurrentWorldSettings(CompoundTag settings,int source,int target) {
        // A complete codec payload is at least native 1.16. Do not replay the
        // older destructive generator rewrite, but do apply genuine later
        // fixes, including native 1.17 flat-generator block renames.
        int provenSource=Math.max(source,2586);
        return provenSource<target?NativeNbt.update(delegate,References.WORLD_GEN_SETTINGS,
                settings.copy(),provenSource,target):settings;
    }
    private static int entityChunkVersion(CompoundTag root) {
        int[] position=root.getIntArray("Position");
        Tag entities=root.get("Entities");
        if(!root.contains("Position",11) || position.length!=2 || !(entities instanceof ListTag)
                || (((ListTag)entities).size()>0 && ((ListTag)entities).getElementType()!=10))
            throw new IllegalStateException("Malformed saved entity chunk; refusing unsafe construction");
        int version=root.contains("DataVersion",3)?root.getInt("DataVersion"):-1;
        if(version>UpgradePreservation.MINECRAFT_VERSION)
            throw new IllegalStateException("Saved entity chunk comes from a newer Minecraft version");
        // Terrain Sections/Biomes cannot identify the separate entity format.
        // The owned current writer marker proves the native version even when
        // an outer stamp was removed or downgraded.
        for(UpgradeJournal.LocatedCorpse body:UpgradeJournal.corpses((ListTag)entities))
            if(body.entity.getString("id").equals("lootablebodies:corpse")
                    && body.entity.contains(UpgradePreservation.VERSION,3)
                    && body.entity.getInt(UpgradePreservation.VERSION)==UpgradePreservation.DATA_VERSION)
                return UpgradePreservation.MINECRAFT_VERSION;
        if(version<512)throw new IllegalStateException("Cannot prove saved entity chunk source version");
        return version;
    }
    private void bridgeIdentifiers(CompoundTag nbt,String kind) {
        switch(kind) {
        case "chunk":
            CompoundTag level=nbt.getCompound("Level");
            bridgeList(level,"Entities","entity_tree");bridgeList(level,"TileEntities","block_entity");break;
        case "player":
            bridgeList(nbt,"Inventory","item_stack");bridgeList(nbt,"EnderItems","item_stack");
            bridgeField(nbt.getCompound("RootVehicle"),"Entity","entity_tree");
            bridgeField(nbt,"ShoulderEntityLeft","entity_tree");bridgeField(nbt,"ShoulderEntityRight","entity_tree");break;
        case "item_stack":
            CompoundTag tag=nbt.getCompound("tag");
            bridgeField(tag,"EntityTag","entity_tree");bridgeField(tag,"BlockEntityTag","block_entity");break;
        case "entity": case "entity_tree":
            renameIdentifier(nbt,References.ENTITY_TREE);
            bridgeList(nbt,"Passengers","entity_tree");bridgeField(nbt,"Riding","entity_tree");
            for(String key:new String[]{"Equipment","ArmorItems","HandItems","Items","Inventory"})bridgeList(nbt,key,"item_stack");
            for(String key:new String[]{"Item","FireworksItem","Potion","SaddleItem","ArmorItem","DecorItem"})bridgeField(nbt,key,"item_stack");
            bridgeField(nbt,"TileEntityData","block_entity");
            for(Tag entry:nbt.getCompound("Offers").getList("Recipes",10))
                for(String key:new String[]{"buy","buyB","sell"})bridgeField((CompoundTag)entry,key,"item_stack");
            bridgeSpawner(nbt);break;
        case "block_entity":
            renameIdentifier(nbt,References.BLOCK_ENTITY);
            bridgeList(nbt,"Items","item_stack");bridgeField(nbt,"RecordItem","item_stack");bridgeSpawner(nbt);break;
        case "structure":
            for(Tag entry:nbt.getList("entities",10))bridgeField((CompoundTag)entry,"nbt","entity_tree");
            for(Tag entry:nbt.getList("blocks",10))bridgeField((CompoundTag)entry,"nbt","block_entity");break;
        default: break;
        }
    }
    private void renameIdentifier(CompoundTag nbt,DSL.TypeReference type) {
        if(!nbt.contains("id",8))return;
        CompoundTag id=new CompoundTag();id.putString("id",nbt.getString("id"));
        CompoundTag fixed=cyano.lootable.upgrade.NativeNbt.update(delegate,type,id,703,705);
        if(!fixed.contains("id",8))throw new IllegalStateException("Vanilla identifier conversion lost its type");
        nbt.putString("id",fixed.getString("id"));
    }
    private void bridgeField(CompoundTag parent,String key,String kind) {
        if(parent.contains(key,10))bridgeIdentifiers(parent.getCompound(key),kind);
    }
    private void bridgeList(CompoundTag parent,String key,String kind) {
        for(Tag entry:parent.getList(key,10))bridgeIdentifiers((CompoundTag)entry,kind);
    }
    private void bridgeSpawner(CompoundTag nbt) {
        bridgeField(nbt,"SpawnData","entity_tree");
        for(Tag entry:nbt.getList("SpawnPotentials",10))bridgeField((CompoundTag)entry,"Entity","entity_tree");
    }
    private static boolean nonempty(CompoundTag nbt,String key){return nbt.contains(key,9) && !nbt.getList(key,10).isEmpty();}
    private void shieldList(ListTag entities,int source,Tokens tokens,int x,int z,boolean saved) {
        for(int i=0;i<entities.size();i++)entities.set(i,shieldEntity(entities.getCompound(i),source,tokens,x,z,saved));
    }
    private CompoundTag shieldEntity(CompoundTag entity,int source,Tokens tokens,int x,int z,boolean saved) {
        if(UpgradeJournal.corpse(entity)) {
            CompoundTag context=sourceContext(entity,source,x,z,saved);
            CompoundTag safe=stageCorpse(entity,context);
            ListTag riders=safe.getList("Passengers",10);
            for(int i=0;i<riders.size();i++) {
                CompoundTag child=riders.getCompound(i);
                CompoundTag converted;
                if(UpgradeJournal.corpse(child)) {
                    Tokens nested=new Tokens();CompoundTag placeholder=shieldEntity(child,source,nested,x,z,saved);
                    converted=(CompoundTag)restore(placeholder,nested);
                } else converted=cyano.lootable.upgrade.NativeNbt.update(this,References.ENTITY_TREE,child.copy(),source,UpgradePreservation.MINECRAFT_VERSION);
                riders.set(i,converted);
            }
            String token=tokens.put(safe,false);CompoundTag placeholder=new CompoundTag();placeholder.putString("id",source<704?"ArmorStand":"minecraft:armor_stand");placeholder.putString(tokens.key,token);return placeholder;
        }
        shieldList(entity.getList("Passengers",10),source,tokens,x,z,saved);return entity;
    }
    private CompoundTag sourceContext(CompoundTag entity,int version,int x,int z,boolean saved) {
        if(entity.getString("id").equals("lootablebodies:corpse") && entity.contains(UpgradePreservation.VERSION,3) && entity.getInt(UpgradePreservation.VERSION)==UpgradePreservation.DATA_VERSION){CompoundTag context=new CompoundTag();context.putInt("DataVersion",UpgradePreservation.MINECRAFT_VERSION);return context;}
        UpgradeJournal journal=preserved!=null?preserved:UpgradePreservation.journal();
        if(saved) {
            try {
                CompoundTag original=journal==null || !CorpseIdentity.present(entity)?null:journal.originalFor(CorpseIdentity.read(entity),x,z);
                if(original==null)throw new IllegalStateException("Unpreserved corpse in saved chunk; refusing unsafe conversion");
                return original.getCompound("SourceVersions").copy();
            }catch(IOException damaged){throw new IllegalStateException("Corpse preservation could not be verified",damaged);}
        }
        CompoundTag context=new CompoundTag();int marker=entity.getInt(UpgradePreservation.VERSION);
        context.putInt("DataVersion",entity.getString("id").equals("lootablebodies.Corpse")?512:marker==1?922:marker==2?1343:marker==3?1631:marker==4?1976:marker==5?2230:marker==6?2586:version);return context;
    }
    static CompoundTag stageCorpse(CompoundTag raw,CompoundTag context) {
        if(raw.contains(UpgradePreservation.VERSION,3) && raw.getInt(UpgradePreservation.VERSION)>UpgradePreservation.DATA_VERSION)throw new IllegalStateException("This corpse was saved by a newer Lootable Bodies format");
        CompoundTag safe=raw.copy();boolean current=raw.getString("id").equals("lootablebodies:corpse") && raw.contains(UpgradePreservation.VERSION,3) && raw.getInt(UpgradePreservation.VERSION)==UpgradePreservation.DATA_VERSION;
        if(CorpseIdentity.present(raw)) {
            safe.putUUID("UUID",CorpseIdentity.read(raw));safe.remove("UUIDMost");safe.remove("UUIDLeast");
        }
        safe.putString("id","lootablebodies:corpse");safe.putInt(UpgradePreservation.VERSION,UpgradePreservation.DATA_VERSION);
        if(current)return safe;
        safe.put(UpgradePreservation.SOURCES,context.copy());
        ListTag records=new ListTag();boolean custom=list(raw.get("Equipment"));
        retainInventory(records,"Equipment",raw.get("Equipment"),context);
        retainInventory(records,"Aux",raw.get("Aux"),context);
        if(!custom) {
            retainMirror(records,raw.getList("ArmorItems",10),new int[]{3,2,1,0},context);
            retainMirror(records,raw.getList("HandItems",10),new int[]{4,5},context);
        }
        Tag recovery=raw.get("LootableBodiesUnresolved");
        if(list(recovery))for(Tag value:(ListTag)recovery) {
            CompoundTag record=((CompoundTag)value).copy();
            if(record.contains("Source",8) && record.contains("Data",10) && !record.contains("SourceVersions") && (record.getString("Source").equals("Equipment") || record.getString("Source").equals("Aux")))record.put("SourceVersions",context.copy());
            records.add(record);
        }else if(recovery!=null)retain(records,"Recovery",recovery,context);
        safe.put("Equipment",new ListTag());safe.remove("Aux");safe.remove("ArmorItems");safe.remove("HandItems");
        if(!records.isEmpty())safe.put("LootableBodiesUnresolved",records);else safe.remove("LootableBodiesUnresolved");
        // These are durable existing recovery records, not a transient envelope:
        // Anvil may save this compound before an entity reader runs.
        safe.remove("LootableBodiesUpgradePayload");safe.remove("LootableBodiesInventorySource");
        if(safe.contains("CustomName",8) && context.getInt("DataVersion")<1514)
            safe.putString("CustomName",Component.Serializer.toJson(new TextComponent(safe.getString("CustomName"))));
        return safe;
    }
    private static boolean list(Tag nbt){return nbt instanceof ListTag && (((ListTag)nbt).isEmpty() || ((ListTag)nbt).getElementType()==10);}
    private static void retainInventory(ListTag records,String source,Tag data,CompoundTag context) {
        if(data==null)return;
        if(list(data))for(Tag entry:(ListTag)data)retain(records,source,entry,context);
        else retain(records,source+"List",data,context);
    }
    private static void retainMirror(ListTag records,ListTag items,int[] slots,CompoundTag context) {
        for(int i=0;i<Math.min(items.size(),slots.length);i++) {
            CompoundTag item=items.getCompound(i).copy();if(item.isEmpty())continue;item.putByte("Slot",(byte)slots[i]);retain(records,"Equipment",item,context);
        }
    }
    private static void retain(ListTag records,String source,Tag data,CompoundTag context) {
        CompoundTag record=new CompoundTag();record.putString("Source",source);record.put("Data",data.copy());if(!context.isEmpty())record.put("SourceVersions",context.copy());records.add(record);
    }
    private void protectEggs(Tag value,int source,Tokens tokens) {
        if(value instanceof CompoundTag) {
            CompoundTag item=(CompoundTag)value;
            if(item.getString("id").equals("minecraft:spawn_egg") && item.getCompound("tag").contains("EntityTag",10) && UpgradeJournal.corpse(item.getCompound("tag").getCompound("EntityTag"))) {
                CompoundTag entity=item.getCompound("tag").getCompound("EntityTag");CompoundTag context=sourceContext(entity,source,0,0,false);
                CompoundTag safe=stageCorpse(entity,context);item.putString("id","lootablebodies:corpse_spawn_egg");item.getCompound("tag").remove("EntityTag");item.putString(tokens.key,tokens.put(safe,true));
            }
            for(String key:new ArrayList<>(item.getAllKeys()))protectEggs(item.get(key),source,tokens);
        }else if(value instanceof ListTag)for(Tag entry:(ListTag)value)protectEggs(entry,source,tokens);
    }
    private Tag restore(Tag value,Tokens tokens) {
        if(value instanceof CompoundTag) {
            CompoundTag nbt=(CompoundTag)value;
            if(nbt.contains(tokens.key,8)) {
                String id=nbt.getString(tokens.key);Protected original=tokens.values.get(id);if(original==null)throw new IllegalStateException("Unknown preservation token");
                tokens.restored.merge(id,1,Integer::sum);
                if(!original.egg)return original.data.copy();
                nbt.remove(tokens.key);CompoundTag tag=nbt.getCompound("tag");tag.put("EntityTag",original.data.copy());nbt.put("tag",tag);
            }
            for(String key:new ArrayList<>(nbt.getAllKeys()))nbt.put(key,restore(nbt.get(key),tokens));return nbt;
        }
        if(value instanceof ListTag){ListTag list=(ListTag)value;for(int i=0;i<list.size();i++)list.set(i,restore(list.get(i),tokens));}return value;
    }
    private static final class Protected {final CompoundTag data;final boolean egg;Protected(CompoundTag data,boolean egg){this.data=data;this.egg=egg;}}
    private static final class Tokens {
        final String key="LootableBodiesMigration_"+UUID.randomUUID().toString().replace("-","");
        final Map<String,Protected> values=new HashMap<>();final Map<String,Integer> restored=new HashMap<>();
        String put(CompoundTag data,boolean egg){String id=Integer.toString(values.size());values.put(id,new Protected(data,egg));return id;}
    }
    private static boolean sections(CompoundTag root,String key,int type) {
        ListTag sections=root.getCompound("Level").getList("Sections",10);for(int i=0;i<sections.size();i++)if(sections.getCompound(i).contains(key,type))return true;return false;
    }
    private static boolean compatible(int version,boolean legacy,boolean palette) {
        return version>=512 && version<=UpgradePreservation.MINECRAFT_VERSION && (!legacy || version<1451) && (!palette || version>=1451);
    }
    private static int chunkVersion(CompoundTag chunk,int requested) {
        boolean legacy=sections(chunk,"Blocks",7),palette=sections(chunk,"Palette",9);
        if(legacy && palette)throw new IllegalStateException("Mixed old and flattened sections cannot be converted safely");
        int source=chunk.contains("DataVersion",3)?chunk.getInt("DataVersion"):requested;
        UpgradeJournal journal=UpgradePreservation.journal();CompoundTag level=chunk.getCompound("Level");
        int preserved=-1;
        for(UpgradeJournal.LocatedCorpse located:UpgradeJournal.corpses(level.getList("Entities",10))) {
            try {
                CompoundTag original=journal==null || !CorpseIdentity.present(located.entity)?null:journal.originalFor(CorpseIdentity.read(located.entity),level.getInt("xPos"),level.getInt("zPos"));
                if(original!=null) {
                    CompoundTag context=original.getCompound("SourceVersions");int candidate=UpgradePreservation.chunkSourceVersion(context);
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
            CompoundTag world=journal.worldVersions();int candidate=UpgradePreservation.chunkSourceVersion(world);
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
