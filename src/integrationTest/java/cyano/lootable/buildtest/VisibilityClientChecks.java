package cyano.lootable.buildtest;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.entity.boss.dragon.phase.Phase;
import net.minecraft.util.text.StringTextComponent;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RenderLivingEvent;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;

/** Receives real Forge spawn/destroy/respawn packets; never takes native input focus. */
final class VisibilityClientChecks {
    private final String role=System.getProperty("lootablebodies.multiplayer.role");
    private int phase=-1,frames,ticks,guiFrames;
    private boolean connecting,respawnSent,finished;
    private final Set<Integer> done=new HashSet<>();
    private final Set<UUID> rendered=new HashSet<>();
    void register(){if(Boolean.getBoolean("lootablebodies.visibility")&&role!=null){net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);MinecraftForge.EVENT_BUS.register(this);}}
    @SubscribeEvent public void mouse(net.minecraftforge.client.event.GuiScreenEvent.MouseClickedEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void key(net.minecraftforge.client.event.GuiScreenEvent.KeyboardKeyPressedEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void rendered(RenderLivingEvent.Post<?,?> e){if(e.getEntity() instanceof EntityLootableBody)rendered.add(e.getEntity().getUniqueID());}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e) {
        if(e.phase!=TickEvent.Phase.END)return;Minecraft mc=Minecraft.getInstance();
        if(mc.isGameFocused())mc.mouseHelper.ungrabMouse();net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        try {
            if(finished){mc.shutdown();return;}
            RuntimeChecks.require(++ticks<15000,"Visibility client timeout role="+role+" phase="+phase);
            RuntimeChecks.require(!VisibilityChecks.file("abort").exists(),"Visibility runner aborted");
            int n=Integer.parseInt(new String(Files.readAllBytes(VisibilityChecks.file("phase").toPath()),StandardCharsets.UTF_8).trim());
            if(n!=phase){phase=n;frames=0;guiFrames=0;respawnSent=false;rendered.clear();System.out.println("VISIBILITY_CLIENT_PHASE "+role+" "+phase);}frames++;
            if(FixtureConnections.retry(mc,role))connecting=false;
            if(!connecting && FixtureConnections.ready(role) && mc.world==null && mc.currentScreen instanceof MainMenuScreen && (phase==0 || phase==9 || phase==11)) {
                connecting=true;mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.framerateLimit=30;mc.gameSettings.renderDistanceChunks=4;
                FixtureConnections.connect(mc);
            }
            if(mc.world==null || mc.player==null)return;
            if(frames%200==0)System.out.println("VISIBILITY_CLIENT_STATE "+role+" phase="+phase+" position="+mc.player.getPosition()+" rendered="+rendered+" screen="+(mc.currentScreen==null?null:mc.currentScreen.getClass().getSimpleName())+" container="+mc.player.openContainer.getClass().getSimpleName());
            if(phase==12 && frames%200==0) {
                EntityLootableBody b=body(mc,"first");RuntimeChecks.require(b!=null&&!b.removed&&!b.isInvisible(),"Corpse disappeared during five-minute wait");
            }
            if(done.contains(phase) || frames<20)return;
            if(phase==0 || phase==1 || phase==13){ack();return;}
            if(phase==2 || phase==14) {
                EntityLootableBody b=body(mc,phase==2?"first":"second");if(b==null)return;
                if(role.equals("A") && !(mc.currentScreen instanceof DeathScreen))return;
                RuntimeChecks.require(!b.removed&&!b.isInvisible(),"Received corpse is dead/invisible");shot(mc,"death-"+phase+".png");ack();
            } else if(phase==3 || phase==15) {
                if(role.equals("A")) {
                    if(mc.currentScreen instanceof DeathScreen){if(!respawnSent){mc.player.respawnPlayer();respawnSent=true;}return;}
                    if(!mc.player.isAlive())return;
                }
                if(body(mc,phase==3?"first":"second")!=null)ack();
            } else if(phase==4 || phase==16 || phase==19) {
                EntityLootableBody b=body(mc,phase==16?"second":"first");if(b==null)return;
                if(!(mc.currentScreen instanceof CorpseGUIContainer)) {
                    if(!rendered.contains(b.getUniqueID()))return;
                    mc.playerController.interactWithEntity(mc.player,b,Hand.MAIN_HAND);return;
                }
                int index=phase==16?411:410;boolean found=false;
                for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.openContainer.getSlot(slot).getStack(),RuntimeChecks.stack(index)))found=true;
                if(!found || ++guiFrames<10)return;shot(mc,"corpse-gui-"+phase+".png");ack();
            } else if(phase==5) {mc.player.closeScreen();ack();
            } else if(phase==6) {
                boolean present=body(mc,"first")!=null;if(present==role.equals("B"))ack();
            } else if(phase==7 || phase==9 || phase==11) {
                EntityLootableBody b=body(mc,"first");if(b!=null&&!b.removed&&!b.isInvisible()&&rendered.contains(b.getUniqueID())){shot(mc,"tracked-"+phase+".png");ack();}
            } else if(phase==8 || phase==10) {
                if(role.equals(phase==8?"A":"B")){mc.world.sendQuittingDisconnectingPacket();mc.func_213254_o();mc.displayGuiScreen(new MainMenuScreen());connecting=false;}
                ack();
            } else if(phase==12 || phase==120) {
                EntityLootableBody b=body(mc,"first");if(b!=null&&!b.removed&&rendered.contains(b.getUniqueID()))ack();
            } else if(phase==17) {
                if(role.equals("A"))for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.openContainer.getSlot(slot).getStack(),RuntimeChecks.stack(411)))mc.playerController.windowClick(mc.player.openContainer.windowId,slot,0,ClickType.QUICK_MOVE,mc.player);
                // The following acknowledgement is not inventory proof; the server checks the resulting packet state.
                ack();
            } else if(phase==18) {
                new File(mc.gameDir,"visibility-client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_VISIBILITY_CLIENT_PASS role="+role);
                mc.player.closeScreen();
                mc.getConnection().getNetworkManager().closeChannel(new net.minecraft.util.text.StringTextComponent("Disposable check complete"));
                finished=true;
            } else if(phase==20) {
                if(body(mc,"first")==null&&body(mc,"second")==null)ack();
            } else if(phase==21) {
                mc.player.rotationYaw=frames*3;mc.player.rotationPitch=25;
                EntityLootableBody a=body(mc,"first"),b=body(mc,"second");
                if(a!=null&&b!=null&&!a.removed&&!b.removed&&rendered.contains(a.getUniqueID())&&rendered.contains(b.getUniqueID())) {shot(mc,"chunk-reentry.png");ack();}
            }
        } catch(Throwable error){error.printStackTrace();finished=true;mc.shutdown();}
    }
    private EntityLootableBody body(Minecraft mc,String name) throws Exception {
        File file=VisibilityChecks.file(name+".uuid");if(!file.isFile())return null;UUID id=UUID.fromString(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        for(Entity e:FixtureApi.entities(mc.world))if(e instanceof EntityLootableBody&&e.getUniqueID().equals(id))return (EntityLootableBody)e;return null;
    }
    private void ack() throws Exception {done.add(phase);Files.write(VisibilityChecks.file(role+".visibility-"+phase).toPath(),new byte[]{1});}
    private static void shot(Minecraft mc,String name){ScreenShotHelper.saveScreenshot(mc.gameDir,name,mc.mainWindow.getFramebufferWidth(),mc.mainWindow.getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));}
}
