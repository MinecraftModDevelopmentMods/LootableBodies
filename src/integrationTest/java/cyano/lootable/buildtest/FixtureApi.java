package cyano.lootable.buildtest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Read-only entity snapshots across the target's separate world APIs. */
final class FixtureApi {
    private FixtureApi() { }
    static List<Entity> entities(World world) {
        if (world instanceof ServerWorld) return ((ServerWorld) world).getEntities().collect(Collectors.toList());
        return ClientEntities.read(world);
    }
    static Entity loadEntity(CompoundNBT data, World world) {
        return net.minecraft.entity.EntityType.func_220335_a(data, world, entity -> entity);
    }
    static Entity readChunkEntity(CompoundNBT data, ServerWorld world, net.minecraft.world.chunk.Chunk chunk) {
        // Invoke the actual native chunk serializer; do not replace its admission callback.
        CompoundNBT level=new CompoundNBT();net.minecraft.nbt.ListNBT list=new net.minecraft.nbt.ListNBT();list.add(data.copy());level.put("Entities",list);
        try {
            java.lang.reflect.Method nativeRead=null;
            for(java.lang.reflect.Method method:net.minecraft.world.chunk.storage.ChunkSerializer.class.getDeclaredMethods())
                if(java.lang.reflect.Modifier.isStatic(method.getModifiers()) && !method.isSynthetic() && method.getReturnType()==void.class && java.util.Arrays.equals(method.getParameterTypes(),new Class<?>[]{CompoundNBT.class,net.minecraft.world.chunk.Chunk.class})) {
                    if(nativeRead!=null)throw new IllegalStateException("Ambiguous native entity admission");nativeRead=method;
                }
            if(nativeRead==null)throw new IllegalStateException("Missing native entity admission");
            nativeRead.setAccessible(true);nativeRead.invoke(null,level,chunk);
            for(net.minecraft.util.ClassInheritanceMultiMap<Entity> section:chunk.getEntityLists())for(Entity entity:section)return entity;
            return null;
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Native chunk admission failed",failure);}
    }
    static void awaitTicking(Entity entity) {
        ServerWorld world=(ServerWorld)entity.world;int x=net.minecraft.util.math.MathHelper.floor(entity.getPosX())>>4,z=net.minecraft.util.math.MathHelper.floor(entity.getPosZ())>>4;
        world.forceChunk(x,z,true);
        net.minecraft.world.server.ServerChunkProvider provider=world.getChunkProvider();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(!provider.isChunkLoaded(entity)) {
            RuntimeChecks.require(System.nanoTime()<deadline,"Native entity-ticking chunk did not become ready");
            if(!provider.driveOneTask())Thread.yield();
        }
    }
    static void unload(ServerWorld world,int x,int z) {
        net.minecraft.world.server.ServerChunkProvider provider=world.getChunkProvider();
        world.forceChunk(x,z,false);
        // Expire temporary native load tickets and execute real save/unload work.
        for(int tick=0;tick<1200 && provider.chunkExists(x,z);tick++) {
            provider.tick(()->true);
            for(int task=0;task<1000 && provider.driveOneTask();task++) { }
            if((tick&31)==0)Thread.yield();
        }
        provider.save(true);
        RuntimeChecks.require(!provider.chunkExists(x,z),"Fixture chunk remained loaded after native ticket expiry: "+x+","+z);
    }
    static void flush(MinecraftServer server) { server.save(true, true, false); }
    @SuppressWarnings("unchecked")
    static java.util.Set<net.minecraft.entity.player.ServerPlayerEntity> tracked(Entity entity) {
        try {
            Object manager=((ServerWorld)entity.world).getChunkProvider().chunkManager;
            java.lang.reflect.Field map=null;
            for(java.lang.reflect.Field field:manager.getClass().getDeclaredFields())
                if(it.unimi.dsi.fastutil.ints.Int2ObjectMap.class.isAssignableFrom(field.getType())) {
                    if(map!=null)throw new IllegalStateException("Ambiguous native tracking map"); map=field;
                }
            if(map==null)throw new IllegalStateException("Missing native tracking map");
            map.setAccessible(true);
            Object tracker=((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>)map.get(manager)).get(entity.getEntityId());
            if(tracker==null)return java.util.Collections.emptySet();
            java.lang.reflect.Field players=null;
            for(java.lang.reflect.Field field:tracker.getClass().getDeclaredFields())
                if(java.util.Set.class.isAssignableFrom(field.getType())) {
                    if(players!=null)throw new IllegalStateException("Ambiguous native tracking set");players=field;
                }
            if(players==null)throw new IllegalStateException("Missing native tracking set");
            players.setAccessible(true);
            return new java.util.HashSet<>((java.util.Set<net.minecraft.entity.player.ServerPlayerEntity>)players.get(tracker));
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot inspect native tracking",failure);}
    }
    @OnlyIn(Dist.CLIENT)
    private static final class ClientEntities {
        static List<Entity> read(World world) {
            List<Entity> result = new ArrayList<>();
            ((net.minecraft.client.world.ClientWorld) world).getAllEntities().forEach(result::add);
            return result;
        }
    }
}
