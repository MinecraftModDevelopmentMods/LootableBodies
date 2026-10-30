package cyano.lootable.buildtest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Read-only entity snapshots across the target's separate world APIs. */
final class FixtureApi {
    private FixtureApi() { }
    static void spawn(net.minecraft.server.level.ServerPlayer player,net.minecraft.core.BlockPos position,boolean forced,boolean notify,net.minecraft.resources.ResourceKey<Level> dimension) {
        player.setRespawnPosition(dimension,position,0F,forced,notify);
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
            net.minecraft.core.RegistryAccess.RegistryHolder registries=net.minecraft.core.RegistryAccess.builtin();
            net.minecraft.core.Registry<net.minecraft.world.level.dimension.DimensionType> types=registries.registryOrThrow(net.minecraft.core.Registry.DIMENSION_TYPE_REGISTRY);
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes=registries.registryOrThrow(net.minecraft.core.Registry.BIOME_REGISTRY);
            net.minecraft.world.level.levelgen.WorldGenSettings settings=new net.minecraft.world.level.levelgen.WorldGenSettings(4267321L,false,false,
                net.minecraft.world.level.levelgen.WorldGenSettings.withOverworld(types,
                    net.minecraft.world.level.dimension.DimensionType.defaultDimensions(types,biomes,registries.registryOrThrow(net.minecraft.core.Registry.NOISE_GENERATOR_SETTINGS_REGISTRY),4267321L),
                    new net.minecraft.world.level.levelgen.FlatLevelSource(net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(biomes))));
            client.createLevel(folder,new net.minecraft.world.level.LevelSettings(title,mode,false,net.minecraft.world.Difficulty.NORMAL,commands,new net.minecraft.world.level.GameRules(),net.minecraft.world.level.DataPackConfig.DEFAULT),registries,settings);
        }
    }
    static List<Entity> entities(Level world) {
        if (world instanceof ServerLevel) {
            List<Entity> result=new ArrayList<>();((ServerLevel)world).getAllEntities().forEach(result::add);return result;
        }
        return ClientEntities.read(world);
    }
    static Entity loadEntity(CompoundTag data, Level world) {
        return net.minecraft.world.entity.EntityType.loadEntityRecursive(data, world, entity -> entity);
    }
    static Entity readChunkEntity(CompoundTag data, ServerLevel world, net.minecraft.world.level.chunk.LevelChunk chunk) {
        // Invoke the actual native chunk serializer; do not replace its admission callback.
        CompoundTag root=new CompoundTag(),level=new CompoundTag();net.minecraft.nbt.ListTag list=new net.minecraft.nbt.ListTag();list.add(data.copy());level.put("Entities",list);
        level.putInt("xPos",chunk.getPos().x);level.putInt("zPos",chunk.getPos().z);root.put("Level",level);root.putInt("DataVersion",2730);
        // Current fixture NBT follows the real native conversion boundary too.
        // The post-load admission callback remains Minecraft's own method.
        CompoundTag fixed=cyano.lootable.upgrade.NativeNbt.update(
                cyano.lootable.upgrade.UpgradePreservation.wrapper(net.minecraft.util.datafix.DataFixers.getDataFixer()),
                net.minecraft.util.datafix.fixes.References.CHUNK,root,2730,2730);
        try {
            java.lang.reflect.Method nativeRead=null;
            for(java.lang.reflect.Method method:net.minecraft.world.level.chunk.storage.ChunkSerializer.class.getDeclaredMethods())
                if(java.lang.reflect.Modifier.isStatic(method.getModifiers()) && !method.isSynthetic() && method.getReturnType()==void.class && java.util.Arrays.equals(method.getParameterTypes(),new Class<?>[]{ServerLevel.class,CompoundTag.class,net.minecraft.world.level.chunk.LevelChunk.class})) {
                    if(nativeRead!=null)throw new IllegalStateException("Ambiguous native entity admission");nativeRead=method;
                }
            if(nativeRead==null)throw new IllegalStateException("Missing native entity admission");
            nativeRead.setAccessible(true);nativeRead.invoke(null,world,fixed.getCompound("Level"),chunk);
            Object manager=singleField(ServerLevel.class,net.minecraft.world.level.entity.PersistentEntitySectionManager.class).get(world);
            net.minecraft.world.level.entity.EntitySectionStorage<?> sections=(net.minecraft.world.level.entity.EntitySectionStorage<?>)
                    singleField(manager.getClass(),net.minecraft.world.level.entity.EntitySectionStorage.class).get(manager);
            java.util.UUID id=fixed.getCompound("Level").getList("Entities",10).getCompound(0).getUUID("UUID");
            return sections.getExistingSectionsInChunk(chunk.getPos().toLong()).flatMap(section->section.getEntities())
                    .filter(entity->entity instanceof Entity && ((Entity)entity).getUUID().equals(id)).map(entity->(Entity)entity).findFirst().orElse(null);
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native chunk admission failed",failure);}
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
        ServerLevel world=(ServerLevel)entity.level;int x=net.minecraft.util.Mth.floor(entity.getX())>>4,z=net.minecraft.util.Mth.floor(entity.getZ())>>4;
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
            provider.tick(()->true);
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
            Object manager=((ServerLevel)entity.level).getChunkSource().chunkMap;
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
