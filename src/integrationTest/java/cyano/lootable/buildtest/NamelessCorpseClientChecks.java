package cyano.lootable.buildtest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Registry;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Exercise the actual client locale, entity label, spawn egg and saved name. */
final class NamelessCorpseClientChecks {
    private static final String[] LOCALES = {"en_us","de_de","en_au","en_ca","en_en","en_gb","en_pt",
            "es_es","es_mx","fr_ca","fr_fr","it_it","nl_nl","zh_cn"};
    private static final UUID BODY = UUID.fromString("00000000-0000-4000-8000-000000000119");
    private int stage, frames, locale;
    private Future<?> pending;
    private LanguageInfo originalLanguage;
    private boolean originalUnicode, originalBidi;
    private boolean renderedBody;

    @SubscribeEvent public void rendered(RenderLivingEvent.Post<?,?> event) {
        if(event.getEntity().getUUID().equals(BODY)) renderedBody=true;
    }

    static void englishContract(Minecraft mc) throws Exception {
        assertLocale(mc, "en_us");
    }

    private static String translation(Minecraft mc, String code) throws Exception {
        com.google.gson.JsonObject values;
        try(Reader in=new InputStreamReader(mc.getResourceManager().getResource(new ResourceLocation("lootablebodies","lang/"+code+".json")).getInputStream(),StandardCharsets.UTF_8)) {
            values=new com.google.gson.JsonParser().parse(in).getAsJsonObject();
        }
        String expected=values.get("entity.lootablebodies.Corpse.name").getAsString();
        RuntimeChecks.require(expected != null && !expected.isEmpty(), "Missing original locale "+code);
        return expected;
    }

    private static void assertLocale(Minecraft mc, String code) throws Exception {
        String expected = translation(mc, code);
        EntityLootableBody body = new EntityLootableBody(mc.level);
        body.setPos(18.5,80,18.5);
        RuntimeChecks.disableVacuum(body);
        for (int slot=0;slot<48;slot++) body.setItem(slot,RuntimeChecks.stack(slot));
        body.addItem(RuntimeChecks.stack(70));
        CompoundTag data = NameLifetimeChecks.saved(body);
        CompoundTag retained = new CompoundTag();
        retained.putString("Source","FutureFixture"); retained.putString("Data","retain verbatim");
        data.put("LootableBodiesUnresolved",NbtRecoveryChecks.list(retained));
        body = (EntityLootableBody)FixtureApi.loadEntity(data,mc.level);
        RuntimeChecks.require(body != null && new ResourceLocation("lootablebodies:corpse").equals(body.getType().getRegistryName()), "Registry/display identity changed");
        CompoundTag before = NameLifetimeChecks.saved(body);
        RuntimeChecks.require(expected.equals(body.getName().getString()), "Untranslated nameless corpse in "+code+": "+body.getName().getString());
        RuntimeChecks.require(expected.equals(body.getDisplayName().getString()), "Wrong rendered name in "+code);
        RuntimeChecks.require(!body.hasCustomName() && !before.contains("Name") && !before.contains("CustomName"), "Localized fallback became an owner name");
        RuntimeChecks.require(NameLifetimeChecks.same(before,NameLifetimeChecks.saved(body)), "Reading fallback mutated complete NBT");
        EntityLootableBody loaded = (EntityLootableBody)FixtureApi.loadEntity(before.copy(),mc.level);
        RuntimeChecks.require(expected.equals(loaded.getName().getString()) && NameLifetimeChecks.same(before,NameLifetimeChecks.saved(loaded)),
                "Fallback reload changed UUID, inventory, recovery or other NBT");
        for (String owner : new String[]{"Owner Fixture", "entity.Corpse.name", "Owner \u00e9\u540d"}) {
            body.setUserName(owner); CompoundTag named = NameLifetimeChecks.saved(body);
            RuntimeChecks.require(owner.equals(body.getName().getString()) && owner.equals(body.getDisplayName().getString()), "Owner name was translated/renamed");
            RuntimeChecks.require(NameLifetimeChecks.same(named,NameLifetimeChecks.saved(body)), "Owner lookup mutated NBT");
        }
        body.setUserName(""); CompoundTag cleared = NameLifetimeChecks.saved(body);
        RuntimeChecks.require(expected.equals(body.getName().getString()) && !body.hasCustomName() &&
                NameLifetimeChecks.same(cleared,NameLifetimeChecks.saved(body)), "Cleared owner did not regain transient fallback");
        ItemStack egg = new ItemStack(LootableBodies.CORPSE_EGG);
        CompoundTag eggBefore = egg.save(new CompoundTag());
        RuntimeChecks.require(egg.getHoverName().getString().equals(net.minecraft.client.resources.language.I18n.get("item.lootablebodies.corpse_spawn_egg")) && !egg.getHoverName().getString().contains("entity.Corpse.name"), "Untranslated spawn egg in "+code);
        RuntimeChecks.require(NameLifetimeChecks.same(eggBefore,egg.save(new CompoundTag())), "Egg name lookup mutated its native ID/NBT");
        System.out.println("NAME_TRANSLATION_PASS "+code);
    }

