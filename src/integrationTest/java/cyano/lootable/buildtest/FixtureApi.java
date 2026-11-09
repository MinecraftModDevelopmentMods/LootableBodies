package cyano.lootable.buildtest;

import static cyano.lootable.buildtest.TestNativeNbt.readUuid;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Read-only entity snapshots across the target's separate world APIs. */
final class FixtureApi {
    private FixtureApi() { }
    static void spawn(net.minecraft.server.level.ServerPlayer player,net.minecraft.core.BlockPos position,boolean forced,boolean notify,net.minecraft.resources.ResourceKey<Level> dimension) {
        var spawn = net.minecraft.world.level.storage.LevelData.RespawnData.of(dimension, position, 0F, 0F);
        player.setRespawnPosition(new net.minecraft.server.level.ServerPlayer.RespawnConfig(spawn, forced), notify);
    }
    static net.minecraft.resources.ResourceKey<Level> dimensionKey(int id) {
        if(id==0)return Level.OVERWORLD;if(id==-1)return Level.NETHER;if(id==1)return Level.END;
        throw new IllegalArgumentException("Unknown fixture dimension "+id);
    }
    static int dimensionId(Level world) {
        if(world.dimension()==Level.OVERWORLD)return 0;
        if(world.dimension()==Level.NETHER)return -1;
        if(world.dimension()==Level.END)return 1;
        throw new IllegalArgumentException("Unknown fixture dimension "+world.dimension());
    }
    static net.minecraft.world.level.storage.PlayerDataStorage playerData(MinecraftServer server) {
        java.util.List<java.lang.reflect.Field> fields=new java.util.ArrayList<>();
        for(Class<?> type=server.getClass();type!=null;type=type.getSuperclass())for(java.lang.reflect.Field field:type.getDeclaredFields())
            if(field.getType()==net.minecraft.world.level.storage.PlayerDataStorage.class)fields.add(field);
        if(fields.size()!=1)throw new IllegalStateException("Ambiguous fixture player reader");
        try {java.lang.reflect.Field field=fields.get(0);field.setAccessible(true);return (net.minecraft.world.level.storage.PlayerDataStorage)field.get(server);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    @OnlyIn(Dist.CLIENT)
    static final class ClientWorlds {
        static void create(net.minecraft.client.Minecraft client,String folder,String title,net.minecraft.world.level.GameType mode,boolean commands) {
            client.createWorldOpenFlows().createFreshLevel(folder,
                new net.minecraft.world.level.LevelSettings(title,mode,false,net.minecraft.world.Difficulty.NORMAL,commands,
                    new net.minecraft.world.level.gamerules.GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS),net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                new net.minecraft.world.level.levelgen.WorldOptions(4267321L,false,false),
                access->access.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(),new net.minecraft.client.gui.screens.TitleScreen());
        }
    }
    static List<Entity> entities(Level world) {
        if (world instanceof ServerLevel) {
            List<Entity> result=new ArrayList<>();((ServerLevel)world).getAllEntities().forEach(result::add);return result;
        }
        return ClientEntities.read(world);
    }
    static Entity loadEntity(CompoundTag data, Level world) {
        return net.minecraft.world.entity.EntityType.loadEntityRecursive(data, world, net.minecraft.world.entity.EntitySpawnReason.LOAD, entity -> entity);
    }
    static boolean saveEntity(Entity entity, CompoundTag data) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        output.buildResult().merge(data.copy());
        boolean saved = entity.saveAsPassenger(output);
        for (String key : java.util.Set.copyOf(data.keySet())) data.remove(key);
        data.merge(output.buildResult());
        return saved;
    }

    static void loadEntityData(Entity entity, CompoundTag data) {
        entity.load(TestNativeNbt.input(entity, data));
    }

    static net.minecraft.nbt.ListTag saveInventory(net.minecraft.world.entity.player.Player player) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess());
        player.getInventory().save(output.list("Inventory", net.minecraft.world.ItemStackWithSlot.CODEC));
        ListTag inventory = output.buildResult().getListOrEmpty("Inventory");
        var slots = new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.FEET, net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND};
        for (int index = 0; index < slots.length; index++) {
            var stack = player.getItemBySlot(slots[index]);
            if (stack.isEmpty()) continue;
            CompoundTag item = FixtureStackData.save(stack);
            item.putByte("Slot", (byte) (index == 4 ? 150 : 100 + index));
            inventory.add(item);
        }
        return inventory;
    }

    static net.minecraft.nbt.ListTag saveEnderInventory(ServerPlayer player) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, player.registryAccess());
        player.getEnderChestInventory().storeAsSlots(output.list("EnderItems", net.minecraft.world.ItemStackWithSlot.CODEC));
        return output.buildResult().getListOrEmpty("EnderItems");
    }

    static net.minecraft.network.protocol.game.ServerboundContainerClickPacket clickPacket(
            ServerPlayer player, int stateId, int slot, int button,
            net.minecraft.world.inventory.ClickType type, net.minecraft.world.item.ItemStack predicted) {
        var ops = player.registryAccess().createSerializationContext(net.minecraft.util.HashOps.CRC32C_INSTANCE);
        net.minecraft.network.HashedPatchMap.HashGenerator hash = component -> component.encodeValue(ops).getOrThrow().asInt();
        var changed = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<net.minecraft.network.HashedStack>();
        if (predicted != null && slot >= 0) changed.put(slot, net.minecraft.network.HashedStack.create(predicted, hash));
        return new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                player.containerMenu.containerId, stateId, (short)slot, (byte)button, type, changed,
                net.minecraft.network.HashedStack.create(player.containerMenu.getCarried(), hash));
    }

    static void readAdditional(cyano.lootable.entities.EntityLootableBody body, CompoundTag data) {
        body.readAdditionalSaveData(TestNativeNbt.input(body, data));
    }

    static void writeAdditional(cyano.lootable.entities.EntityLootableBody body, CompoundTag data) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, body.registryAccess());
        output.buildResult().merge(data.copy());
        body.addAdditionalSaveData(output);
        for (String key : java.util.Set.copyOf(data.keySet())) data.remove(key);
        data.merge(output.buildResult());
    }

    static Entity readChunkEntity(CompoundTag data, ServerLevel world, net.minecraft.world.level.chunk.LevelChunk chunk) {
        // Invoke the actual native chunk serializer; do not replace its admission callback.
        CompoundTag root=new CompoundTag();net.minecraft.nbt.ListTag list=new net.minecraft.nbt.ListTag();list.add(data.copy());root.put("entities",list);
        root.putInt("xPos",chunk.getPos().x);root.putInt("zPos",chunk.getPos().z);root.put("sections",new net.minecraft.nbt.ListTag());root.putInt("DataVersion",4671);
        // Current fixture NBT follows the real native conversion boundary too.
        // The post-load admission callback remains Minecraft's own method.
        CompoundTag fixed=cyano.lootable.upgrade.NativeNbt.update(
                cyano.lootable.upgrade.UpgradePreservation.wrapper(net.minecraft.util.datafix.DataFixers.getDataFixer()),
                net.minecraft.util.datafix.fixes.References.CHUNK,root,4671,4671);
        try {
            java.lang.reflect.Method nativeRead=null;
            for(java.lang.reflect.Method method:net.minecraft.world.level.chunk.storage.SerializableChunkData.class.getDeclaredMethods())
                if(java.lang.reflect.Modifier.isStatic(method.getModifiers()) && !method.isSynthetic() && method.getReturnType()==net.minecraft.world.level.chunk.LevelChunk.PostLoadProcessor.class && java.util.Arrays.equals(method.getParameterTypes(),new Class<?>[]{ServerLevel.class,java.util.List.class,java.util.List.class})) {
                    if(nativeRead!=null)throw new IllegalStateException("Ambiguous native entity admission");nativeRead=method;
                }
            if(nativeRead==null)throw new IllegalStateException("Missing native entity admission");
            nativeRead.setAccessible(true);
            net.minecraft.world.level.chunk.LevelChunk.PostLoadProcessor processor=(net.minecraft.world.level.chunk.LevelChunk.PostLoadProcessor)nativeRead.invoke(null,world, compounds(fixed, "entities"), compounds(fixed, "block_entities"));
            if(processor==null)throw new IllegalStateException("Native entity admission returned no processor");
            processor.run(chunk);
            java.util.UUID id=readUuid(TestNativeNbt.typedList(fixed, "entities", 10).getCompoundOrEmpty(0), "UUID");
            return sectionEntities(world,chunk.getPos().toLong()).filter(entity->entity.getUUID().equals(id)).findFirst().orElse(null);
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native chunk admission failed",failure);}
    }
    private static List<CompoundTag> compounds(CompoundTag data, String key) {
        return TestNativeNbt.typedList(data, key, 10).stream().map(CompoundTag.class::cast).toList();
    }

    static List<Entity> entities(ServerLevel world,int chunkX,int chunkZ) {
        // Forge 50 may have admitted a drop to the native section before its
        // chunk is tracked. Count both stores, once per actual entity.
        var all=new java.util.LinkedHashSet<>(entities(world));
        sectionEntities(world,net.minecraft.world.level.ChunkPos.asLong(chunkX,chunkZ)).forEach(all::add);
        return new ArrayList<>(all);
    }
    private static java.util.stream.Stream<Entity> sectionEntities(ServerLevel world,long chunk) {
        try {
            Object manager=singleField(ServerLevel.class,net.minecraft.world.level.entity.PersistentEntitySectionManager.class).get(world);
            var sections=(net.minecraft.world.level.entity.EntitySectionStorage<?>)
                    singleField(manager.getClass(),net.minecraft.world.level.entity.EntitySectionStorage.class).get(manager);
            return sections.getExistingSectionsInChunk(chunk).flatMap(section->section.getEntities())
                    .filter(Entity.class::isInstance).map(Entity.class::cast);
        }catch(IllegalAccessException failure){throw new IllegalStateException("Cannot read the native entity section",failure);}
    }
    private static java.lang.reflect.Field singleField(Class<?> owner,Class<?> type) {
        java.lang.reflect.Field found=null;
        for(java.lang.reflect.Field field:owner.getDeclaredFields())if(field.getType()==type) {
            if(found!=null)throw new IllegalStateException("Ambiguous native field of type "+type.getName());found=field;
        }
        if(found==null)throw new IllegalStateException("Missing native field of type "+type.getName());found.setAccessible(true);return found;
    }
    static boolean sectionContains(Entity entity,int chunkX,int chunkZ) {
        try {
            Object callback=singleField(Entity.class,net.minecraft.world.level.entity.EntityInLevelCallback.class).get(entity);
            java.lang.reflect.Field key=null,storage=null;
            for(java.lang.reflect.Field field:callback.getClass().getDeclaredFields()) {
                if(field.getType()==long.class){if(key!=null)throw new IllegalStateException("Ambiguous native entity section key");key=field;}
                if(field.getType()==net.minecraft.world.level.entity.EntitySection.class){if(storage!=null)throw new IllegalStateException("Ambiguous native entity section");storage=field;}
            }
            if(key==null || storage==null)return false;key.setAccessible(true);storage.setAccessible(true);
            long current=key.getLong(callback);net.minecraft.core.SectionPos position=net.minecraft.core.SectionPos.of(current);
            // Legacy native admission indexes entities without going through
            // ServerLevel.addEntity's Forge onAddedToWorld callback.
            return position.x()==chunkX && position.z()==chunkZ
                    && current==net.minecraft.core.SectionPos.asLong(entity.blockPosition())
                    && ((net.minecraft.world.level.entity.EntitySection<?>)storage.get(callback)).getEntities().anyMatch(member->member==entity);
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot inspect native entity section",failure);}
    }
    static void awaitEntityInbox(ServerLevel world,int x,int z) {
        RuntimeChecks.require(world.getServer().isSameThread(),"Entity readiness must run on the native server thread");
        try {
            net.minecraft.world.level.entity.PersistentEntitySectionManager<?> manager=
                    (net.minecraft.world.level.entity.PersistentEntitySectionManager<?>)singleField(
                            ServerLevel.class,net.minecraft.world.level.entity.PersistentEntitySectionManager.class).get(world);
            long chunk=net.minecraft.world.level.ChunkPos.asLong(x,z);
            try {
                NativeEntityInbox.await(manager,chunk,()->{
                    // The real server loop services both executors. Fixture
                    // callbacks execute before that loop, so pump both queues.
                    boolean serverTask=world.getServer().pollTask();
                    boolean chunkTask=world.getChunkSource().pollTask();
                    return serverTask || chunkTask;
                },System::nanoTime,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30),world.getServer()::managedBlock);
            }catch(IllegalStateException failure){
                net.minecraft.world.level.chunk.LevelChunk cached=world.getChunkSource().getChunkNow(x,z);
                String state="full="+(cached==null?"absent":cached.getFullStatus())+" manager="+manager.gatherStats();
                for(java.lang.reflect.Field field:manager.getClass().getDeclaredFields())
                    if(it.unimi.dsi.fastutil.longs.Long2ObjectMap.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);Object value=((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>)field.get(manager)).get(chunk);
                        if(value instanceof Enum)state+=" "+field.getName()+"="+value;
                    }
                System.out.println("NATIVE_ENTITY_INBOX_STATE chunk="+x+","+z+" "+state);throw failure;
            }
        }catch(IllegalAccessException failure){throw new IllegalStateException("Cannot inspect native entity inbox",failure);}
    }
    static void awaitTicking(Entity entity) {
        ServerLevel world=(ServerLevel)entity.level();int x=net.minecraft.util.Mth.floor(entity.getX())>>4,z=net.minecraft.util.Mth.floor(entity.getZ())>>4;
        world.setChunkForced(x,z,true);
        net.minecraft.server.level.ServerChunkCache provider=world.getChunkSource();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(!world.isPositionEntityTicking(entity.blockPosition())) {
            RuntimeChecks.require(System.nanoTime()<deadline,"Native entity-ticking chunk did not become ready");
            if(!provider.pollTask())Thread.yield();
        }
    }
    static void unload(ServerLevel world,int x,int z) {
        net.minecraft.server.level.ServerChunkCache provider=world.getChunkSource();
        world.setChunkForced(x,z,false);
        // Expire temporary native load tickets and execute real save/unload work.
        for(int tick=0;tick<1200 && provider.hasChunk(x,z);tick++) {
            provider.tick(()->true,true);
            for(int task=0;task<1000 && provider.pollTask();task++) { }
            if((tick&31)==0)Thread.yield();
        }
        provider.save(true);
        RuntimeChecks.require(!provider.hasChunk(x,z),"Fixture chunk remained loaded after native ticket expiry: "+x+","+z);
    }
    static void flush(MinecraftServer server) { server.saveAllChunks(true, true, false); }
    @SuppressWarnings("unchecked")
    static java.util.Set<net.minecraft.server.level.ServerPlayer> tracked(Entity entity) {
        try {
            Object manager=((ServerLevel)entity.level()).getChunkSource().chunkMap;
            java.lang.reflect.Field map=null;
            for(java.lang.reflect.Field field:manager.getClass().getDeclaredFields())
                if(it.unimi.dsi.fastutil.ints.Int2ObjectMap.class.isAssignableFrom(field.getType())) {
                    if(map!=null)throw new IllegalStateException("Ambiguous native tracking map"); map=field;
                }
            if(map==null)throw new IllegalStateException("Missing native tracking map");
            map.setAccessible(true);
            Object tracker=((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>)map.get(manager)).get(entity.getId());
            if(tracker==null)return java.util.Collections.emptySet();
            java.lang.reflect.Field players=null;
            for(java.lang.reflect.Field field:tracker.getClass().getDeclaredFields())
                if(java.util.Set.class.isAssignableFrom(field.getType())) {
                    if(players!=null)throw new IllegalStateException("Ambiguous native tracking set");players=field;
                }
            if(players==null)throw new IllegalStateException("Missing native tracking set");
            players.setAccessible(true);
            java.util.Set<net.minecraft.server.level.ServerPlayer> result=new java.util.HashSet<>();
            for(Object connection:(java.util.Set<?>)players.get(tracker)) {
                if(!(connection instanceof net.minecraft.server.network.ServerPlayerConnection))throw new IllegalStateException("Unexpected native tracking member");
                result.add(((net.minecraft.server.network.ServerPlayerConnection)connection).getPlayer());
            }
            return result;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot inspect native tracking",failure);}
    }
    @OnlyIn(Dist.CLIENT)
    private static final class ClientEntities {
        static List<Entity> read(Level world) {
            List<Entity> result = new ArrayList<>();
            ((net.minecraft.client.multiplayer.ClientLevel) world).entitiesForRendering().forEach(result::add);
            return result;
        }
    }
}
