package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.Language;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Exercise the actual client locale, entity label, spawn egg and saved name. */
final class NamelessCorpseClientChecks {
    private static final String[] LOCALES = {"en_us","de_de","en_au","en_ca","en_en","en_gb","en_pt",
            "es_es","es_mx","fr_ca","fr_fr","it_it","nl_nl","zh_cn"};
    private static final UUID BODY = UUID.fromString("00000000-0000-4000-8000-000000000119");
    private int stage, frames, locale;
    private Future<?> pending;
    private Language originalLanguage;
    private boolean originalUnicode, originalBidi;
    private boolean renderedBody;

    @SubscribeEvent public void rendered(RenderLivingEvent.Post<?> event) {
        if(event.getEntity().getUniqueID().equals(BODY)) renderedBody=true;
    }

    static void englishContract(Minecraft mc) throws Exception {
        assertLocale(mc, "en_us");
    }

    private static String translation(Minecraft mc, String code) throws Exception {
        Properties values = new Properties();
        try (Reader in = new InputStreamReader(mc.getResourceManager().getResource(
                new ResourceLocation("lootablebodies", "lang/"+code+".lang")).getInputStream(), StandardCharsets.UTF_8)) {
            values.load(in);
        }
        String expected = values.getProperty("entity.lootablebodies.Corpse.name");
        RuntimeChecks.require(expected != null && !expected.isEmpty(), "Missing original locale "+code);
        return expected;
    }

    private static void assertLocale(Minecraft mc, String code) throws Exception {
        String expected = translation(mc, code);
        EntityLootableBody body = new EntityLootableBody(mc.world);
        body.setPosition(18.5,80,18.5);
        RuntimeChecks.disableVacuum(body);
        for (int slot=0;slot<48;slot++) body.setInventorySlotContents(slot,RuntimeChecks.stack(slot));
        body.addItem(RuntimeChecks.stack(70));
        NBTTagCompound data = NameLifetimeChecks.saved(body);
        NBTTagCompound retained = new NBTTagCompound();
        retained.setString("Source","FutureFixture"); retained.setString("Data","retain verbatim");
        data.setTag("LootableBodiesUnresolved",NbtRecoveryChecks.list(retained));
        body = (EntityLootableBody)EntityList.createEntityFromNBT(data,mc.world);
        RuntimeChecks.require(body != null && "Corpse".equals(EntityList.getEntityString(body)) &&
                new ResourceLocation("lootablebodies:corpse").equals(EntityList.getKey(body)), "Registry/display identity changed");
        NBTTagCompound before = NameLifetimeChecks.saved(body);
        RuntimeChecks.require(expected.equals(body.getName()), "Untranslated nameless corpse in "+code+": "+body.getName());
        RuntimeChecks.require(expected.equals(body.getDisplayName().getUnformattedText()), "Wrong rendered name in "+code);
        RuntimeChecks.require(!body.hasCustomName() && !before.hasKey("Name") && !before.hasKey("CustomName"), "Localized fallback became an owner name");
        RuntimeChecks.require(NameLifetimeChecks.same(before,NameLifetimeChecks.saved(body)), "Reading fallback mutated complete NBT");
        EntityLootableBody loaded = (EntityLootableBody)EntityList.createEntityFromNBT(before.copy(),mc.world);
        RuntimeChecks.require(expected.equals(loaded.getName()) && NameLifetimeChecks.same(before,NameLifetimeChecks.saved(loaded)),
                "Fallback reload changed UUID, inventory, recovery or other NBT");
        for (String owner : new String[]{"Owner Fixture", "entity.Corpse.name", "Owner \u00e9\u540d"}) {
            body.setUserName(owner); NBTTagCompound named = NameLifetimeChecks.saved(body);
            RuntimeChecks.require(owner.equals(body.getName()) && owner.equals(body.getDisplayName().getUnformattedText()), "Owner name was translated/renamed");
            RuntimeChecks.require(NameLifetimeChecks.same(named,NameLifetimeChecks.saved(body)), "Owner lookup mutated NBT");
        }
        body.setUserName(""); NBTTagCompound cleared = NameLifetimeChecks.saved(body);
        RuntimeChecks.require(expected.equals(body.getName()) && !body.hasCustomName() &&
                NameLifetimeChecks.same(cleared,NameLifetimeChecks.saved(body)), "Cleared owner did not regain transient fallback");
        ItemStack egg = new ItemStack(Items.SPAWN_EGG);
        ItemMonsterPlacer.applyEntityIdToItemStack(egg,new ResourceLocation("lootablebodies:corpse"));
        NBTTagCompound eggBefore = egg.writeToNBT(new NBTTagCompound());
        RuntimeChecks.require(egg.getDisplayName().endsWith(" "+expected) && !egg.getDisplayName().contains("entity.Corpse.name"), "Untranslated spawn egg in "+code);
        RuntimeChecks.require(NameLifetimeChecks.same(eggBefore,egg.writeToNBT(new NBTTagCompound())), "Egg name lookup mutated its native ID/NBT");
        System.out.println("NAME_TRANSLATION_PASS "+code);
    }

