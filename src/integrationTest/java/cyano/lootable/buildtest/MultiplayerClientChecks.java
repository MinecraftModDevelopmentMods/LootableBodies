package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.Screenshot;
import net.minecraftforge.common.MinecraftForge;

import net.minecraftforge.event.TickEvent;

/** Two independent real Forge clients; no native desktop input or control. */
final class MultiplayerClientChecks {
    private final String role=System.getProperty("lootablebodies.multiplayer.role");
    private final Set<Integer> completed=new HashSet<>();
    private boolean connecting;
    private boolean finished;
    private int lastPhase=-1,frames,ticks;
    void register(){if(role!=null&&!Boolean.getBoolean("lootablebodies.visibility")&&!Boolean.getBoolean("lootablebodies.respawn")){
            net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre.BUS.addListener(event -> true);
            net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre.BUS.addListener(event -> true);
            TickEvent.ClientTickEvent.Post.BUS.addListener(this::tick);
        }}
    private void tick(TickEvent.ClientTickEvent.Post event){
        Minecraft mc=Minecraft.getInstance();
        if(mc.isWindowActive())mc.mouseHandler.releaseMouse();net.minecraft.client.KeyMapping.releaseAll();
        try{
            if(finished){mc.stop();return;}
            RuntimeChecks.require(++ticks<6000,"Multiplayer client timeout role="+role+" phase="+lastPhase);
            if(new File(MultiplayerChecks.sync(),"abort").exists())throw new AssertionError("Multiplayer runner abort");
            if(FixtureConnections.retry(mc,role))connecting=false;
            if(!connecting && FixtureConnections.ready(role) && mc.screen instanceof TitleScreen){
                connecting=true;mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.options.renderDistance().set(4);
                FixtureConnections.connect(mc);
            }
            if(mc.level==null || mc.player==null)return;
            int phase;try{phase=Integer.parseInt(new String(Files.readAllBytes(new File(MultiplayerChecks.sync(),"phase").toPath()),StandardCharsets.UTF_8).trim());}catch(IOException busy){return;}
            if(phase!=lastPhase){lastPhase=phase;frames=0;System.out.println("TCP_CLIENT_PHASE "+role+" "+phase);}frames++;
            if(phase==8){
                try(FileWriter out=new FileWriter(new File(mc.gameDirectory,"multiplayer-client.pass"))){out.write("two-client packet/GUI checks passed\n");}
                System.out.println("LOOTABLE_BODIES_MULTIPLAYER_CLIENT_PASS role="+role);
                finished=true;mc.player.closeContainer();FixtureConnections.disconnect(mc);return;
            }
            if(frames%100==0)System.out.println("TCP_CLIENT_STATE role="+role+" phase="+phase+" screen="+(mc.screen==null?"world":mc.screen.getClass().getSimpleName())+" container="+mc.player.containerMenu.getClass().getSimpleName()+" window="+mc.player.containerMenu.containerId+" position="+mc.player.blockPosition()+" completed="+completed);
            if(!(mc.screen instanceof CorpseGUIContainer)){
                if(frames%10==0)for(Entity entity:FixtureApi.entities(mc.level))if(entity instanceof EntityLootableBody && entity.getUUID().equals(MultiplayerChecks.BODY))mc.gameMode.interact(mc.player, entity, new net.minecraft.world.phys.EntityHitResult(entity), InteractionHand.MAIN_HAND);
                return;
            }
            if(completed.contains(phase) || frames<15)return;int window=mc.player.containerMenu.containerId;
            if(phase==1){
                if(!RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(6).getItem(),MultiplayerChecks.stack(19,23)))return;
                shot(mc,"shared-ready.png");ack(1);
            }else if(phase==2){mc.gameMode.handleContainerInput(window,6,0,ContainerInput.QUICK_MOVE,mc.player);ack(2);}
            else if(phase==3 && role.equals("A")){
                if(!RuntimeChecks.sameStack(mc.player.containerMenu.getCarried(),MultiplayerChecks.stack(19,10)))return;
                mc.gameMode.handleContainerInput(window,-999,0,ContainerInput.QUICK_CRAFT,mc.player);mc.gameMode.handleContainerInput(window,6,1,ContainerInput.QUICK_CRAFT,mc.player);mc.gameMode.handleContainerInput(window,7,1,ContainerInput.QUICK_CRAFT,mc.player);ack(3);
            }else if(phase==4 && role.equals("B")){
                if(!RuntimeChecks.sameStack(mc.player.getInventory().getNonEquipmentItems().get(0),MultiplayerChecks.stack(20,7)))return;
                mc.gameMode.handleContainerInput(window,6,0,ContainerInput.SWAP,mc.player);ack(4);
            }else if(phase==5 && role.equals("A")){mc.gameMode.handleContainerInput(window,-999,2,ContainerInput.QUICK_CRAFT,mc.player);ack(5);}
            else if(phase==6 && role.equals("A")){
                MultiplayerChecks.equal(mc.player.containerMenu.getCarried(),MultiplayerChecks.stack(19,5),"TCP client cursor prediction differs");
                mc.gameMode.handleContainerInput(window,48,0,ContainerInput.PICKUP,mc.player);ack(6);
            }else if(phase==7){
                MultiplayerChecks.equal(mc.player.containerMenu.getSlot(6).getItem(),MultiplayerChecks.stack(20,7),"Shared GUI slot 6 differs");MultiplayerChecks.equal(mc.player.containerMenu.getSlot(7).getItem(),MultiplayerChecks.stack(19,5),"Shared GUI slot 7 differs");
                MultiplayerChecks.equal(mc.player.containerMenu.getCarried(),null,"Shared GUI cursor differs");shot(mc,"shared-final.png");ack(7);
            }
        }catch(Throwable error){error.printStackTrace();mc.stop();}
    }
    private void ack(int phase){completed.add(phase);MultiplayerChecks.CHANNEL.send(new MultiplayerChecks.Step(phase),net.minecraftforge.network.PacketDistributor.SERVER.noArg());}
    private void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,message->System.out.println(message.getString()));}
}