    void tick(Minecraft mc) throws Exception {
        if(stage==0 && mc.screen instanceof TitleScreen) {
            mc.options.pauseOnLostFocus=false;mc.options.framerateLimit=30;mc.options.renderDistance=4;
            originalLanguage=mc.getLanguageManager().getSelected();
            originalUnicode=mc.options.forceUnicodeFont; originalBidi=mc.font.isBidirectional();
            MinecraftForge.EVENT_BUS.register(this);
            FixtureApi.ClientWorlds.create(mc,"name-fixture","Name fixture",net.minecraft.world.level.GameType.CREATIVE,false);stage=1;
        } else if(stage==1 && mc.level!=null && mc.player!=null && mc.getSingleplayerServer()!=null) {
            pending=mc.getSingleplayerServer().submitAsync(() -> {
                ServerLevel world=mc.getSingleplayerServer().getLevel(FixtureApi.dimensionKey(0));
                world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,null);
                for(int x=12;x<=24;x++)for(int z=12;z<=24;z++)world.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                EntityLootableBody body=new EntityLootableBody(world);body.setUUID(BODY);body.setPos(18.5,80,18.5);body.setNoGravity(true);RuntimeChecks.disableVacuum(body);
                for(int i=0;i<48;i++)body.setItem(i,RuntimeChecks.stack(i));
                RuntimeChecks.require(world.addFreshEntity(body),"Spawn nameless graphical fixture");
                EntityLootableBody named=new EntityLootableBody(world);named.setPos(20.5,80,18.5);named.setNoGravity(true);named.setUserName("Owner Fixture");RuntimeChecks.disableVacuum(named);world.addFreshEntity(named);
                net.minecraft.server.level.ServerPlayer player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(18.5,80,14.5,0,12);
            });stage=2;frames=0;
        } else if(stage==2 && pending.isDone() && ++frames>30) {
            pending.get();stage=3;frames=0;
        } else if(stage==3) {
            // Keep the disposable view attached to the current network player.
            // The integrated-server join may replace its initial camera entity.
            mc.setCameraEntity(mc.player);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            mc.player.setYRot(0);mc.player.yRotO=0;
            mc.player.setXRot(20);mc.player.xRotO=20;
            String code=LOCALES[locale];
            if(frames++==0) {
                renderedBody=false;
                mc.getLanguageManager().setSelected(new LanguageInfo(code,code,code,false));
                mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());

                // Native16 LanguageMap controls bidirectional text after resource reload.
                assertLocale(mc,code);
                try(PrintWriter out=new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(mc.gameDirectory,"name-checks.txt"),true),StandardCharsets.UTF_8))) {out.println("PASS locale_"+code+"_fallback_owner_egg_complete_nbt");}
            }
            if(frames==15 && Arrays.asList("en_us","de_de","en_pt","es_es","zh_cn").contains(code)) {
                System.out.println("NAME_CAMERA player="+mc.player.blockPosition()+" view="+mc.getCameraEntity().blockPosition()+" yaw="+mc.player.getYRot()+" pitch="+mc.player.getXRot()+" rendered="+renderedBody);
                RuntimeChecks.require(renderedBody,"No corpse rendered for label screenshot "+code);
                Screenshot.grab(mc.gameDirectory,"name-"+code+".png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
            }
            if(frames>20) {frames=0;locale++;if(locale==LOCALES.length) {
                mc.getLanguageManager().setSelected(originalLanguage);mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
                RuntimeChecks.require(mc.font.isBidirectional()==originalBidi,"Restored language direction changed");stage=4;
            }}
        } else if(stage==4) {
            for(Entity entity:FixtureApi.entities(mc.level))if(entity.getUUID().equals(BODY)) {
                RuntimeChecks.require("Corpse".equals(entity.getName().getString()) && !entity.hasCustomName(),"Network corpse has wrong English fallback/custom name");
                mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);stage=5;frames=0;break;
            }
        } else if(stage==5 && mc.screen instanceof CorpseGUIContainer && ++frames>20) {
            for(int slot=0;slot<48;slot++)RuntimeChecks.require(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(slot).getItem(),RuntimeChecks.stack(slot)),"Name fix changed network inventory slot "+slot);
            Screenshot.grab(mc.gameDirectory,"name-english-inventory.png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
            new File(mc.gameDirectory,"client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_NAME_TRANSLATION_PASS locales="+LOCALES.length);
            mc.player.closeContainer();FixtureConnections.disconnect(mc);stage=6;
        } else if(stage==6 && FixtureConnections.stopped(mc)) {mc.stop();stage=7;}
    }
}
