package cyano.lootable.upgrade;

import static cyano.lootable.upgrade.NativeNbt.hasType;
import static cyano.lootable.upgrade.NativeNbt.typedList;
import static cyano.lootable.upgrade.NativeNbt.isListOf;
import static cyano.lootable.upgrade.NativeNbt.writeUuid;

import com.mojang.datafixers.*;
import com.mojang.serialization.Dynamic;
import com.mojang.datafixers.schemas.Schema;
import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.LenientJsonParser;
import net.minecraft.util.datafix.fixes.References;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

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
            if(result.getCompoundOrEmpty("Level").getBooleanOr("hasLegacyStructureData", false) && structures!=null)result=structures.apply(result);
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
        Tag currentSettings=kind.equals("level") && hasType(copy, "WorldGenSettings", 10)
                && currentWorldSettings(copy.getCompoundOrEmpty("WorldGenSettings"))?copy.get("WorldGenSettings").copy():null;
        if(currentSettings!=null)copy.remove("WorldGenSettings");
        Tag currentBiomes=null;
        if(kind.equals("chunk")) {
            CompoundTag level=TerrainChunkData.payload(copy);
            // A damaged stamp can replay the old 2D biome expansion on an
            // already-current 3D array. Protect that field alone so older
            // vanilla entities and containers still receive their own fixes.
            if(source<2202 && target>=2202 && hasType(level, "Biomes", 11)
                    && level.getIntArray("Biomes").orElseGet(() -> new int[0]).length==1024) {
                currentBiomes=level.get("Biomes").copy();level.remove("Biomes");
            }
            // Vanilla discards typed Sections/TileEntities from incomplete old
            // chunks. Keep stored terrain as well as entities and containers.
            if(source<1493 && (nonempty(level,"Sections") || nonempty(level,"Entities") || nonempty(level,"TileEntities"))) {
                level.putBoolean("TerrainPopulated",true);level.putBoolean("LightPopulated",true);
            }
            shieldList(typedList(level, TerrainChunkData.entityKey(copy), 10),source,tokens,level.getIntOr("xPos", 0),level.getIntOr("zPos", 0),true);
        } else if(kind.equals("entity_chunk")) {
            int[] position=copy.getIntArray("Position").orElseGet(() -> new int[0]);
            shieldList(typedList(copy, "Entities", 10),source,tokens,position[0],position[1],true);
        } else if(kind.equals("entity") || kind.equals("entity_tree"))copy=shieldEntity(copy,source,tokens,0,0,false);
        else if(kind.equals("player")) {
            CompoundTag vehicle=copy.getCompoundOrEmpty("RootVehicle");
            if(hasType(vehicle, "Entity", 10))vehicle.put("Entity",shieldEntity(vehicle.getCompoundOrEmpty("Entity"),source,tokens,0,0,false));
            for(String shoulder:new String[]{"ShoulderEntityLeft","ShoulderEntityRight"})if(hasType(copy, shoulder, 10))copy.put(shoulder,shieldEntity(copy.getCompoundOrEmpty(shoulder),source,tokens,0,0,false));
        }
        // The 4290 fix expects a JSON string even when a legacy writer
        // carried a plain spawn-egg entity name past the old 1458 text fix.
        prepareEggs(copy,source,tokens,source>=1458 && source<4290 && target>=4290);
        CompoundTag result;
        if(currentBiomes!=null && target>=2832) {
            // Cross the old 2D expansion on a copy without the already-3D
            // array, then restore it before native18 consumes it into section
            // biome palettes. A detached post-Level tag would lose this data.
            result=nativeConvert(type,copy,source,2202);
            if(!hasType(result, "Level", 10))throw new IllegalStateException("Native biome prefix lost the legacy Level payload");
            result.getCompoundOrEmpty("Level").put("Biomes",currentBiomes);currentBiomes=null;
            result=nativeConvert(type,result,2202,target);
        } else result=nativeConvert(type,copy,source,target);
        result=(CompoundTag)restore(result,tokens);
        if(currentBiomes!=null) {
            if(!hasType(result, "Level", 10))throw new IllegalStateException("Protected legacy biomes require staged native height conversion");
            result.getCompoundOrEmpty("Level").put("Biomes",currentBiomes);
        }
        if(currentSettings!=null)result.put(target >= 4771 ? "world_gen_settings" : "WorldGenSettings",updateCurrentWorldSettings((CompoundTag)currentSettings,source,target));
        for(String token:tokens.values.keySet())if(!Integer.valueOf(1).equals(tokens.restored.get(token)))throw new IllegalStateException("Native conversion lost or duplicated protected Lootable Bodies data");
        if(kind.equals("chunk")) {
            // Native height conversion flattens Level but leaves DataVersion
            // for its ChunkStorage caller. Validate the converted payload with
            // the proven conversion version, not a recovered stale input stamp.
            // chunkVersion has already rejected genuinely future flat inputs.
            result.putInt("DataVersion",Math.max(source,target));
            CompoundTag level=TerrainChunkData.payload(result);ListTag entities=typedList(level, TerrainChunkData.entityKey(result), 10);
            if(!UpgradeJournal.corpses(entities).isEmpty()) {
                if(!hasType(level, "xPos", 3) || !hasType(level, "zPos", 3))throw new IllegalStateException("Saved terrain has no containing coordinates for corpse recovery");
                SavedCorpsePosition.prepare(entities,level.getIntOr("xPos", 0),level.getIntOr("zPos", 0));
            }
        } else if(kind.equals("entity_chunk")) {
            int[] position=result.getIntArray("Position").orElseGet(() -> new int[0]);SavedCorpsePosition.prepare(typedList(result, "Entities", 10),position[0],position[1]);
        }
        if(kind.equals("chunk") || kind.equals("entity_chunk"))result.putInt("DataVersion",Math.max(source,target));
        return result;
    }
    private CompoundTag nativeConvert(DSL.TypeReference type,CompoundTag input,int source,int target) {
        if(source<704 && target>=705) {
            // The old item hook writes Chest/Banner/Pig names back while the
            // 704/705 schemas expect namespaced IDs. Cross that boundary on
            // copies before later item and flattening fixes run.
            input=NativeNbt.update(delegate,type,input,source,703);
            bridgeIdentifiers(input,type.typeName());
            source=705;
        }
        if (type == References.CHUNK && source < 1458 && target >= 1458
                && legacyNamedInventoryPresent(input)) {
            int beforeNames = Math.max(source, 1456);
            if (source < beforeNames) input = NativeNbt.update(delegate, type, input, source, beforeNames);
            input = crossBlockEntityNameBoundary(input, beforeNames);
            source = 1458;
        }
        return source<target?NativeNbt.update(delegate,type,input,source,target):input;
    }

    private static final Set<String> LEGACY_NAMEABLE_INVENTORIES = Set.of(
            "minecraft:brewing_stand", "minecraft:chest", "minecraft:trapped_chest",
            "minecraft:dispenser", "minecraft:dropper", "minecraft:furnace",
            "minecraft:hopper", "minecraft:shulker_box");

    private static boolean legacyNamedInventoryPresent(CompoundTag chunk) {
        return typedList(chunk.getCompoundOrEmpty("Level"), "TileEntities", 10).stream()
                .map(CompoundTag.class::cast).anyMatch(NativeSafeFixer::hasLegacyNamedInventory);
    }

    private static boolean hasLegacyNamedInventory(CompoundTag block) {
        return LEGACY_NAMEABLE_INVENTORIES.contains(block.getStringOr("id", ""))
                && !typedList(block, "Items", 10).isEmpty();
    }

    private CompoundTag crossBlockEntityNameBoundary(CompoundTag chunk, int source) {
        // The 1458 block-entity name fix can serialize nested entity names
        // through the old string codec after the entity name fix has run.
        // Apply the same native fix to each item independently across this
        // boundary, then restore the list to its verified block entity.
        var blocks = typedList(chunk.getCompoundOrEmpty("Level"), "TileEntities", 10);
        Map<Integer, ListTag> inventories = new LinkedHashMap<>();
        Map<Integer, CompoundTag> identities = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            if (!hasLegacyNamedInventory(block)) continue;
            ListTag items = new ListTag();
            for (Tag entry : typedList(block, "Items", 10)) {
                CompoundTag original = (CompoundTag) entry;
                CompoundTag updated = NativeNbt.update(delegate, References.ITEM_STACK, original.copy(), source, 1458);
                if (!hasType(updated, "id", 8) || !Objects.equals(original.get("Slot"), updated.get("Slot"))
                        || !Objects.equals(original.get("Count"), updated.get("Count"))) {
                    throw new IllegalStateException("Native legacy block inventory name conversion changed a stack");
                }
                items.add(updated);
            }
            block.remove("Items");
            inventories.put(i, items);
            identities.put(i, block.copy());
        }
        CompoundTag result = NativeNbt.update(delegate, References.CHUNK, chunk, source, 1458);
        var converted = typedList(result.getCompoundOrEmpty("Level"), "TileEntities", 10);
        if (converted.size() != blocks.size()) throw new IllegalStateException("Native name conversion changed block entities");
        for (var entry : inventories.entrySet()) {
            CompoundTag block = converted.getCompoundOrEmpty(entry.getKey());
            CompoundTag original = identities.get(entry.getKey());
            for (String key : List.of("id", "x", "y", "z")) {
                if (!Objects.equals(block.get(key), original.get(key))) {
                    throw new IllegalStateException("Native name conversion changed block entity identity");
                }
            }
            if (block.contains("Items")) throw new IllegalStateException("Native name conversion replaced a detached inventory");
            block.put("Items", entry.getValue());
        }
        return result;
    }
    private static boolean currentWorldSettings(CompoundTag nbt) {
        if(!hasType(nbt, "seed", 4) || !hasType(nbt, "dimensions", 10))return false;
        CompoundTag dimensions=nbt.getCompoundOrEmpty("dimensions");
        if(dimensions.isEmpty())return false;
        for(String key:dimensions.keySet()) {
            if(!hasType(dimensions, key, 10))return false;
            CompoundTag dimension=dimensions.getCompoundOrEmpty(key);
            if(!(hasType(dimension, "type", 8) || hasType(dimension, "type", 10))
                    || !hasType(dimension, "generator", 10) || !hasType(dimension.getCompoundOrEmpty("generator"), "type", 8))return false;
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
    private int entityChunkVersion(CompoundTag root) {
        int[] position=root.getIntArray("Position").orElseGet(() -> new int[0]);
        Tag entities=root.get("Entities");
        if(!hasType(root, "Position", 11) || position.length!=2 || !isListOf(entities, 10))
            throw new IllegalStateException("Malformed saved entity chunk; refusing unsafe construction");
        int version=hasType(root, "DataVersion", 3)?root.getIntOr("DataVersion", 0):-1;
        if(version>UpgradePreservation.MINECRAFT_VERSION)
            throw new IllegalStateException("Saved entity chunk comes from a newer Minecraft version");
        // Terrain Sections/Biomes cannot identify the separate entity format.
        // Current owned markers and the matching immutable journal can prove
        // the minimum native source even when an outer stamp was damaged.
        UpgradeJournal journal=preserved!=null?preserved:UpgradePreservation.journal();
        for(UpgradeJournal.LocatedCorpse body:UpgradeJournal.corpses((ListTag)entities)) {
            if(body.entity.getStringOr("id", "").equals("lootablebodies:corpse")
                    && hasType(body.entity, UpgradePreservation.VERSION, 3)
                    && body.entity.getIntOr(UpgradePreservation.VERSION, 0)==UpgradePreservation.DATA_VERSION)
                version=Math.max(version,UpgradePreservation.MINECRAFT_VERSION);
            else if(journal!=null && CorpseIdentity.present(body.entity))try {
                CompoundTag original=journal.originalFor(CorpseIdentity.read(body.entity),position[0],position[1]);
                if(original!=null) {
                    int proven=UpgradePreservation.chunkSourceVersion(original.getCompoundOrEmpty("SourceVersions"));
                    if(proven>=512 && proven<=UpgradePreservation.MINECRAFT_VERSION)version=Math.max(version,proven);
                }
            }catch(IOException failure){throw new IllegalStateException("Entity chunk source context could not be verified",failure);}
        }
        if(version<512)throw new IllegalStateException("Cannot prove saved entity chunk source version");
        return version;
    }
    private void bridgeIdentifiers(CompoundTag nbt,String kind) {
        switch(kind) {
        case "chunk":
            CompoundTag level=nbt.getCompoundOrEmpty("Level");
            bridgeList(level,"Entities","entity_tree");bridgeList(level,"TileEntities","block_entity");break;
        case "player":
            bridgeList(nbt,"Inventory","item_stack");bridgeList(nbt,"EnderItems","item_stack");
            bridgeField(nbt.getCompoundOrEmpty("RootVehicle"),"Entity","entity_tree");
            bridgeField(nbt,"ShoulderEntityLeft","entity_tree");bridgeField(nbt,"ShoulderEntityRight","entity_tree");break;
        case "item_stack":
            CompoundTag tag=nbt.getCompoundOrEmpty("tag");
            bridgeField(tag,"EntityTag","entity_tree");bridgeField(tag,"BlockEntityTag","block_entity");break;
        case "entity": case "entity_tree":
            renameIdentifier(nbt,References.ENTITY_TREE);
            bridgeList(nbt,"Passengers","entity_tree");bridgeField(nbt,"Riding","entity_tree");
            for(String key:new String[]{"Equipment","ArmorItems","HandItems","Items","Inventory"})bridgeList(nbt,key,"item_stack");
            for(String key:new String[]{"Item","FireworksItem","Potion","SaddleItem","ArmorItem","DecorItem"})bridgeField(nbt,key,"item_stack");
            bridgeField(nbt,"TileEntityData","block_entity");
            for(Tag entry:typedList(nbt.getCompoundOrEmpty("Offers"), "Recipes", 10))
                for(String key:new String[]{"buy","buyB","sell"})bridgeField((CompoundTag)entry,key,"item_stack");
            bridgeSpawner(nbt);break;
        case "block_entity":
            renameIdentifier(nbt,References.BLOCK_ENTITY);
            bridgeList(nbt,"Items","item_stack");bridgeField(nbt,"RecordItem","item_stack");bridgeSpawner(nbt);break;
        case "structure":
            for(Tag entry:typedList(nbt, "entities", 10))bridgeField((CompoundTag)entry,"nbt","entity_tree");
            for(Tag entry:typedList(nbt, "blocks", 10))bridgeField((CompoundTag)entry,"nbt","block_entity");break;
        default: break;
        }
    }
    private void renameIdentifier(CompoundTag nbt,DSL.TypeReference type) {
        if(!hasType(nbt, "id", 8))return;
        CompoundTag id=new CompoundTag();id.putString("id",nbt.getStringOr("id", ""));
        CompoundTag fixed=cyano.lootable.upgrade.NativeNbt.update(delegate,type,id,703,705);
        if(!hasType(fixed, "id", 8))throw new IllegalStateException("Vanilla identifier conversion lost its type");
        nbt.putString("id",fixed.getStringOr("id", ""));
    }
    private void bridgeField(CompoundTag parent,String key,String kind) {
        if(hasType(parent, key, 10))bridgeIdentifiers(parent.getCompoundOrEmpty(key),kind);
    }
    private void bridgeList(CompoundTag parent,String key,String kind) {
        for(Tag entry:typedList(parent, key, 10))bridgeIdentifiers((CompoundTag)entry,kind);
    }
    private void bridgeSpawner(CompoundTag nbt) {
        bridgeField(nbt,"SpawnData","entity_tree");
        for(Tag entry:typedList(nbt, "SpawnPotentials", 10))bridgeField((CompoundTag)entry,"Entity","entity_tree");
    }
    private static boolean nonempty(CompoundTag nbt,String key){return hasType(nbt, key, 9) && !typedList(nbt, key, 10).isEmpty();}
    private void shieldList(ListTag entities,int source,Tokens tokens,int x,int z,boolean saved) {
        for(int i=0;i<entities.size();i++)entities.set(i,shieldEntity(entities.getCompoundOrEmpty(i),source,tokens,x,z,saved));
    }
    private CompoundTag shieldEntity(CompoundTag entity,int source,Tokens tokens,int x,int z,boolean saved) {
        if(UpgradeJournal.corpse(entity)) {
            CompoundTag context=sourceContext(entity,source,x,z,saved);
            CompoundTag safe=stageCorpse(entity,context);
            ListTag riders=typedList(safe, "Passengers", 10);
            for(int i=0;i<riders.size();i++) {
                CompoundTag child=riders.getCompoundOrEmpty(i);
                CompoundTag converted;
                if(UpgradeJournal.corpse(child)) {
                    Tokens nested=new Tokens();CompoundTag placeholder=shieldEntity(child,source,nested,x,z,saved);
                    converted=(CompoundTag)restore(placeholder,nested);
                } else converted=cyano.lootable.upgrade.NativeNbt.update(this,References.ENTITY_TREE,child.copy(),source,UpgradePreservation.MINECRAFT_VERSION);
                riders.set(i,converted);
            }
            String token=tokens.put(safe,false);CompoundTag placeholder=new CompoundTag();placeholder.putString("id",source<704?"ArmorStand":"minecraft:armor_stand");placeholder.putString(tokens.key,token);return placeholder;
        }
        shieldList(typedList(entity, "Passengers", 10),source,tokens,x,z,saved);return entity;
    }
    private CompoundTag sourceContext(CompoundTag entity,int version,int x,int z,boolean saved) {
        if(entity.getStringOr("id", "").equals("lootablebodies:corpse") && hasType(entity, UpgradePreservation.VERSION, 3) && entity.getIntOr(UpgradePreservation.VERSION, 0)==UpgradePreservation.DATA_VERSION){CompoundTag context=new CompoundTag();context.putInt("DataVersion",UpgradePreservation.MINECRAFT_VERSION);return context;}
        UpgradeJournal journal=preserved!=null?preserved:UpgradePreservation.journal();
        if(saved) {
            try {
                CompoundTag original=journal==null || !CorpseIdentity.present(entity)?null:journal.originalFor(CorpseIdentity.read(entity),x,z);
                if(original==null)throw new IllegalStateException("Unpreserved corpse in saved chunk; refusing unsafe conversion");
                return original.getCompoundOrEmpty("SourceVersions").copy();
            }catch(IOException damaged){throw new IllegalStateException("Corpse preservation could not be verified",damaged);}
        }
        CompoundTag context=new CompoundTag();int marker=entity.getIntOr(UpgradePreservation.VERSION, 0);
        context.putInt("DataVersion",entity.getStringOr("id", "").equals("lootablebodies.Corpse")?512:marker==1?922:marker==2?1343:marker==3?1631:marker==4?1976:marker==5?2230:marker==6?2586:marker==7?2730:marker==8?2975:marker==9?3337:marker==10?3465:marker==11?3839:marker==12?3955:marker==13?4671:version);return context;
    }
    static CompoundTag stageCorpse(CompoundTag raw,CompoundTag context) {
        if(hasType(raw, UpgradePreservation.VERSION, 3) && raw.getIntOr(UpgradePreservation.VERSION, 0)>UpgradePreservation.DATA_VERSION)throw new IllegalStateException("This corpse was saved by a newer Lootable Bodies format");
        CompoundTag safe=raw.copy();boolean current=raw.getStringOr("id", "").equals("lootablebodies:corpse") && hasType(raw, UpgradePreservation.VERSION, 3) && raw.getIntOr(UpgradePreservation.VERSION, 0)==UpgradePreservation.DATA_VERSION;
        if(CorpseIdentity.present(raw)) {
            writeUuid(safe, "UUID", CorpseIdentity.read(raw));safe.remove("UUIDMost");safe.remove("UUIDLeast");
        }
        safe.putString("id","lootablebodies:corpse");safe.putInt(UpgradePreservation.VERSION,UpgradePreservation.DATA_VERSION);
        if(current)return safe;
        safe.put(UpgradePreservation.SOURCES,context.copy());
        ListTag records=new ListTag();boolean custom=list(raw.get("Equipment"));
        retainInventory(records,"Equipment",raw.get("Equipment"),context);
        retainInventory(records,"Aux",raw.get("Aux"),context);
        if(!custom) {
            retainMirror(records,typedList(raw, "ArmorItems", 10),new int[]{3,2,1,0},context);
            retainMirror(records,typedList(raw, "HandItems", 10),new int[]{4,5},context);
        }
        Tag recovery=raw.get("LootableBodiesUnresolved");
        if(list(recovery))for(Tag value:(ListTag)recovery) {
            CompoundTag record=((CompoundTag)value).copy();
            if(hasType(record, "Source", 8) && hasType(record, "Data", 10) && !record.contains("SourceVersions") && (record.getStringOr("Source", "").equals("Equipment") || record.getStringOr("Source", "").equals("Aux")))record.put("SourceVersions",context.copy());
            records.add(record);
        }else if(recovery!=null)retain(records,"Recovery",recovery,context);
        safe.put("Equipment",new ListTag());safe.remove("Aux");safe.remove("ArmorItems");safe.remove("HandItems");
        if(!records.isEmpty())safe.put("LootableBodiesUnresolved",records);else safe.remove("LootableBodiesUnresolved");
        // These are durable existing recovery records, not a transient envelope:
        // Anvil may save this compound before an entity reader runs.
        safe.remove("LootableBodiesUpgradePayload");safe.remove("LootableBodiesInventorySource");
        if(hasType(safe, "CustomName", 8) && context.getIntOr("DataVersion", 0)<1514)
            safe.putString("CustomName",new JsonPrimitive(safe.getStringOr("CustomName", "")).toString());
        return safe;
    }
    private static boolean list(Tag nbt){return isListOf(nbt, 10);}
    private static void retainInventory(ListTag records,String source,Tag data,CompoundTag context) {
        if(data==null)return;
        if(list(data))for(Tag entry:(ListTag)data)retain(records,source,entry,context);
        else retain(records,source+"List",data,context);
    }
    private static void retainMirror(ListTag records,ListTag items,int[] slots,CompoundTag context) {
        for(int i=0;i<Math.min(items.size(),slots.length);i++) {
            CompoundTag item=items.getCompoundOrEmpty(i).copy();if(item.isEmpty())continue;item.putByte("Slot",(byte)slots[i]);retain(records,"Equipment",item,context);
        }
    }
    private static void retain(ListTag records,String source,Tag data,CompoundTag context) {
        CompoundTag record=new CompoundTag();record.putString("Source",source);record.put("Data",data.copy());if(!context.isEmpty())record.put("SourceVersions",context.copy());records.add(record);
    }
    private void prepareEggs(Tag value,int source,Tokens tokens,boolean bridgeLegacyNames) {
        if(value instanceof CompoundTag) {
            CompoundTag item=(CompoundTag)value;
            if(item.getStringOr("id", "").equals("minecraft:spawn_egg") && hasType(item.getCompoundOrEmpty("tag"), "EntityTag", 10) && UpgradeJournal.corpse(item.getCompoundOrEmpty("tag").getCompoundOrEmpty("EntityTag"))) {
                CompoundTag entity=item.getCompoundOrEmpty("tag").getCompoundOrEmpty("EntityTag");CompoundTag context=sourceContext(entity,source,0,0,false);
                CompoundTag safe=stageCorpse(entity,context);item.putString("id","lootablebodies:corpse_spawn_egg");item.getCompoundOrEmpty("tag").remove("EntityTag");item.putString(tokens.key,tokens.put(safe,true));
            }
            if (bridgeLegacyNames) bridgeSpawnEggName(item);
            for(String key:new ArrayList<>(item.keySet()))prepareEggs(item.get(key),source,tokens,bridgeLegacyNames);
        }else if(value instanceof ListTag)for(Tag entry:(ListTag)value)prepareEggs(entry,source,tokens,bridgeLegacyNames);
    }
    private static void bridgeSpawnEggName(CompoundTag item) {
        String id = item.getStringOr("id", "");
        if (!id.startsWith("minecraft:") || !(id.endsWith("_spawn_egg") || id.equals("minecraft:spawn_egg"))) return;
        CompoundTag entity;
        if (hasType(item, "count", 99)) {
            entity = item.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:entity_data");
        } else if (hasType(item, "Count", 99)) {
            entity = item.getCompoundOrEmpty("tag").getCompoundOrEmpty("EntityTag");
        } else {
            return;
        }
        if (!hasType(entity, "id", 8) || !(entity.get("CustomName") instanceof StringTag name)) return;
        try {
            LenientJsonParser.parse(name.value());
        } catch (JsonSyntaxException plainText) {
            // Match the native fallback's literal value without sending
            // invalid JSON through the native converter. The source is sealed.
            entity.putString("CustomName", new JsonPrimitive(name.value()).toString());
        }
    }
    private Tag restore(Tag value,Tokens tokens) {
        if(value instanceof CompoundTag) {
            CompoundTag nbt=(CompoundTag)value;
            if(hasType(nbt, tokens.key, 8)) {
                String id=nbt.getStringOr(tokens.key, "");Protected original=tokens.values.get(id);if(original==null)throw new IllegalStateException("Unknown preservation token");
                tokens.restored.merge(id,1,Integer::sum);
                if(!original.egg)return original.data.copy();
                nbt.remove(tokens.key);
                if(hasType(nbt, "count", 99)) {
                    CompoundTag components=nbt.getCompoundOrEmpty("components");
                    components.put("minecraft:entity_data",original.data.copy());
                    nbt.put("components",components);
                } else {
                    CompoundTag tag=nbt.getCompoundOrEmpty("tag");
                    tag.put("EntityTag",original.data.copy());
                    nbt.put("tag",tag);
                }
            }
            for(String key:new ArrayList<>(nbt.keySet()))nbt.put(key,restore(nbt.get(key),tokens));return nbt;
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
        ListTag sections=typedList(root.getCompoundOrEmpty("Level"), "Sections", 10);for(int i=0;i<sections.size();i++)if(hasType(sections.getCompoundOrEmpty(i), key, type))return true;return false;
    }
    private static boolean compatible(int version,boolean legacy,boolean palette) {
        return version>=512 && version<=UpgradePreservation.MINECRAFT_VERSION && (!legacy || version<1451) && (!palette || version>=1451);
    }
    private static int chunkVersion(CompoundTag chunk,int requested) {
        if(TerrainChunkData.payload(chunk)==chunk)return TerrainChunkData.flattenedVersion(chunk);
        boolean legacy=sections(chunk,"Blocks",7),palette=sections(chunk,"Palette",9);
        if(legacy && palette)throw new IllegalStateException("Mixed old and flattened sections cannot be converted safely");
        int source=hasType(chunk, "DataVersion", 3)?chunk.getIntOr("DataVersion", 0):requested;
        UpgradeJournal journal=UpgradePreservation.journal();CompoundTag level=chunk.getCompoundOrEmpty("Level");
        int preserved=-1;
        for(UpgradeJournal.LocatedCorpse located:UpgradeJournal.corpses(typedList(level, "Entities", 10))) {
            try {
                CompoundTag original=journal==null || !CorpseIdentity.present(located.entity)?null:journal.originalFor(CorpseIdentity.read(located.entity),level.getIntOr("xPos", 0),level.getIntOr("zPos", 0));
                if(original!=null) {
                    CompoundTag context=original.getCompoundOrEmpty("SourceVersions");int candidate=UpgradePreservation.chunkSourceVersion(context);
                    if(!context.getBooleanOr("Ambiguous", false) && compatible(candidate,legacy,palette)) {
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
            if(!compatible(candidate,legacy,palette))candidate=world.getIntOr("WorldDataVersion", 0);
            if(!compatible(candidate,legacy,palette) && hasType(world.getCompoundOrEmpty("WorldVersion"), "Id", 3))candidate=world.getCompoundOrEmpty("WorldVersion").getIntOr("Id", 0);
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
