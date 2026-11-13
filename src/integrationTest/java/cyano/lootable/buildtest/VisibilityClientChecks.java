package cyano.lootable.buildtest;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;

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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RenderLivingEvent;

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
    void register(){if(Boolean.getBoolean("lootablebodies.visibility")&&role!=null){
            net.minecraftforge.client.event.ScreenEvent.MouseButtonPressed.Pre.BUS.addListener(event -> true);
            net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre.BUS.addListener(event -> true);
            TickEvent.ClientTickEvent.Pre.BUS.addListener(event -> tick(true));
            TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick(false));
            RenderLivingEvent.Post.BUS.addListener(this::rendered);
            net.minecraftforge.client.event.ScreenEvent.Render.Post.BUS.addListener(this::deathScreenDrawn);
            net.minecraftforge.event.entity.EntityLeaveLevelEvent.BUS.addListener(this::removed);
            ProbeFrames.observe(this::readyForRealDeath);
        }}
    private void rendered(RenderLivingEvent.Post<?,?,?> event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(event.getRenderer() instanceof cyano.lootable.graphics.CorpseRenderer)
                || !(event.getState() instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState state)
                || !(mc.level.getEntity(state.id) instanceof EntityLootableBody body)) return;
        rendered.add(body.getUUID());
        if (mc.gui.screen() instanceof DeathScreen && !body.isRemoved() && !body.isInvisible()) deathRendered.add(body.getUUID());
    }

    private void readyForRealDeath(ProbeFrames.Frame frame) {
        if(!role.equals("A") || (phase!=1&&phase!=13) || done.contains(phase) || frames<20) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null || !mc.player.isAlive() || mc.gui.screen()!=null ||
                !mc.level.hasChunkAt(mc.player.blockPosition()) || frame.camera().entity()!=mc.player ||
                frame.camera().forwardVector().y()>-0.75F)return;
        // Client ticks can catch up before a world frame has actually drawn.
        // Native death removes the owner's tracking connection after20ticks;
        // begin that finite window only with a real, loaded, downward view.
        net.minecraft.world.phys.Vec3 p=mc.player.position();
        net.minecraft.world.phys.AABB futureBody=new net.minecraft.world.phys.AABB(
                p.x-0.425D,p.y,p.z-0.425D,p.x+0.425D,p.y+1.25D,p.z+0.425D).inflate(0.5D);
        if(!frame.frustum().isVisible(futureBody))return;
        try {
            String receipt="{\"phase\":"+phase+",\"native_world_frame\":true,\"render_tick\":"+frame.tick()+
                ",\"pitch\":"+mc.player.getXRot()+",\"corpse_box_in_native_frustum\":true,\"at_nanos\":"+System.nanoTime()+"}";
            Files.write(VisibilityChecks.file("A.predeath-render-"+phase+".json").toPath(),receipt.getBytes(StandardCharsets.UTF_8));
            System.out.println("VISIBILITY_PREDEATH_RENDER_READY "+receipt);ack();
        }catch(Throwable failure){failure.printStackTrace();finished=true;mc.stop();}
    }
    private void deathScreenDrawn(net.minecraftforge.client.event.ScreenEvent.Render.Post e) {
        if(!role.equals("A") || !(e.getScreen() instanceof DeathScreen) || (phase!=2&&phase!=14) || done.contains(phase))return;
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
    private void removed(net.minecraftforge.event.entity.EntityLeaveLevelEvent e) {
        Entity entity=e.getEntity();
        if(entity instanceof EntityLootableBody && entity.level().isClientSide()) {
            System.out.println("VISIBILITY_CLIENT_REMOVAL "+role+" phase="+phase+" uuid="+entity.getUUID()+" reason="+entity.getRemovalReason()+" rendered="+rendered.contains(entity.getUUID()));
            StackWalker.getInstance().forEach(frame->System.out.println("VISIBILITY_REMOVAL_FRAME "+frame));
        }
    }
    private void tick(boolean beforeClientTick) {
        Minecraft mc=Minecraft.getInstance();
        if(beforeClientTick) {
            if(mc.level!=null&&mc.level!=profileWorld) {
                profileWorld=mc.level;
                // These two offline fixture accounts use explicit empty native
                // texture properties. Visibility does not depend on Mojang HTTP.
                FixtureProfileCache.install(name->{
                    RuntimeChecks.require(name.matches("Container[AB]"),"Unexpected offline fixture profile");
                    UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
                    String texture="{\"timestamp\":0,\"profileId\":\""+id.toString().replace("-","")+"\",\"profileName\":\""+name+"\",\"textures\":{}}";
                    return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.of(
                            FixtureProfileCache.withTexture(id, name, Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8)))));
                });
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
            if(!connecting && FixtureConnections.ready(role) && mc.level==null && mc.gui.screen() instanceof TitleScreen && (phase==0 || phase==9 || phase==11)) {
                connecting=true;mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.options.renderDistance().set(4);
                FixtureConnections.connect(mc);
            }
            if(mc.level==null || mc.player==null)return;
            net.minecraft.network.Connection connection=mc.getConnection().getConnection();
            if(connection!=traced){RespawnPacketTrace.install(connection,new File(mc.gameDirectory,"client-packets.txt"),null);traced=connection;}
            if(frames%200==0)System.out.println("VISIBILITY_CLIENT_STATE "+role+" phase="+phase+" position="+mc.player.blockPosition()+" rendered="+rendered+" screen="+(mc.gui.screen()==null?null:mc.gui.screen().getClass().getName())+" deathScreen="+(mc.gui.screen() instanceof DeathScreen)+" done="+done+" firstBody="+body(mc,"first")+" secondBody="+body(mc,"second")+" container="+mc.player.containerMenu.getClass().getSimpleName());
            if(phase==12 && frames%200==0) {
                EntityLootableBody b=body(mc,"first");RuntimeChecks.require(b!=null&&!b.isRemoved()&&!b.isInvisible(),"Corpse disappeared during five-minute wait");
            }
            if(done.contains(phase) || frames<20)return;
            if(phase==0 || phase==1 || phase==13){if(!role.equals("A") || phase==0)ack();return;}
            if(phase==2 || phase==14) {
                // The owner proves the live corpse at the actual rendered death
                // screen. Native tracking may remove it before a later tick.
                if(role.equals("A"))return;
                EntityLootableBody b=body(mc,phase==2?"first":"second");if(b==null)return;
                if(!rendered.contains(b.getUUID()))return;
                RuntimeChecks.require(!b.isRemoved()&&!b.isInvisible(),"Received corpse is dead/invisible");shot(mc,"death-"+phase+".png");ack();
            } else if(phase==3 || phase==15) {
                if(role.equals("A")) {
                    if(mc.gui.screen() instanceof DeathScreen){if(!respawnSent){mc.player.respawn();respawnSent=true;}return;}
                    if(!mc.player.isAlive())return;
                }
                if(body(mc,phase==3?"first":"second")!=null)ack();
            } else if(phase==4 || phase==16 || phase==19) {
                EntityLootableBody b=body(mc,phase==16?"second":"first");if(b==null)return;
                if(!(mc.gui.screen() instanceof CorpseGUIContainer)) {
                    if(!rendered.contains(b.getUUID()))return;
                    if(frames%10==0)mc.gameMode.interact(mc.player, b, new net.minecraft.world.phys.EntityHitResult(b), InteractionHand.MAIN_HAND);return;
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
                if(role.equals(phase==8?"A":"B")){FixtureConnections.disconnect(mc);connecting=false;}
                ack();
            } else if(phase==12 || phase==120) {
                EntityLootableBody b=body(mc,"first");if(b!=null&&!b.isRemoved()&&rendered.contains(b.getUUID()))ack();
            } else if(phase==17) {
                if(role.equals("A"))for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(slot).getItem(),RuntimeChecks.stack(411)))mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,slot,0,ContainerInput.QUICK_MOVE,mc.player);
                // The following acknowledgement is not inventory proof; the server checks the resulting packet state.
                ack();
            } else if(phase==18) {
                new File(mc.gameDirectory,"visibility-client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_VISIBILITY_CLIENT_PASS role="+role);
                mc.player.closeContainer();
                mc.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("Disposable check complete"));
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
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,message->System.out.println(message.getString()));}
}