    void tick(Minecraft mc) throws Exception {
        if(stage==0 && mc.currentScreen instanceof GuiMainMenu) {
            mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.limitFramerate=30;mc.gameSettings.renderDistanceChunks=4;
            originalLanguage=mc.getLanguageManager().getCurrentLanguage();
            originalUnicode=mc.fontRenderer.getUnicodeFlag(); originalBidi=mc.fontRenderer.getBidiFlag();
            MinecraftForge.EVENT_BUS.register(this);
            mc.launchIntegratedServer("name-fixture","Name fixture",new WorldSettings(4267321,GameType.CREATIVE,false,false,WorldType.FLAT));stage=1;
        } else if(stage==1 && mc.world!=null && mc.player!=null && mc.getIntegratedServer()!=null) {
            pending=mc.getIntegratedServer().addScheduledTask(() -> {
                WorldServer world=mc.getIntegratedServer().getWorld(0);
                world.getGameRules().setOrCreateGameRule("doMobSpawning","false");
                for(int x=12;x<=24;x++)for(int z=12;z<=24;z++)world.setBlockState(new BlockPos(x,79,z),Blocks.STONE.getDefaultState());
                EntityLootableBody body=new EntityLootableBody(world);body.setUniqueId(BODY);body.setPosition(18.5,80,18.5);body.setNoGravity(true);RuntimeChecks.disableVacuum(body);
                for(int i=0;i<48;i++)body.setInventorySlotContents(i,RuntimeChecks.stack(i));
                RuntimeChecks.require(world.spawnEntity(body),"Spawn nameless graphical fixture");
                EntityLootableBody named=new EntityLootableBody(world);named.setPosition(20.5,80,18.5);named.setNoGravity(true);named.setUserName("Owner Fixture");RuntimeChecks.disableVacuum(named);world.spawnEntity(named);
                net.minecraft.entity.player.EntityPlayerMP player=mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
                player.capabilities.isFlying=true;player.sendPlayerAbilities();player.connection.setPlayerLocation(18.5,80,14.5,0,12);
            });stage=2;frames=0;
        } else if(stage==2 && pending.isDone() && ++frames>30) {
            pending.get();stage=3;frames=0;
        } else if(stage==3) {
            // Keep the disposable view attached to the current network player.
            // The integrated-server join may replace its initial camera entity.
            mc.setRenderViewEntity(mc.player);mc.gameSettings.thirdPersonView=0;
            mc.player.rotationYaw=mc.player.prevRotationYaw=0;
            mc.player.rotationPitch=mc.player.prevRotationPitch=20;
            String code=LOCALES[locale];
            if(frames++==0) {
                renderedBody=false;
                mc.getLanguageManager().setCurrentLanguage(new Language(code,code,code,false));
                mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
                mc.fontRenderer.setUnicodeFlag(mc.getLanguageManager().isCurrentLocaleUnicode());
                mc.fontRenderer.setBidiFlag(mc.getLanguageManager().isCurrentLanguageBidirectional());
                assertLocale(mc,code);
                try(PrintWriter out=new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(mc.mcDataDir,"name-checks.txt"),true),StandardCharsets.UTF_8))) {out.println("PASS locale_"+code+"_fallback_owner_egg_complete_nbt");}
            }
            if(frames==15 && Arrays.asList("en_us","de_de","en_pt","es_es","zh_cn").contains(code)) {
                System.out.println("NAME_CAMERA player="+mc.player.getPosition()+" view="+mc.getRenderViewEntity().getPosition()+" yaw="+mc.player.rotationYaw+" pitch="+mc.player.rotationPitch+" rendered="+renderedBody);
                RuntimeChecks.require(renderedBody,"No corpse rendered for label screenshot "+code);
                ScreenShotHelper.saveScreenshot(mc.mcDataDir,"name-"+code+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
            }
            if(frames>20) {frames=0;locale++;if(locale==LOCALES.length) {
                mc.getLanguageManager().setCurrentLanguage(originalLanguage);mc.getLanguageManager().onResourceManagerReload(mc.getResourceManager());
                mc.fontRenderer.setUnicodeFlag(originalUnicode);mc.fontRenderer.setBidiFlag(originalBidi);stage=4;
            }}
        } else if(stage==4) {
            for(Entity entity:mc.world.loadedEntityList)if(entity.getUniqueID().equals(BODY)) {
                RuntimeChecks.require("Corpse".equals(entity.getName()) && !entity.hasCustomName(),"Network corpse has wrong English fallback/custom name");
                mc.playerController.interactWithEntity(mc.player,entity,EnumHand.MAIN_HAND);stage=5;frames=0;break;
            }
        } else if(stage==5 && mc.currentScreen instanceof CorpseGUIContainer && ++frames>20) {
            for(int slot=0;slot<48;slot++)RuntimeChecks.require(RuntimeChecks.sameStack(mc.player.openContainer.getSlot(slot).getStack(),RuntimeChecks.stack(slot)),"Name fix changed network inventory slot "+slot);
            ScreenShotHelper.saveScreenshot(mc.mcDataDir,"name-english-inventory.png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
            new File(mc.mcDataDir,"client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_NAME_TRANSLATION_PASS locales="+LOCALES.length);
            mc.player.closeScreen();mc.getIntegratedServer().initiateShutdown();stage=6;
        } else if(stage==6 && mc.world==null && !mc.isIntegratedServerRunning()) {mc.shutdown();stage=7;}
    }
}
