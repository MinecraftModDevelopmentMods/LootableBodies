package cyano.lootable.buildtest;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.network.chat.TextComponent;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;

/** Respawns on the death notification, without waiting for a corpse spawn. */
final class RespawnPacketClientChecks {
    private final String role=System.getProperty("lootablebodies.multiplayer.role");
    private int index=-1,phase=-1,frames,ticks,deathTicks,guiFrames;
    private boolean connecting,traced,requested,finished;
    private UUID uuid;
    private int entityId;
    private final Set<String> completed=new HashSet<>();
    private File observations;
    private Level profileWorld;
    void register(){if(Boolean.getBoolean("lootablebodies.respawn")&&role!=null){net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);MinecraftForge.EVENT_BUS.register(this);}}
    @SubscribeEvent public void mouse(net.minecraftforge.client.event.GuiScreenEvent.MouseClickedEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void key(net.minecraftforge.client.event.GuiScreenEvent.KeyboardKeyPressedEvent.Pre e){e.setCanceled(true);}
    private void respawn(Minecraft mc) {
        if((!role.equals("A")&&!Boolean.getBoolean("lootablebodies.respawn.simultaneous"))||mc.player==null)return;
        if(mc.screen instanceof DeathScreen) {
            int kind=index%8,delay=kind==1?1:kind==2?20:kind==3?60:0;
            if(!requested&&deathTicks>=delay){
                if(Boolean.getBoolean("lootablebodies.respawn.counterCollision")&&kind==2) {
                    if(phase!=2)return;
                    try {
                        java.lang.reflect.Field counter=null;
                        for(java.lang.reflect.Field field:Entity.class.getDeclaredFields())
                            if(java.lang.reflect.Modifier.isStatic(field.getModifiers())&&field.getType()==java.util.concurrent.atomic.AtomicInteger.class) {
                                RuntimeChecks.require(counter==null,"Ambiguous native entity counter");counter=field;
                            }
                        RuntimeChecks.require(counter!=null,"Missing native entity counter");counter.setAccessible(true);
                        // Native 1.14 increments before assigning the new entity ID.
                        ((java.util.concurrent.atomic.AtomicInteger)counter.get(null)).set(entityId-1);
                    } catch(ReflectiveOperationException error){throw new RuntimeException(error);}
                    observe(mc,"SEEDED_CLIENT_COUNTER_COLLISION next_id="+entityId);
                }
                requested=true;mc.player.respawn();observe(mc,"REQUEST_RESPAWN without_corpse_barrier delay="+delay);
            }
        } else if(mc.player.isAlive()){requested=false;deathTicks=0;}
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e) {
        Minecraft mc=Minecraft.getInstance();
        if(e.phase==TickEvent.Phase.START) {
            if(mc.level!=null&&mc.level!=profileWorld) {
                profileWorld=mc.level;
                // IPacket tests use cached synthetic profiles, never real accounts or
                // live profile-service availability. The local_texture default stays false.
                GameProfileCache fixtureCache=new GameProfileCache(null,new File(mc.gameDirectory,"fixture-usercache.json")) {
                    @Override public Optional<GameProfile> get(String name) {
                        RuntimeChecks.require(name.matches("Container[AB]"),"Unexpected packet fixture profile");
                        UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
                        GameProfile p=new GameProfile(id,name);
                        String texture="{\"timestamp\":0,\"profileId\":\""+id.toString().replace("-","")+"\",\"profileName\":\""+name+"\",\"textures\":{}}";
                        p.getProperties().put("textures",new Property("textures",Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8))));return Optional.of(p);
                    }
                };
                fixtureCache.setExecutor(mc::execute);SkullBlockEntity.setProfileCache(fixtureCache);
            }
            return;
        }
        if(mc.isWindowActive())mc.mouseHandler.releaseMouse();net.minecraft.client.KeyMapping.releaseAll();
        try {
            if(finished){mc.stop();return;}
            RuntimeChecks.require(++ticks<20000,"Respawn client total timeout");RuntimeChecks.require(!RespawnPacketChecks.file("abort").exists(),"Respawn runner aborted");
            String[] state=new String(Files.readAllBytes(RespawnPacketChecks.file("state").toPath()),StandardCharsets.UTF_8).trim().split(" ");
            int nextIndex=Integer.parseInt(state[0]),nextPhase=Integer.parseInt(state[1]);
            if(nextIndex!=index||nextPhase!=phase){index=nextIndex;phase=nextPhase;frames=0;guiFrames=0;System.out.println("RESPAWN_CLIENT_PHASE "+role+" "+index+" "+phase);}
            uuid=state[2].equals("none")?null:UUID.fromString(state[2]);entityId=Integer.parseInt(state[3]);frames++;
            if(FixtureConnections.retry(mc,role))connecting=false;
            if(!connecting&&FixtureConnections.ready(role)&&mc.screen instanceof TitleScreen) {
                connecting=true;mc.options.pauseOnLostFocus=false;mc.options.framerateLimit=30;mc.options.renderDistance=4;
                FixtureConnections.connect(mc);
            }
            if(mc.level==null||mc.player==null)return;
            if(!traced) {
                traced=true;observations=new File(mc.gameDirectory,"main-packet-observations.txt");
                RespawnPacketTrace.install(mc.getConnection().getConnection(),new File(mc.gameDirectory,"client-packets.txt"),packet->mc.submitAsync(()->{observe(mc,"AFTER "+packet);respawn(mc);}));
            }
            if(mc.screen instanceof DeathScreen)deathTicks++;
            respawn(mc);
            if(frames%100==0)observe(mc,"TICK phase="+phase+" frame="+frames);
            RuntimeChecks.require(frames<450,"Client corpse missing case="+index+" phase="+phase+" role="+role);
            String key=index+"-"+phase;if(completed.contains(key))return;
            if(phase==0&&frames>10){mc.player.closeContainer();ack(key);}
            else if(phase==1&&frames>20){
                if(index%4==1)Thread.sleep(250); // Coalesce an ordinary FIFO packet burst; no packet reordering.
                ack(key);
            } else if(phase==3&&frames>20) {
                File expected=RespawnPacketChecks.file("expected-"+index+"-"+role+".txt");if(!expected.isFile())return;
                for(String line:Files.readAllLines(expected.toPath(),StandardCharsets.UTF_8)) {
                    String[] pair=line.split(" ");Entity tracked=mc.level.getEntity(Integer.parseInt(pair[0]));
                    if(!(tracked instanceof EntityLootableBody)||!tracked.getUUID().equals(UUID.fromString(pair[1]))||tracked.isRemoved()||!FixtureApi.entities(mc.level).contains(tracked))return;
                }
                Entity entity=mc.level.getEntity(entityId);
                if(!(entity instanceof EntityLootableBody)||!entity.getUUID().equals(uuid)||!FixtureApi.entities(mc.level).contains(entity)||entity.isRemoved())return;
                if(role.equals("A")) {
                    if(!(mc.screen instanceof CorpseGUIContainer)){mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);return;}
                    boolean found=false;for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(slot).getItem(),RuntimeChecks.stack(520+index)))found=true;
                    if(!found||++guiFrames<10)return;
                }
                observe(mc,"VERIFIED owner_or_observer exact_uuid_loaded_after_respawn");
                if(index%8==0||index%8==7)Screenshot.grab(mc.gameDirectory,"respawn-case-"+index+".png",mc.getMainRenderTarget(),message->System.out.println(message.getString()));
                ack(key);
            } else if(phase==9) {
                new File(mc.gameDirectory,"respawn-client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_RESPAWN_CLIENT_PASS "+role);
                mc.player.closeContainer();
                // Close the transport before clearing the world. The next main
                // loop drains already queued destroy packets against that world;
                // clearing it here races vanilla 1.11's unguarded packet handler.
                mc.getConnection().getConnection().disconnect(new net.minecraft.network.chat.TextComponent("Disposable check complete"));
                finished=true;
            }
        } catch(Throwable failure){failure.printStackTrace();try{Files.write(RespawnPacketChecks.file(role+".failure").toPath(),failure.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}finished=true;mc.stop();}
    }
    private void ack(String key) throws Exception {completed.add(key);Files.write(RespawnPacketChecks.file(role+".case-"+key).toPath(),new byte[]{1});}
    private synchronized void observe(Minecraft mc,String event) {
        try(FileWriter out=new FileWriter(observations,true)) {
            StringBuilder bodies=new StringBuilder();if(mc.level!=null)for(Entity ent:FixtureApi.entities(mc.level))if(ent instanceof EntityLootableBody)bodies.append(ent.getId()).append(':').append(ent.getUUID()).append(':').append(ent.isRemoved()).append(',');
            Entity mapped=mc.level==null?null:mc.level.getEntity(entityId);
            out.write(System.nanoTime()+" case="+index+" phase="+phase+" "+event+" world="+(mc.level==null?"none":FixtureApi.dimensionId(mc.level))+" player="+(mc.player==null?"none":mc.player.getId())+" mapped="+(mapped==null?null:mapped.getUUID())+" loaded="+bodies+"\n");
        } catch(IOException error){throw new RuntimeException(error);}
    }
}
