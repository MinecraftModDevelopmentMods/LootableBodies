package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.*;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseRenderer;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.*;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.resources.ResourceLocation;


/** Deterministic service faults; no real account or authentication service required. */
final class VisibilitySkinChecks {
    static void run(Minecraft mc) throws Exception {
        RuntimeChecks.require(!LootableBodies.useLocalSkin,"Visibility checks require the production texture default");
        File report=new File(mc.gameDirectory,"skin-checks.txt");
        List<String> failures=new ArrayList<>();
        Field skinField=field(Minecraft.class,SkinManager.class);
        SkinManager original=(SkinManager)skinField.get(mc);
        GameProfile profile=new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000201"),"VisibilityFault");
        try {
            skinField.set(mc,new SkinManager(mc.getTextureManager(),new File(mc.gameDirectory,"skin-fixture"),mc.getMinecraftSessionService()) {
                @Override public Map<MinecraftProfileTexture.Type,MinecraftProfileTexture> getInsecureSkinInformation(GameProfile p) {
                    throw new IllegalStateException("injected skin cache failure");
                }
            });
            try {
                ResourceLocation texture=CorpseRenderer.getSkin(profile);
                RuntimeChecks.require(DefaultPlayerSkin.getDefaultSkin(profile.getId()).equals(texture),"Failed skin lookup did not use vanilla fallback");
                mark(report,"skin_cache_failure_has_visible_default",true);
            } catch(Throwable failure) {failures.add("skin cache: "+failure);mark(report,"skin_cache_failure_has_visible_default",false);}
        } finally {skinField.set(mc,original);}
        Field cache=field(SkullBlockEntity.class,GameProfileCache.class);
        Field service=field(SkullBlockEntity.class,MinecraftSessionService.class);
        Object oldCache=cache.get(null),oldService=service.get(null);
        AtomicInteger requests=new AtomicInteger();
        CountDownLatch failedRequest=new CountDownLatch(1);
        CountDownLatch slowDelivered=new CountDownLatch(1);
        try {
            GameProfileCache faultCache=new GameProfileCache(null,new File(mc.gameDirectory,"fault-usercache.json")) {
                @Override public Optional<GameProfile> get(String name) {return Optional.of(new GameProfile(profile.getId(),name));}
                @Override public void add(GameProfile value) {super.add(value);if(value.getName().equals("VisibilitySlow"))slowDelivered.countDown();}
            };
            faultCache.setExecutor(Runnable::run);SkullBlockEntity.setProfileCache(faultCache);
            SkullBlockEntity.setSessionService((MinecraftSessionService)Proxy.newProxyInstance(MinecraftSessionService.class.getClassLoader(),new Class<?>[]{MinecraftSessionService.class},(proxy,method,args)->{
                if(method.getName().equals("fillProfileProperties")) {requests.incrementAndGet();failedRequest.countDown();throw new IllegalStateException("injected profile lookup failure");}
                throw new UnsupportedOperationException(method.getName());
            }));
            EntityLootableBody body=new EntityLootableBody(mc.level);body.setUserName("VisibilityFault");
            try {body.baseTick();RuntimeChecks.require(body.getGameProfile()!=null,"Failure erased fallback profile");mark(report,"profile_failure_does_not_abort_client_tick",true);}
            catch(Throwable failure) {failures.add("profile: "+failure);mark(report,"profile_failure_does_not_abort_client_tick",false);}
            RuntimeChecks.require(failedRequest.await(2,TimeUnit.SECONDS),"Profile failure was never injected");
            // Deliberately slow lookup must leave the render/tick thread responsive.
            SkullBlockEntity.setSessionService((MinecraftSessionService)Proxy.newProxyInstance(MinecraftSessionService.class.getClassLoader(),new Class<?>[]{MinecraftSessionService.class},(proxy,method,args)->{
                if(method.getName().equals("fillProfileProperties")) {requests.incrementAndGet();Thread.sleep(400);return args[0];}
                throw new UnsupportedOperationException(method.getName());
            }));
            EntityLootableBody slow=new EntityLootableBody(mc.level);slow.setUserName("VisibilitySlow");
            long start=System.nanoTime();slow.baseTick();long millis=(System.nanoTime()-start)/1000000;
            try {RuntimeChecks.require(millis<150,"Slow profile blocked client tick for "+millis+"ms");mark(report,"delayed_profile_does_not_block_client_tick",true);}
            catch(Throwable failure) {failures.add("delay: "+failure);mark(report,"delayed_profile_does_not_block_client_tick",false);}
            boolean local=LootableBodies.useLocalSkin;LootableBodies.useLocalSkin=true;
            slow.setUserName("VisibilityRenamed");slow.baseTick();LootableBodies.useLocalSkin=local;
            RuntimeChecks.require(slowDelivered.await(2,TimeUnit.SECONDS),"Native profile callback was never delivered");
            // Fence the same owned executor after its cache/add/CAS task.
            // Waiting for the service alone would not prove the late CAS path.
            CountDownLatch callbackFence=new CountDownLatch(1);
            ((java.util.concurrent.Executor)field(EntityLootableBody.class,java.util.concurrent.ThreadPoolExecutor.class).get(null)).execute(callbackFence::countDown);
            RuntimeChecks.require(callbackFence.await(2,TimeUnit.SECONDS),"Owned profile lookup did not finish");
            RuntimeChecks.require(requests.get()==2,"Faults never exercised actual profile services");
            RuntimeChecks.require(slow.getGameProfile().getName().equals("VisibilityRenamed"),"Late skin result overwrote renamed corpse");
            mark(report,"late_profile_result_preserves_new_name",true);
        } finally {cache.set(null,oldCache);service.set(null,oldService);}
        if(!failures.isEmpty())throw new AssertionError("VISIBILITY_SKIN_FAILURES "+failures);
    }
    private static void mark(File report,String name,boolean passed) throws Exception {
        try(FileWriter w=new FileWriter(report,true)){w.write((passed?"PASS ":"FAIL ")+name+"\n");}
    }
    private static Field field(Class<?> owner,Class<?> type) {for(Field f:owner.getDeclaredFields())if(f.getType()==type){f.setAccessible(true);return f;}throw new AssertionError(type);}
}
