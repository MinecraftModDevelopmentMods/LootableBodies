package buildcontrol;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
/** Independent native Forge control, without a Lootable Bodies dependency. */
@Mod("forgeplatformcontrol")
public final class ForgeControl {
    public ForgeControl() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.DistExecutor.runWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,()->()->net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ClientControl()));
    }
    @SubscribeEvent public void started(FMLServerStartedEvent event) {
        if(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").isPresent())throw new AssertionError("Independent Forge control contains Lootable Bodies");
        if(net.minecraftforge.fml.loading.FMLEnvironment.dist==net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER) {
            if(Boolean.getBoolean("lootablebodies.control.tcpServer"))System.out.println("FORGE_PLATFORM_CONTROL_READY");
            else {System.out.println("FORGE_PLATFORM_CONTROL_PASS");event.getServer().initiateShutdown();}
        }
    }
    public static final class ClientControl {
        private int stage,ticks;
        @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
            if(event.phase!=TickEvent.Phase.END)return;
            net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
            if(Integer.getInteger("lootablebodies.control.port",0)>0) {tcp(mc);return;}
            if(stage==0 && mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu) {
                mc.gameSettings.pauseOnLostFocus=false;
                if(Boolean.getBoolean("lootablebodies.control.world")) {
                    mc.launchIntegratedServer("platform-fixture","Platform fixture",new net.minecraft.world.WorldSettings(4267321,net.minecraft.world.GameType.CREATIVE,false,false,net.minecraft.world.WorldType.FLAT));stage=1;
                }else finish(mc);
            }else if(stage==1 && mc.world!=null && mc.player!=null) {
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("config/forge-client.toml"),"\n# disposable watcher control\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
                }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                stage=4;
            }else if(stage==4 && ++ticks>=80) {
                mc.world.sendQuittingDisconnectingPacket();mc.loadWorld(null);stage=2;
            }else if(stage==2 && mc.world==null && !mc.isIntegratedServerRunning())finish(mc);
        }
        private int connections,wait;
        private net.minecraft.client.network.ServerPinger pinger;
        private net.minecraft.client.multiplayer.ServerData destination;
        private void tcp(net.minecraft.client.Minecraft mc) {
            mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.limitFramerate=30;
            if(++wait>1600)throw new AssertionError("Native Forge control login timed out");
            if(stage==1 && wait%40==0 && mc.currentScreen instanceof net.minecraft.client.gui.GuiConnecting) {
                try {
                    for(java.lang.reflect.Field field:net.minecraft.client.gui.GuiConnecting.class.getDeclaredFields())
                        if(field.getType()==net.minecraft.network.NetworkManager.class) {
                            field.setAccessible(true);net.minecraft.network.NetworkManager manager=(net.minecraft.network.NetworkManager)field.get(mc.currentScreen);
                            if(manager!=null && manager.channel()!=null) {
                                System.out.println("NATIVE_FORGE_TCP_STATE autoRead="+manager.channel().config().isAutoRead()+" active="+manager.channel().isActive()+" protocol="+manager.channel().attr(net.minecraft.network.NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get());
                            }
                        }
                }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            }
            if(stage==0 && mc.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu) {
                if(connections>=10){finish(mc);return;}
                destination=new net.minecraft.client.multiplayer.ServerData("Owned native control","127.0.0.1:"+Integer.getInteger("lootablebodies.control.port"),false);
                if(Boolean.getBoolean("lootablebodies.control.ping")) {
                    pinger=new net.minecraft.client.network.ServerPinger();
                    try{pinger.ping(destination);}catch(java.net.UnknownHostException failure){throw new IllegalStateException(failure);}
                    stage=5;
                }else {mc.displayGuiScreen(new net.minecraft.client.gui.GuiConnecting(new net.minecraft.client.gui.GuiMainMenu(),mc,destination));stage=1;}
                wait=0;
            }else if(stage==5) {
                pinger.pingPendingNetworks();
                if(destination.pingToServer>=0){pinger.clearPendingNetworks();mc.displayGuiScreen(new net.minecraft.client.gui.GuiConnecting(new net.minecraft.client.gui.GuiMainMenu(),mc,destination));stage=1;wait=0;}
            }else if(stage==1 && mc.world!=null && mc.player!=null) {
                System.out.println("NATIVE_FORGE_TCP_JOIN "+(++connections));
                mc.world.sendQuittingDisconnectingPacket();mc.loadWorld(null);mc.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());stage=0;wait=0;
            }else if(stage==1 && mc.currentScreen instanceof net.minecraft.client.gui.GuiDisconnected) {
                throw new AssertionError("NATIVE_FORGE_TCP_LOGIN_FAILURE joins="+connections);
            }
        }
        private void finish(net.minecraft.client.Minecraft mc) {
            System.out.println("FORGE_PLATFORM_CONTROL_PASS");mc.shutdown();stage=3;
        }
    }
}
