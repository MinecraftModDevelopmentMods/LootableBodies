package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.ClickType;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Two independent real Forge clients; no native desktop input or control. */
final class MultiplayerClientChecks {
    private final String role=System.getProperty("lootablebodies.multiplayer.role");
    private final Set<Integer> completed=new HashSet<>();
    private boolean connecting;
    private boolean finished;
    private int lastPhase=-1,frames,ticks;
    void register(){if(role!=null&&!Boolean.getBoolean("lootablebodies.visibility")&&!Boolean.getBoolean("lootablebodies.respawn")){FMLCommonHandler.instance().bus().register(this);MinecraftForge.EVENT_BUS.register(this);}}
    @SubscribeEvent public void mouse(net.minecraftforge.client.event.GuiScreenEvent.MouseInputEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void key(net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getMinecraft();
        if(mc.inGameHasFocus)mc.setIngameNotInFocus();net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        try{
            if(finished){mc.shutdown();return;}
            RuntimeChecks.require(++ticks<6000,"Multiplayer client timeout role="+role+" phase="+lastPhase);
            if(new File(MultiplayerChecks.sync(),"abort").exists())throw new AssertionError("Multiplayer runner abort");
            if(FixtureConnections.retry(mc,role))connecting=false;
            if(!connecting && mc.currentScreen instanceof GuiMainMenu){
                connecting=true;mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.limitFramerate=30;mc.gameSettings.renderDistanceChunks=4;
                FixtureConnections.connect(mc);
            }
            if(mc.world==null || mc.player==null)return;
            int phase;try{phase=Integer.parseInt(new String(Files.readAllBytes(new File(MultiplayerChecks.sync(),"phase").toPath()),StandardCharsets.UTF_8).trim());}catch(IOException busy){return;}
            if(phase!=lastPhase){lastPhase=phase;frames=0;System.out.println("TCP_CLIENT_PHASE "+role+" "+phase);}frames++;
            if(phase==8){
                try(FileWriter out=new FileWriter(new File(mc.gameDir,"multiplayer-client.pass"))){out.write("two-client packet/GUI checks passed\n");}
                System.out.println("LOOTABLE_BODIES_MULTIPLAYER_CLIENT_PASS role="+role);
                finished=true;mc.player.closeScreen();mc.world.sendQuittingDisconnectingPacket();mc.loadWorld(null);mc.displayGuiScreen(new GuiMainMenu());return;
            }
            if(frames%100==0)System.out.println("TCP_CLIENT_STATE role="+role+" phase="+phase+" screen="+(mc.currentScreen==null?"world":mc.currentScreen.getClass().getSimpleName())+" container="+mc.player.openContainer.getClass().getSimpleName()+" window="+mc.player.openContainer.windowId+" position="+mc.player.getPosition()+" completed="+completed);
            if(!(mc.currentScreen instanceof CorpseGUIContainer)){
                if(frames%10==0)for(Entity entity:mc.world.loadedEntityList)if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(MultiplayerChecks.BODY))mc.playerController.interactWithEntity(mc.player,entity,EnumHand.MAIN_HAND);
                return;
            }
            if(completed.contains(phase) || frames<15)return;int window=mc.player.openContainer.windowId;
            if(phase==1){
                if(!RuntimeChecks.sameStack(mc.player.openContainer.getSlot(6).getStack(),MultiplayerChecks.stack(19,23)))return;
                shot(mc,"shared-ready.png");ack(1);
            }else if(phase==2){mc.playerController.windowClick(window,6,0,ClickType.QUICK_MOVE,mc.player);ack(2);}
            else if(phase==3 && role.equals("A")){
                if(!RuntimeChecks.sameStack(mc.player.inventory.getItemStack(),MultiplayerChecks.stack(19,10)))return;
                mc.playerController.windowClick(window,-999,0,ClickType.QUICK_CRAFT,mc.player);mc.playerController.windowClick(window,6,1,ClickType.QUICK_CRAFT,mc.player);mc.playerController.windowClick(window,7,1,ClickType.QUICK_CRAFT,mc.player);ack(3);
            }else if(phase==4 && role.equals("B")){
                if(!RuntimeChecks.sameStack(mc.player.inventory.mainInventory.get(0),MultiplayerChecks.stack(20,7)))return;
                mc.playerController.windowClick(window,6,0,ClickType.SWAP,mc.player);ack(4);
            }else if(phase==5 && role.equals("A")){mc.playerController.windowClick(window,-999,2,ClickType.QUICK_CRAFT,mc.player);ack(5);}
            else if(phase==6 && role.equals("A")){
                MultiplayerChecks.equal(mc.player.inventory.getItemStack(),MultiplayerChecks.stack(19,5),"TCP client cursor prediction differs");
                mc.playerController.windowClick(window,48,0,ClickType.PICKUP,mc.player);ack(6);
            }else if(phase==7){
                MultiplayerChecks.equal(mc.player.openContainer.getSlot(6).getStack(),MultiplayerChecks.stack(20,7),"Shared GUI slot 6 differs");MultiplayerChecks.equal(mc.player.openContainer.getSlot(7).getStack(),MultiplayerChecks.stack(19,5),"Shared GUI slot 7 differs");
                MultiplayerChecks.equal(mc.player.inventory.getItemStack(),null,"Shared GUI cursor differs");shot(mc,"shared-final.png");ack(7);
            }
        }catch(Throwable error){error.printStackTrace();mc.shutdown();}
    }
    private void ack(int phase){completed.add(phase);MultiplayerChecks.CHANNEL.sendToServer(new MultiplayerChecks.Step(phase));}
    private void shot(Minecraft mc,String name){ScreenShotHelper.saveScreenshot(mc.gameDir,name,mc.displayWidth,mc.displayHeight,mc.getFramebuffer());}
}
