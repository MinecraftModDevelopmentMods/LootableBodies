package buildcontrol;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.event.TickEvent;
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
            else {System.out.println("FORGE_PLATFORM_CONTROL_PASS");event.getServer().initiateShutdown(false);}
        }
    }
    private int checkTicks,checkDeaths;
    @SubscribeEvent public void deathTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !Boolean.getBoolean("lootablebodies.control.deaths"))return;
        net.minecraft.server.MinecraftServer server=net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
        net.minecraft.entity.player.ServerPlayerEntity p=server.getPlayerList().getPlayerByUsername("ControlOnly");
        if(p==null || !p.isAlive())return;
        if(++checkTicks==1) {
            p.setGameType(net.minecraft.world.GameType.SURVIVAL);
            double x=checkDeaths%2==0?96.5:-.4,z=checkDeaths%2==0?96.5:-.4;
            for(int a=-6;a<=6;a++)for(int b=-6;b<=6;b++)p.world.setBlockState(new net.minecraft.util.math.BlockPos(x+a,80,z+b),net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
            p.func_242111_a(p.world.getDimensionKey(),new net.minecraft.util.math.BlockPos((int)Math.floor(x),80,(int)Math.floor(z)-3),0F,true,false);
            p.connection.setPlayerLocation(x,81,z,0,0);
        }
        if(checkTicks==50){
            int px=(int)Math.floor(p.getPosX()),pz=(int)Math.floor(p.getPosZ());
            for(int ax=-6;ax<=6;ax++)for(int az=-6;az<=6;az++){
                p.world.setBlockState(new net.minecraft.util.math.BlockPos(px+ax,77,pz+az),net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
                p.world.removeBlock(new net.minecraft.util.math.BlockPos(px+ax,79,pz+az),false);
                p.world.removeBlock(new net.minecraft.util.math.BlockPos(px+ax,80,pz+az),false);
            }
            p.onKillCommand();checkTicks=0;System.out.println("NATIVE_DEATH "+(++checkDeaths));}
    }
    public static final class ClientControl {
        private int stage,ticks;
        @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
            if(event.phase!=TickEvent.Phase.END)return;
            net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
            if(Integer.getInteger("lootablebodies.control.port",0)>0) {tcp(mc);return;}
            if(stage==0 && mc.currentScreen instanceof net.minecraft.client.gui.screen.MainMenuScreen) {
                mc.gameSettings.pauseOnLostFocus=false;
                if(Boolean.getBoolean("lootablebodies.control.world")) {
                    createWorld(mc);stage=1;
                }else finish(mc);
            }else if(stage==1 && mc.world!=null && mc.player!=null) {
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("config/forge-client.toml"),"\n# disposable watcher control\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
                }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                stage=4;
            }else if(stage==4 && ++ticks>=80) {
                mc.world.sendQuittingDisconnectingPacket();mc.unloadWorld();stage=2;
            }else if(stage==2 && mc.world==null && !mc.isIntegratedServerRunning())finish(mc);
        }
        private int connections,wait,deathTicks,respawns;
        private volatile boolean hold;
        private boolean installed;
        private io.netty.channel.ChannelHandlerContext delayContext;
        private final java.util.List<Object[]> delayed=new java.util.ArrayList<>();
        private void delayNativePackets(net.minecraft.client.Minecraft mc) {
            if(!installed) {
                installed=true;
                mc.getConnection().getNetworkManager().channel().pipeline().addBefore("packet_handler","native_latency_control",new io.netty.channel.ChannelOutboundHandlerAdapter(){
                    @Override public void handlerAdded(io.netty.channel.ChannelHandlerContext ctx){delayContext=ctx;}
                    @Override public void write(io.netty.channel.ChannelHandlerContext ctx,Object message,io.netty.channel.ChannelPromise promise) throws Exception {
                        if(hold || !delayed.isEmpty()){delayed.add(new Object[]{message,promise});return;}
                        super.write(ctx,message,promise);
                    }
                });
            }
            boolean next=mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen && deathTicks<40;
            boolean release=hold && !next;hold=next;
            if(release)delayContext.executor().execute(()->{
                if(hold)return;
                System.out.println("NATIVE_FIFO_DELAY_RELEASE packets="+delayed.size());
                for(Object[] pending:delayed)delayContext.write(pending[0],(io.netty.channel.ChannelPromise)pending[1]);
                delayed.clear();delayContext.flush();
            });
        }
        private net.minecraft.client.network.ServerPinger pinger;
        private net.minecraft.client.multiplayer.ServerData destination;
        private void tcp(net.minecraft.client.Minecraft mc) {
            mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.framerateLimit=30;
            if(++wait>1600)throw new AssertionError("Native Forge control login timed out");
            if(stage==1 && wait%40==0 && mc.currentScreen instanceof net.minecraft.client.gui.screen.ConnectingScreen) {
                try {
                    for(java.lang.reflect.Field field:net.minecraft.client.gui.screen.ConnectingScreen.class.getDeclaredFields())
                        if(field.getType()==net.minecraft.network.NetworkManager.class) {
                            field.setAccessible(true);net.minecraft.network.NetworkManager manager=(net.minecraft.network.NetworkManager)field.get(mc.currentScreen);
                            if(manager!=null && manager.channel()!=null) {
                                System.out.println("NATIVE_FORGE_TCP_STATE autoRead="+manager.channel().config().isAutoRead()+" active="+manager.channel().isActive()+" protocol="+manager.channel().attr(net.minecraft.network.NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get());
                            }
                        }
                }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            }
            if(stage==0 && mc.currentScreen instanceof net.minecraft.client.gui.screen.MainMenuScreen) {
                if(connections>=10){finish(mc);return;}
                destination=new net.minecraft.client.multiplayer.ServerData("Owned native control","127.0.0.1:"+Integer.getInteger("lootablebodies.control.port"),false);
                if(Boolean.getBoolean("lootablebodies.control.ping")) {
                    pinger=new net.minecraft.client.network.ServerPinger();
                    try{pinger.ping(destination,()->{});}catch(java.net.UnknownHostException failure){throw new IllegalStateException(failure);}
                    stage=5;
                }else {mc.displayGuiScreen(new net.minecraft.client.gui.screen.ConnectingScreen(new net.minecraft.client.gui.screen.MainMenuScreen(),mc,destination));stage=1;}
                wait=0;
            }else if(stage==5) {
                pinger.pingPendingNetworks();
                if(destination.pingToServer>=0){pinger.clearPendingNetworks();mc.displayGuiScreen(new net.minecraft.client.gui.screen.ConnectingScreen(new net.minecraft.client.gui.screen.MainMenuScreen(),mc,destination));stage=1;wait=0;}
            }else if(stage==1 && mc.world!=null && mc.player!=null) {
                if(Boolean.getBoolean("lootablebodies.control.deaths")) {
                    delayNativePackets(mc);
                    if(mc.currentScreen instanceof net.minecraft.client.gui.screen.DeathScreen) {
                        if(++deathTicks==60){mc.player.respawnPlayer();System.out.println("NATIVE_RESPAWN "+(++respawns));}
                    }else if(mc.player.isAlive())deathTicks=0;
                    if(respawns>=8){finish(mc);return;}
                    wait=0;return;
                }
                System.out.println("NATIVE_FORGE_TCP_JOIN "+(++connections));
                mc.world.sendQuittingDisconnectingPacket();mc.unloadWorld();mc.displayGuiScreen(new net.minecraft.client.gui.screen.MainMenuScreen());stage=0;wait=0;
            }else if(stage==1 && mc.currentScreen instanceof net.minecraft.client.gui.screen.DisconnectedScreen) {
                throw new AssertionError("NATIVE_FORGE_TCP_LOGIN_FAILURE joins="+connections);
            }
        }
        private void finish(net.minecraft.client.Minecraft mc) {
            System.out.println("FORGE_PLATFORM_CONTROL_PASS");mc.shutdown();stage=3;
        }
        private void createWorld(net.minecraft.client.Minecraft client) {
            net.minecraft.util.registry.DynamicRegistries.Impl registries=net.minecraft.util.registry.DynamicRegistries.func_239770_b_();
            net.minecraft.util.registry.Registry<net.minecraft.world.DimensionType> types=registries.getRegistry(net.minecraft.util.registry.Registry.DIMENSION_TYPE_KEY);
            net.minecraft.util.registry.Registry<net.minecraft.world.biome.Biome> biomes=registries.getRegistry(net.minecraft.util.registry.Registry.BIOME_KEY);
            net.minecraft.world.gen.settings.DimensionGeneratorSettings settings=new net.minecraft.world.gen.settings.DimensionGeneratorSettings(4267321L,false,false,
                net.minecraft.world.gen.settings.DimensionGeneratorSettings.func_242749_a(types,
                    net.minecraft.world.DimensionType.getDefaultSimpleRegistry(types,biomes,registries.getRegistry(net.minecraft.util.registry.Registry.NOISE_SETTINGS_KEY),4267321L),
                    new net.minecraft.world.gen.FlatChunkGenerator(net.minecraft.world.gen.FlatGenerationSettings.func_242869_a(biomes))));
            client.createWorld("platform-fixture",new net.minecraft.world.WorldSettings("Platform fixture",net.minecraft.world.GameType.CREATIVE,false,net.minecraft.world.Difficulty.NORMAL,false,new net.minecraft.world.GameRules(),net.minecraft.util.datafix.codec.DatapackCodec.VANILLA_CODEC),registries,settings);
        }
    }
}
