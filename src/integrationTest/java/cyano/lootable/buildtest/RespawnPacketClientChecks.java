package cyano.lootable.buildtest;

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
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Respawns on the death notification, without waiting for a corpse spawn. */
final class RespawnPacketClientChecks {
    private final String role=System.getProperty("lootablebodies.multiplayer.role");
    private int index=-1,phase=-1,frames,ticks,deathTicks,guiFrames;
    private boolean connecting,traced,requested,finished;
    private UUID uuid;
    private int entityId;
    private final Set<String> completed=new HashSet<>();
    private File observations;
    private World profileWorld;
    void register(){if(Boolean.getBoolean("lootablebodies.respawn")&&role!=null){FMLCommonHandler.instance().bus().register(this);MinecraftForge.EVENT_BUS.register(this);}}
    @SubscribeEvent public void mouse(net.minecraftforge.client.event.GuiScreenEvent.MouseInputEvent.Pre e){e.setCanceled(true);}
    @SubscribeEvent public void key(net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent.Pre e){e.setCanceled(true);}
    private void respawn(Minecraft mc) {
        if((!role.equals("A")&&!Boolean.getBoolean("lootablebodies.respawn.simultaneous"))||mc.player==null)return;
        if(mc.currentScreen instanceof GuiGameOver) {
            int kind=index%8,delay=kind==1?1:kind==2?20:kind==3?60:0;
            if(!requested&&deathTicks>=delay){
                if(Boolean.getBoolean("lootablebodies.respawn.counterCollision")&&kind==2) {
                    if(phase!=2)return;
                    try {java.lang.reflect.Field counter=net.minecraftforge.fml.relauncher.ReflectionHelper.findField(Entity.class,"nextEntityID","field_70152_a");counter.setInt(null,entityId);}
                    catch(Exception error){throw new RuntimeException(error);}
                    observe(mc,"SEEDED_CLIENT_COUNTER_COLLISION next_id="+entityId);
                }
                requested=true;mc.player.respawnPlayer();observe(mc,"REQUEST_RESPAWN without_corpse_barrier delay="+delay);
            }
        } else if(mc.player.isEntityAlive()){requested=false;deathTicks=0;}
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent e) {
        Minecraft mc=Minecraft.getMinecraft();
        if(e.phase==TickEvent.Phase.START) {
            if(mc.world!=null&&mc.world!=profileWorld) {
                profileWorld=mc.world;
                // Packet tests use cached synthetic profiles, never real accounts or
                // live profile-service availability. The local_texture default stays false.
                TileEntitySkull.setProfileCache(new PlayerProfileCache(null,new File(mc.gameDir,"fixture-usercache.json")) {
                    @Override public GameProfile getGameProfileForUsername(String name) {
                        RuntimeChecks.require(name.matches("Container[AB]"),"Unexpected packet fixture profile");
                        UUID id=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
                        GameProfile p=new GameProfile(id,name);
                        String texture="{\"timestamp\":0,\"profileId\":\""+id.toString().replace("-","")+"\",\"profileName\":\""+name+"\",\"textures\":{}}";
                        p.getProperties().put("textures",new Property("textures",Base64.getEncoder().encodeToString(texture.getBytes(StandardCharsets.UTF_8))));return p;
                    }
                });
            }
            return;
        }
        if(mc.inGameHasFocus)mc.setIngameNotInFocus();net.minecraft.client.settings.KeyBinding.unPressAllKeys();
        try {
            if(finished){mc.shutdown();return;}
            RuntimeChecks.require(++ticks<20000,"Respawn client total timeout");RuntimeChecks.require(!RespawnPacketChecks.file("abort").exists(),"Respawn runner aborted");
            String[] state=new String(Files.readAllBytes(RespawnPacketChecks.file("state").toPath()),StandardCharsets.UTF_8).trim().split(" ");
            int nextIndex=Integer.parseInt(state[0]),nextPhase=Integer.parseInt(state[1]);
            if(nextIndex!=index||nextPhase!=phase){index=nextIndex;phase=nextPhase;frames=0;guiFrames=0;System.out.println("RESPAWN_CLIENT_PHASE "+role+" "+index+" "+phase);}
            uuid=state[2].equals("none")?null:UUID.fromString(state[2]);entityId=Integer.parseInt(state[3]);frames++;
            if(FixtureConnections.retry(mc,role))connecting=false;
            if(!connecting&&FixtureConnections.ready(role)&&mc.currentScreen instanceof GuiMainMenu) {
                connecting=true;mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.limitFramerate=30;mc.gameSettings.renderDistanceChunks=4;
                FixtureConnections.connect(mc);
            }
            if(mc.world==null||mc.player==null)return;
            if(!traced) {
                traced=true;observations=new File(mc.gameDir,"main-packet-observations.txt");
                RespawnPacketTrace.install(mc.getConnection().getNetworkManager(),new File(mc.gameDir,"client-packets.txt"),packet->mc.addScheduledTask(()->{observe(mc,"AFTER "+packet);respawn(mc);}));
            }
            if(mc.currentScreen instanceof GuiGameOver)deathTicks++;
            respawn(mc);
            if(frames%100==0)observe(mc,"TICK phase="+phase+" frame="+frames);
            RuntimeChecks.require(frames<450,"Client corpse missing case="+index+" phase="+phase+" role="+role);
            String key=index+"-"+phase;if(completed.contains(key))return;
            if(phase==0&&frames>10){mc.player.closeScreen();ack(key);}
            else if(phase==1&&frames>20){
                if(index%4==1)Thread.sleep(250); // Coalesce an ordinary FIFO packet burst; no packet reordering.
                ack(key);
            } else if(phase==3&&frames>20) {
                File expected=RespawnPacketChecks.file("expected-"+index+"-"+role+".txt");if(!expected.isFile())return;
                for(String line:Files.readAllLines(expected.toPath(),StandardCharsets.UTF_8)) {
                    String[] pair=line.split(" ");Entity tracked=mc.world.getEntityByID(Integer.parseInt(pair[0]));
                    if(!(tracked instanceof EntityLootableBody)||!tracked.getUniqueID().equals(UUID.fromString(pair[1]))||tracked.isDead||!mc.world.loadedEntityList.contains(tracked))return;
                }
                Entity entity=mc.world.getEntityByID(entityId);
                if(!(entity instanceof EntityLootableBody)||!entity.getUniqueID().equals(uuid)||!mc.world.loadedEntityList.contains(entity)||entity.isDead)return;
                if(role.equals("A")) {
                    if(!(mc.currentScreen instanceof CorpseGUIContainer)){mc.playerController.interactWithEntity(mc.player,entity,EnumHand.MAIN_HAND);return;}
                    boolean found=false;for(int slot=0;slot<48;slot++)if(RuntimeChecks.sameStack(mc.player.openContainer.getSlot(slot).getStack(),RuntimeChecks.stack(520+index)))found=true;
                    if(!found||++guiFrames<10)return;
                }
                observe(mc,"VERIFIED owner_or_observer exact_uuid_loaded_after_respawn");
                if(index%8==0||index%8==7)ScreenShotHelper.saveScreenshot(mc.gameDir,"respawn-case-"+index+".png",mc.displayWidth,mc.displayHeight,mc.getFramebuffer());
                ack(key);
            } else if(phase==9) {
                new File(mc.gameDir,"respawn-client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_RESPAWN_CLIENT_PASS "+role);
                mc.player.closeScreen();
                // Close the transport before clearing the world. The next main
                // loop drains already queued destroy packets against that world;
                // clearing it here races vanilla 1.11's unguarded packet handler.
                mc.getConnection().getNetworkManager().closeChannel(new net.minecraft.util.text.TextComponentString("Disposable check complete"));
                finished=true;
            }
        } catch(Throwable failure){failure.printStackTrace();try{Files.write(RespawnPacketChecks.file(role+".failure").toPath(),failure.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}finished=true;mc.shutdown();}
    }
    private void ack(String key) throws Exception {completed.add(key);Files.write(RespawnPacketChecks.file(role+".case-"+key).toPath(),new byte[]{1});}
    private synchronized void observe(Minecraft mc,String event) {
        try(FileWriter out=new FileWriter(observations,true)) {
            StringBuilder bodies=new StringBuilder();if(mc.world!=null)for(Entity ent:mc.world.loadedEntityList)if(ent instanceof EntityLootableBody)bodies.append(ent.getEntityId()).append(':').append(ent.getUniqueID()).append(':').append(ent.isDead).append(',');
            Entity mapped=mc.world==null?null:mc.world.getEntityByID(entityId);
            out.write(System.nanoTime()+" case="+index+" phase="+phase+" "+event+" world="+(mc.world==null?"none":mc.world.provider.getDimension())+" player="+(mc.player==null?"none":mc.player.getEntityId())+" mapped="+(mapped==null?null:mapped.getUniqueID())+" loaded="+bodies+"\n");
        } catch(IOException error){throw new RuntimeException(error);}
    }
}
