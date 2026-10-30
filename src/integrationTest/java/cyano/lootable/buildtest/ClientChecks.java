package cyano.lootable.buildtest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;

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
    @SubscribeEvent public void tooltip(net.minecraftforge.client.event.RenderTooltipEvent.Color event) {
        if(Boolean.getBoolean("lootablebodies.configUiCheck"))try {configScreen.tooltip(event);}catch(Throwable error){fail(error);}
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
        if(mc.isWindowActive()) mc.mouseHandler.releaseMouse();
        net.minecraft.client.KeyMapping.releaseAll();
        if(stage!=reportedStage || ticks%60==0) {
            System.out.println("GRAPHICAL_STAGE "+stage+" screen="+(mc.screen==null?"world":mc.screen.getClass().getSimpleName())+
                    " position="+(mc.player==null?"none":mc.player.blockPosition())+" focus="+mc.isWindowActive());
            reportedStage=stage;
        }
        if(ticks>0 && ticks%150==0) Screenshot.grab(mc.gameDirectory,"diagnostic-stage-"+stage+"-tick-"+ticks+".png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
        if(++ticks > 12000) fail(new AssertionError("client timeout stage="+stage));
        try {
            RuntimeChecks.require(!new File(mc.gameDirectory,"abort").exists(),"Disposable graphical check aborted");
            if(Boolean.getBoolean("lootablebodies.configUiCheck")) {configScreen.tick(mc);return;}
            if(Boolean.getBoolean("lootablebodies.namelessClientCheck")) {namelessNames.tick(mc);return;}
            if(Boolean.getBoolean("lootablebodies.visibilitySkinCheck")) {visibilitySkins.tick(mc);return;}
            if(Boolean.getBoolean("lootablebodies.issueClientCheck")) {reportedIssues.tick(mc);return;}
            if(stage==0 && mc.screen instanceof TitleScreen) {
                mc.options.pauseOnLostFocus=false;
                mc.options.framerateLimit=30;
                mc.options.renderDistance=4;
                stage=1;
                FixtureApi.ClientWorlds.create(mc,"build-fixture","Build fixture",net.minecraft.world.level.GameType.CREATIVE,false);
            } else if(stage==1 && mc.level!=null && mc.player!=null && mc.getSingleplayerServer()!=null) {
                stage=2;
                pending=mc.getSingleplayerServer().submitAsync(() -> {
                    try { RuntimeChecks.run(mc.getSingleplayerServer(),"fresh"); }
                    catch(Exception error) { throw new RuntimeException(error); }
                    positionViewer(mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()));
                });
            } else if(stage==2 && pending.isDone()) {
                pending.get();
                NamelessCorpseClientChecks.englishContract(mc);
                for(Entity entity : FixtureApi.entities(mc.level)) if(entity instanceof EntityLootableBody && entity.getUUID().equals(RuntimeChecks.NEAR)) {
                    mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);
                    stage=3; break;
                }
            } else if(stage==3 && mc.screen instanceof CorpseGUIContainer && ++frames>20) {
                RuntimeChecks.require(((CorpseGUIContainer)mc.screen).getMenu().slots.size()==84,"rendered GUI slots");
                Screenshot.grab(mc.gameDirectory,"corpse-gui-fresh.png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
                mc.player.closeContainer(); stage=4;
                FixtureConnections.disconnect(mc);
            } else if(stage==4 && FixtureConnections.stopped(mc)) {
                stage=5; mc.loadLevel("build-fixture");
            } else if(stage==5 && mc.player!=null && mc.level!=null && mc.getSingleplayerServer()!=null) {
                stage=6;
                pending=mc.getSingleplayerServer().submitAsync(() -> {
                    try { RuntimeChecks.run(mc.getSingleplayerServer(),"deferred"); }
                    catch(Exception error) { throw new RuntimeException(error); }
                    positionViewer(mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()));
                });
            } else if(stage==6 && pending.isDone()) {
                pending.get();
                for(Entity entity:FixtureApi.entities(mc.level)) if(entity instanceof EntityLootableBody && entity.getUUID().equals(RuntimeChecks.NEAR)) {
                    mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);
                    stage=7; frames=0; break;
                }
            } else if(stage==7 && mc.screen instanceof CorpseGUIContainer && ++frames>20) {
                Screenshot.grab(mc.gameDirectory,"corpse-gui-reload.png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
                mc.player.closeContainer(); stage=20; connected.start(mc);
            } else if(stage==20 && connected.tick(mc)) {
                mc.player.closeContainer();drops.start(mc);stage=21;
            } else if(stage==21 && drops.tick(mc)) {
                mc.player.closeContainer();nbt.start(mc);stage=22;
            } else if(stage==22 && nbt.tick(mc)) {
                try(FileWriter out=new FileWriter(new File(mc.gameDirectory,"client.pass"))) {
                    out.write("fresh/reload GUI; connected death/respawn; network shift/normal recovery; two save/reloads verified\n");
                }
                System.out.println("LOOTABLE_BODIES_CLIENT_PASS");
                stage=8; mc.player.closeContainer(); FixtureConnections.disconnect(mc);
            } else if(stage==8 && FixtureConnections.stopped(mc)) {
                stage=9; mc.stop();
            }
        } catch(Throwable error) { fail(error); }
    }
    private static void positionViewer(net.minecraft.server.level.ServerPlayer player) {
        ServerLevel world=player.getLevel();
        RuntimeChecks.awaitChunk(world,0,0);
        for(int x=7;x<=9;x++)for(int z=6;z<=8;z++)
            world.setBlockAndUpdate(new net.minecraft.core.BlockPos(x,79,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        RuntimeChecks.require(world.getBlockState(new net.minecraft.core.BlockPos(8,79,7)).getBlock()==net.minecraft.world.level.block.Blocks.STONE,
                "Graphical recovery needs a stable floor below the viewing player");
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().mayfly=true;player.getAbilities().flying=true;player.onUpdateAbilities();
        player.connection.teleport(8.5,80,7.5,0,0);
    }
    private void fail(Throwable error) {
        error.printStackTrace();
        Minecraft.getInstance().stop();
        stage=99;
    }
}
