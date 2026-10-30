package cyano.lootable.buildtest;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.network.chat.TextComponent;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
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
    private final Set<UUID> deathRendered=new HashSet<>();
    private net.minecraft.network.Connection traced;
    private net.minecraft.client.multiplayer.ClientLevel profileWorld;
    void register(){if(Boolean.getBoolean("lootablebodies.visibility")&&role!=null){net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);MinecraftForge.EVENT_BUS.register(this);}}
    @SubscribeEvent public void mouse(net.minecraftforge.client.event.GuiScreenEvent.MouseClickedEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void key(net.minecraftforge.client.event.GuiScreenEvent.KeyboardKeyPressedEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void rendered(RenderLivingEvent.Post<?,?> e){
        if(e.getEntity() instanceof EntityLootableBody) {
            rendered.add(e.getEntity().getUUID());
            if(Minecraft.getInstance().screen instanceof DeathScreen && !e.getEntity().isRemoved() && !e.getEntity().isInvisible())deathRendered.add(e.getEntity().getUUID());
        }
    }
    @SubscribeEvent public void deathScreenDrawn(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post e) {
        if(!role.equals("A") || !(e.getGui() instanceof DeathScreen) || (phase!=2&&phase!=14) || done.contains(phase))return;
        Minecraft mc=Minecraft.getInstance();
        try {
            EntityLootableBody b=body(mc,phase==2?"first":"second");
            if(b==null || !deathRendered.contains(b.getUUID()))return;
            RuntimeChecks.require(!b.isRemoved()&&!b.isInvisible(),"Rendered death-screen corpse is dead/invisible");
            shot(mc,"death-"+phase+".png");
            String receipt="{\"phase\":"+phase+",\"uuid\":\""+b.getUUID()+"\",\"death_screen_drawn\":true,\"live_corpse_rendered\":true}";
            Files.write(VisibilityChecks.file(role+".death-render-"+phase+".json").toPath(),receipt.getBytes(StandardCharsets.UTF_8));
            System.out.println("VISIBILITY_DEATH_RENDER_PROOF "+role+" "+receipt);ack();
        } catch(Throwable error){error.printStackTrace();finished=true;mc.stop();}
    }
    @SubscribeEvent public void removed(net.minecraftforge.event.entity.EntityLeaveWorldEvent e) {
        Entity entity=e.getEntity();
        if(entity instanceof EntityLootableBody && entity.level.isClientSide) {
            System.out.println("VISIBILITY_CLIENT_REMOVAL "+role+" phase="+phase+" uuid="+entity.getUUID()+" reason="+entity.getRemovalReason()+" rendered="+rendered.contains(entity.getUUID()));
            StackWalker.getInstance().forEach(frame->System.out.println("VISIBILITY_REMOVAL_FRAME "+frame));
        }
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e) {
        Minecraft mc=Minecraft.getInstance();
        if(e.phase==TickEvent.Phase.START) {
            if(mc.level!=null&&mc.level!=profileWorld) {
                profileWorld=mc.level;
                // These two offline fixture accounts use explicit empty native
                // texture properties. Visibility does not depend on Mojang HTTP.
                net.minecraft.server.players.GameProfileCache cache=new net.minecraft.server.players.GameProfileCache(null,new File(mc.gameDirectory,"visibility-usercache.json"));
                cache.setExecutor(mc::execute);
                for(String name:new String[]{"ContainerA","ContainerB"}) {
                    UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
                    com.mojang.authlib.GameProfile profile=new com.mojang.authlib.GameProfile(id,name);
                    String texture="{\"timestamp\":0,\"profileId\":\""+id.toString().replace("-","")+"\",\"profileName\":\""+name+"\",\"textures\":{}}";
                    profile.getProperties().put("textures",new com.mojang.authlib.properties.Property("textures",Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8))));
                    cache.add(profile);
                }
                net.minecraft.world.level.block.entity.SkullBlockEntity.setProfileCache(cache);
            }
            return;
        }
        if(mc.isWindowActive())mc.mouseHandler.releaseMouse();net.minecraft.client.KeyMapping.releaseAll();
        try {
            if(finished){mc.stop();return;}
            RuntimeChecks.require(++ticks<15000,"Visibility client timeout role="+role+" phase="+phase);
            RuntimeChecks.require(!VisibilityChecks.file("abort").exists(),"Visibility runner aborted");
            int n=Integer.parseInt(new String(Files.readAllBytes(VisibilityChecks.file("phase").toPath()),StandardCharsets.UTF_8).trim());
            if(n!=phase){phase=n;frames=0;guiFrames=0;respawnSent=false;rendered.clear();deathRendered.clear();System.out.println("VISIBILITY_CLIENT_PHASE "+role+" "+phase);}frames++;
            if(FixtureConnections.retry(mc,role))connecting=false;
            if(!connecting && FixtureConnections.ready(role) && mc.level==null && mc.screen instanceof TitleScreen && (phase==0 || phase==9 || phase==11)) {
                connecting=true;mc.options.pauseOnLostFocus=false;mc.options.framerateLimit=30;mc.options.renderDistance=4;
                FixtureConnections.connect(mc);
            }
            if(mc.level==null || mc.player==null)return;
            net.minecraft.network.Connection connection=mc.getConnection().getConnection();
            if(connection!=traced){RespawnPacketTrace.install(connection,new File(mc.gameDirectory,"client-packets.txt"),null);traced=connection;}
            if(frames%200==0)System.out.println("VISIBILITY_CLIENT_STATE "+role+" phase="+phase+" position="+mc.player.blockPosition()+" rendered="+rendered+" screen="+(mc.screen==null?null:mc.screen.getClass().getName())+" deathScreen="+(mc.screen instanceof DeathScreen)+" done="+done+" firstBody="+body(mc,"first")+" secondBody="+body(mc,"second")+" container="+mc.player.containerMenu.getClass().getSimpleName());
            if(phase==12 && frames%200==0) {
                EntityLootableBody b=body(mc,"first");RuntimeChecks.require(b!=null&&!b.isRemoved()&&!b.isInvisible(),"Corpse disappeared during five-minute wait");
            }
            if(done.contains(phase) || frames<20)return;
            if(phase==0 || phase==1 || phase==13){ack();return;}
            if(phase==2 || phase==14) {
                // The owner proves the live corpse at the actual rendered death
                // screen. Native tracking may remove it before a later tick.
                if(role.equals("A"))return;
                EntityLootableBody b=body(mc,phase==2?"first":"second");if(b==null)return;
                if(!rendered.contains(b.getUUID()))return;
                RuntimeChecks.require(!b.isRemoved()&&!b.isInvisible(),"Received corpse is dead/invisible");shot(mc,"death-"+phase+".png");ack();
            } else if(phase==3 || phase==15) {
                if(role.equals("A")) {
                    if(mc.screen instanceof DeathScreen){if(!respawnSent){mc.player.respawn();respawnSent=true;}return;}
                    if(!mc.player.isAlive())return;
                }
                if(body(mc,phase==3?"first":"second")!=null)ack();
            } else if(phase==4 || phase==16 || phase==19) {
                EntityLootableBody b=body(mc,phase==16?"second":"first");if(b==null)return;
                if(!(mc.screen instanceof CorpseGUIContainer)) {
                    if(!rendered.contains(b.getUUID()))return;
                    if(frames%10==0)mc.gameMode.interact(mc.player,b,InteractionHand.MAIN_HAND);return;
                }
                int index=phase==16?411:410;boolean found=false;
                for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(slot).getItem(),RuntimeChecks.stack(index)))found=true;
                if(!found || ++guiFrames<10)return;shot(mc,"corpse-gui-"+phase+".png");ack();
            } else if(phase==5) {mc.player.closeContainer();ack();
            } else if(phase==6) {
                boolean present=body(mc,"first")!=null;if(present==role.equals("B"))ack();
            } else if(phase==7 || phase==9 || phase==11) {
                EntityLootableBody b=body(mc,"first");if(b!=null&&!b.isRemoved()&&!b.isInvisible()&&rendered.contains(b.getUUID())){shot(mc,"tracked-"+phase+".png");ack();}
            } else if(phase==8 || phase==10) {
                if(role.equals(phase==8?"A":"B")){mc.level.disconnect();mc.clearLevel();mc.setScreen(new TitleScreen());connecting=false;}
                ack();
            } else if(phase==12 || phase==120) {
                EntityLootableBody b=body(mc,"first");if(b!=null&&!b.isRemoved()&&rendered.contains(b.getUUID()))ack();
            } else if(phase==17) {
                if(role.equals("A"))for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(slot).getItem(),RuntimeChecks.stack(411)))mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,slot,0,ClickType.QUICK_MOVE,mc.player);
                // The following acknowledgement is not inventory proof; the server checks the resulting packet state.
                ack();
            } else if(phase==18) {
                new File(mc.gameDirectory,"visibility-client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_VISIBILITY_CLIENT_PASS role="+role);
                mc.player.closeContainer();
                mc.getConnection().getConnection().disconnect(new net.minecraft.network.chat.TextComponent("Disposable check complete"));
                finished=true;
            } else if(phase==20) {
                if(body(mc,"first")==null&&body(mc,"second")==null)ack();
            } else if(phase==21) {
                mc.player.setYRot(frames*3);mc.player.setXRot(25);
                EntityLootableBody a=body(mc,"first"),b=body(mc,"second");
                if(a!=null&&b!=null&&!a.isRemoved()&&!b.isRemoved()&&rendered.contains(a.getUUID())&&rendered.contains(b.getUUID())) {shot(mc,"chunk-reentry.png");ack();}
            }
        } catch(Throwable error){error.printStackTrace();finished=true;mc.stop();}
    }
    private EntityLootableBody body(Minecraft mc,String name) throws Exception {
        File file=VisibilityChecks.file(name+".uuid");if(!file.isFile())return null;UUID id=UUID.fromString(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        for(Entity e:FixtureApi.entities(mc.level))if(e instanceof EntityLootableBody&&e.getUUID().equals(id))return (EntityLootableBody)e;return null;
    }
    private void ack() throws Exception {done.add(phase);Files.write(VisibilityChecks.file(role+".visibility-"+phase).toPath(),new byte[]{1});}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->System.out.println(message.getString()));}
}
