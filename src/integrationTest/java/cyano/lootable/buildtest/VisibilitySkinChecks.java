package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseRenderer;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.*;
import net.minecraft.resources.Identifier;


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
            skinField.set(mc,new SkinManager(new File(mc.gameDirectory,"skin-fixture").toPath(),mc.services(),
                    new net.minecraft.client.renderer.texture.SkinTextureDownloader(mc.getProxy(),mc.getTextureManager(),mc),mc) {
                @Override public java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> createLookup(GameProfile p, boolean secure) {
                    throw new IllegalStateException("injected skin cache failure");
                }
            });
            try {
                Identifier texture=CorpseRenderer.getSkin(profile);
                RuntimeChecks.require(DefaultPlayerSkin.get(profile).body().texturePath().equals(texture),"Failed skin lookup did not use vanilla fallback");
                mark(report,"skin_cache_failure_has_visible_default",true);
            } catch(Throwable failure) {failures.add("skin cache: "+failure);mark(report,"skin_cache_failure_has_visible_default",false);}
        } finally {skinField.set(mc,original);}
        AtomicInteger requests=new AtomicInteger();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(2);
        CompletableFuture<Optional<GameProfile>> delayed=new CompletableFuture<>();
        try(var ignored=FixtureProfileCache.install(name->{
            requests.incrementAndGet();entered.countDown();
            if(name.equals("VisibilityFault"))return CompletableFuture.failedFuture(new IllegalStateException("injected lookup failure"));
            if(name.equals("VisibilitySlow"))return delayed;
            throw new AssertionError("Unexpected fixture profile: "+name);
        })) {
            EntityLootableBody body=new EntityLootableBody(mc.level);
            body.setUserName("VisibilityFault");
            try {
                body.baseTick();
                RuntimeChecks.require(body.getGameProfile()!=null,"Failure erased fallback profile");
                mark(report,"profile_failure_does_not_abort_client_tick",true);
            } catch(Throwable failure) {
                failures.add("profile: "+failure);
                mark(report,"profile_failure_does_not_abort_client_tick",false);
            }
            EntityLootableBody slow=new EntityLootableBody(mc.level);
            slow.setUserName("VisibilitySlow");
            long start=System.nanoTime();
            slow.baseTick();
            long millis=(System.nanoTime()-start)/1000000;
            try {
                RuntimeChecks.require(!delayed.isDone() && millis<150,"Slow profile blocked client tick for "+millis+"ms");
                mark(report,"delayed_profile_does_not_block_client_tick",true);
            } catch(Throwable failure) {
                failures.add("delay: "+failure);
                mark(report,"delayed_profile_does_not_block_client_tick",false);
            }
            boolean local=LootableBodies.useLocalSkin;
            try {
                LootableBodies.useLocalSkin=true;
                slow.setUserName("VisibilityRenamed");
                slow.baseTick();
            } finally {
                LootableBodies.useLocalSkin=local;
            }
            // Observe real worker entry before completing faults or restoring the native resolver.
            // The separately measured production tick above must still return without waiting.
            RuntimeChecks.require(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"Native profile workers never entered the injected resolver");
            delayed.complete(Optional.of(new GameProfile(profile.id(),"VisibilitySlow")));
            RuntimeChecks.require(requests.get()==2,"Faults did not exercise the native profile lookup");
            RuntimeChecks.require(slow.getGameProfile().name().equals("VisibilityRenamed"),"Late skin result overwrote renamed corpse");
            mark(report,"late_profile_result_preserves_new_name",true);
        }
        if(!failures.isEmpty())throw new AssertionError("VISIBILITY_SKIN_FAILURES "+failures);
    }
    private static void mark(File report,String name,boolean passed) throws Exception {
        try(FileWriter w=new FileWriter(report,true)){w.write((passed?"PASS ":"FAIL ")+name+"\n");}
    }
    private static Field field(Class<?> owner,Class<?> type) {for(Field f:owner.getDeclaredFields())if(f.getType()==type){f.setAccessible(true);return f;}throw new AssertionError(type);}
}
