package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;
import net.minecraftforge.common.MinecraftForge;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Automated real-client world, packet interaction, GUI render and reload check. */
public final class ClientChecks {
    private int stage, ticks, frames;
    private int reportedStage=-1;
    private Future<?> pending;
    private final ConnectedClientChecks connected = new ConnectedClientChecks();
    private final DropClientChecks drops = new DropClientChecks();
    private final NbtClientChecks nbt = new NbtClientChecks();
    private final ReportedIssueClientChecks reportedIssues = new ReportedIssueClientChecks();
    private final VisibilitySkinClientChecks visibilitySkins = new VisibilitySkinClientChecks();
    private final ConfigClientChecks configScreen = new ConfigClientChecks();
    private final NamelessCorpseClientChecks namelessNames = new NamelessCorpseClientChecks();
    @SubscribeEvent public void mouse(net.minecraftforge.client.event.GuiScreenEvent.MouseClickedEvent.Pre event) {
        event.setCanceled(true);
    }
    @SubscribeEvent public void keyboard(net.minecraftforge.client.event.GuiScreenEvent.KeyboardKeyPressedEvent.Pre event) {
        event.setCanceled(true);
    }
    public void register() {
        if (Boolean.getBoolean("lootablebodies.clientCheck")) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
            MinecraftForge.EVENT_BUS.register(this);
        }
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        // The verifier never needs to capture the user's mouse or keyboard.
        if(mc.isGameFocused()) mc.mouseHelper.ungrabMouse();
        net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        if(stage!=reportedStage || ticks%60==0) {
            System.out.println("GRAPHICAL_STAGE "+stage+" screen="+(mc.currentScreen==null?"world":mc.currentScreen.getClass().getSimpleName())+
                    " position="+(mc.player==null?"none":mc.player.getPosition())+" focus="+mc.isGameFocused());
            reportedStage=stage;
        }
        if(ticks>0 && ticks%150==0) ScreenShotHelper.saveScreenshot(mc.gameDir,"diagnostic-stage-"+stage+"-tick-"+ticks+".png",mc.mainWindow.getFramebufferWidth(),mc.mainWindow.getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));
        if(++ticks > 12000) fail(new AssertionError("client timeout stage="+stage));
        try {
            RuntimeChecks.require(!new File(mc.gameDir,"abort").exists(),"Disposable graphical check aborted");
            if(Boolean.getBoolean("lootablebodies.configUiCheck")) {configScreen.tick(mc);return;}
            if(Boolean.getBoolean("lootablebodies.namelessClientCheck")) {namelessNames.tick(mc);return;}
            if(Boolean.getBoolean("lootablebodies.visibilitySkinCheck")) {visibilitySkins.tick(mc);return;}
            if(Boolean.getBoolean("lootablebodies.issueClientCheck")) {reportedIssues.tick(mc);return;}
            if(stage==0 && mc.currentScreen instanceof GuiMainMenu) {
                mc.gameSettings.pauseOnLostFocus=false;
                mc.gameSettings.limitFramerate=30;
                mc.gameSettings.renderDistanceChunks=4;
                stage=1;
                mc.launchIntegratedServer("build-fixture", "Build fixture",new WorldSettings(4267321,GameType.CREATIVE,false,false,WorldType.FLAT));
            } else if(stage==1 && mc.world!=null && mc.player!=null && mc.getIntegratedServer()!=null) {
                stage=2;
                pending=mc.getIntegratedServer().addScheduledTask(() -> {
                    try { RuntimeChecks.run(mc.getIntegratedServer(),"fresh"); }
                    catch(Exception error) { throw new RuntimeException(error); }
                    positionViewer(mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID()));
                });
            } else if(stage==2 && pending.isDone()) {
                pending.get();
                NamelessCorpseClientChecks.englishContract(mc);
                for(Entity entity : mc.world.loadedEntityList) if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(RuntimeChecks.NEAR)) {
                    mc.playerController.interactWithEntity(mc.player,entity,EnumHand.MAIN_HAND);
                    stage=3; break;
                }
            } else if(stage==3 && mc.currentScreen instanceof CorpseGUIContainer && ++frames>20) {
                RuntimeChecks.require(((CorpseGUIContainer)mc.currentScreen).inventorySlots.inventorySlots.size()==84,"rendered GUI slots");
                ScreenShotHelper.saveScreenshot(mc.gameDir,"corpse-gui-fresh.png",mc.mainWindow.getFramebufferWidth(),mc.mainWindow.getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));
                mc.player.closeScreen(); stage=4;
                FixtureConnections.disconnect(mc);
            } else if(stage==4 && FixtureConnections.stopped(mc)) {
                stage=5; mc.launchIntegratedServer("build-fixture","Build fixture",null);
            } else if(stage==5 && mc.player!=null && mc.world!=null && mc.getIntegratedServer()!=null) {
                stage=6;
                pending=mc.getIntegratedServer().addScheduledTask(() -> {
                    try { RuntimeChecks.run(mc.getIntegratedServer(),"deferred"); }
                    catch(Exception error) { throw new RuntimeException(error); }
                    positionViewer(mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID()));
                });
            } else if(stage==6 && pending.isDone()) {
                pending.get();
                for(Entity entity:mc.world.loadedEntityList) if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(RuntimeChecks.NEAR)) {
                    mc.playerController.interactWithEntity(mc.player,entity,EnumHand.MAIN_HAND);
                    stage=7; frames=0; break;
                }
            } else if(stage==7 && mc.currentScreen instanceof CorpseGUIContainer && ++frames>20) {
                ScreenShotHelper.saveScreenshot(mc.gameDir,"corpse-gui-reload.png",mc.mainWindow.getFramebufferWidth(),mc.mainWindow.getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));
                mc.player.closeScreen(); stage=20; connected.start(mc);
            } else if(stage==20 && connected.tick(mc)) {
                mc.player.closeScreen();drops.start(mc);stage=21;
            } else if(stage==21 && drops.tick(mc)) {
                mc.player.closeScreen();nbt.start(mc);stage=22;
            } else if(stage==22 && nbt.tick(mc)) {
                try(FileWriter out=new FileWriter(new File(mc.gameDir,"client.pass"))) {
                    out.write("fresh/reload GUI; connected death/respawn; network shift/normal recovery; two save/reloads verified\n");
                }
                System.out.println("LOOTABLE_BODIES_CLIENT_PASS");
                stage=8; mc.player.closeScreen(); FixtureConnections.disconnect(mc);
            } else if(stage==8 && FixtureConnections.stopped(mc)) {
                stage=9; mc.shutdown();
            }
        } catch(Throwable error) { fail(error); }
    }
    private static void positionViewer(net.minecraft.entity.player.EntityPlayerMP player) {
        WorldServer world=player.getServerWorld();
        RuntimeChecks.awaitChunk(world,0,0);
        for(int x=7;x<=9;x++)for(int z=6;z<=8;z++)
            world.setBlockState(new net.minecraft.util.math.BlockPos(x,79,z),net.minecraft.init.Blocks.STONE.getDefaultState());
        RuntimeChecks.require(world.getBlockState(new net.minecraft.util.math.BlockPos(8,79,7)).getBlock()==net.minecraft.init.Blocks.STONE,
                "Graphical recovery needs a stable floor below the viewing player");
        player.setGameType(GameType.CREATIVE);
        player.abilities.allowFlying=true;player.abilities.isFlying=true;player.sendPlayerAbilities();
        player.connection.setPlayerLocation(8.5,80,7.5,0,0);
    }
    private void fail(Throwable error) {
        error.printStackTrace();
        Minecraft.getInstance().shutdown();
        stage=99;
    }
}
