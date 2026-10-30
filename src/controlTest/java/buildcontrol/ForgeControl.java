package buildcontrol;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.event.TickEvent;
/** Independent native Forge control, without a Lootable Bodies dependency. */
@Mod("forgeplatformcontrol")
public final class ForgeControl {
    public ForgeControl() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
        net.minecraftforge.fml.DistExecutor.runWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,()->()->net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ClientControl()));
    }
    private void loadComplete(net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent event) {
        int rounds=Integer.getInteger("lootablebodies.control.resourceReloads",0);
        if(rounds>0)event.enqueueWork(()->ResourceControl.run(rounds));
    }
    @SubscribeEvent public void started(FMLServerStartedEvent event) {
        if(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").isPresent())throw new AssertionError("Independent Forge control contains Lootable Bodies");
        if(net.minecraftforge.fml.loading.FMLEnvironment.dist==net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER) {
            if(Boolean.getBoolean("lootablebodies.control.tcpServer"))System.out.println("FORGE_PLATFORM_CONTROL_READY");
            else {System.out.println("FORGE_PLATFORM_CONTROL_PASS");event.getServer().halt(false);}
        }
    }
    private int checkTicks,checkDeaths;
    @SubscribeEvent public void deathTick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || !Boolean.getBoolean("lootablebodies.control.deaths"))return;
        net.minecraft.server.MinecraftServer server=net.minecraftforge.fmllegacy.server.ServerLifecycleHooks.getCurrentServer();
        net.minecraft.server.level.ServerPlayer p=server.getPlayerList().getPlayerByName("ControlOnly");
        if(p==null || !p.isAlive())return;
        if(++checkTicks==1) {
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            double x=checkDeaths%2==0?96.5:-.4,z=checkDeaths%2==0?96.5:-.4;
            for(int a=-6;a<=6;a++)for(int b=-6;b<=6;b++)p.level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x+a,80,z+b),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
            p.setRespawnPosition(p.level.dimension(),new net.minecraft.core.BlockPos((int)Math.floor(x),80,(int)Math.floor(z)-3),0F,true,false);
            p.connection.teleport(x,81,z,0,0);
        }
        if(checkTicks==50){
            int px=(int)Math.floor(p.getX()),pz=(int)Math.floor(p.getZ());
            for(int ax=-6;ax<=6;ax++)for(int az=-6;az<=6;az++){
                p.level.setBlockAndUpdate(new net.minecraft.core.BlockPos(px+ax,77,pz+az),net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
                p.level.removeBlock(new net.minecraft.core.BlockPos(px+ax,79,pz+az),false);
                p.level.removeBlock(new net.minecraft.core.BlockPos(px+ax,80,pz+az),false);
            }
            p.kill();checkTicks=0;System.out.println("NATIVE_DEATH "+(++checkDeaths));}
    }
    public static final class ClientControl {
        private int stage,ticks;
        @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
            if(event.phase!=TickEvent.Phase.END)return;
            net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
            if(Integer.getInteger("lootablebodies.control.port",0)>0) {tcp(mc);return;}
            if(stage==0 && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                mc.options.pauseOnLostFocus=false;
                if(Boolean.getBoolean("lootablebodies.control.world")) {
                    createWorld(mc);stage=1;
                }else finish(mc);
            }else if(stage==1 && mc.level!=null && mc.player!=null) {
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("config/forge-client.toml"),"\n# disposable watcher control\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
                }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                stage=4;
            }else if(stage==4 && ++ticks>=80) {
                mc.level.disconnect();mc.clearLevel();stage=2;
            }else if(stage==2 && mc.level==null && !mc.hasSingleplayerServer())finish(mc);
        }
        private int connections,wait,deathTicks,respawns;
        private volatile boolean hold;
        private boolean installed;
        private io.netty.channel.ChannelHandlerContext delayContext;
        private final java.util.List<Object[]> delayed=new java.util.ArrayList<>();
        private void delayNativePackets(net.minecraft.client.Minecraft mc) {
            if(!installed) {
                installed=true;
                mc.getConnection().getConnection().channel().pipeline().addBefore("packet_handler","native_latency_control",new io.netty.channel.ChannelOutboundHandlerAdapter(){
                    @Override public void handlerAdded(io.netty.channel.ChannelHandlerContext ctx){delayContext=ctx;}
                    @Override public void write(io.netty.channel.ChannelHandlerContext ctx,Object message,io.netty.channel.ChannelPromise promise) throws Exception {
                        if(hold || !delayed.isEmpty()){delayed.add(new Object[]{message,promise});return;}
                        super.write(ctx,message,promise);
                    }
                });
            }
            boolean next=mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen && deathTicks<40;
            boolean release=hold && !next;hold=next;
            if(release)delayContext.executor().execute(()->{
                if(hold)return;
                System.out.println("NATIVE_FIFO_DELAY_RELEASE packets="+delayed.size());
                for(Object[] pending:delayed)delayContext.write(pending[0],(io.netty.channel.ChannelPromise)pending[1]);
                delayed.clear();delayContext.flush();
            });
        }
        private net.minecraft.client.multiplayer.ServerStatusPinger pinger;
        private net.minecraft.client.multiplayer.ServerData destination;
        private void tcp(net.minecraft.client.Minecraft mc) {
            mc.options.pauseOnLostFocus=false;mc.options.framerateLimit=30;
            if(++wait>1600)throw new AssertionError("Native Forge control login timed out");
            if(stage==1 && wait%40==0 && mc.screen instanceof net.minecraft.client.gui.screens.ConnectScreen) {
                try {
                    for(java.lang.reflect.Field field:net.minecraft.client.gui.screens.ConnectScreen.class.getDeclaredFields())
                        if(field.getType()==net.minecraft.network.Connection.class) {
                            field.setAccessible(true);net.minecraft.network.Connection manager=(net.minecraft.network.Connection)field.get(mc.screen);
                            if(manager!=null && manager.channel()!=null) {
                                System.out.println("NATIVE_FORGE_TCP_STATE autoRead="+manager.channel().config().isAutoRead()+" active="+manager.channel().isActive()+" protocol="+manager.channel().attr(net.minecraft.network.Connection.ATTRIBUTE_PROTOCOL).get());
                            }
                        }
                }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            }
            if(stage==0 && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                if(connections>=10){finish(mc);return;}
                destination=new net.minecraft.client.multiplayer.ServerData("Owned native control","127.0.0.1:"+Integer.getInteger("lootablebodies.control.port"),false);
                if(Boolean.getBoolean("lootablebodies.control.ping")) {
                    pinger=new net.minecraft.client.multiplayer.ServerStatusPinger();
                    try{pinger.pingServer(destination,()->{});}catch(java.net.UnknownHostException failure){throw new IllegalStateException(failure);}
                    stage=5;
                }else {net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),mc,net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(destination.ip),destination);stage=1;}
                wait=0;
            }else if(stage==5) {
                pinger.tick();
                if(destination.ping>=0){pinger.removeAll();net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(),mc,net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(destination.ip),destination);stage=1;wait=0;}
            }else if(stage==1 && mc.level!=null && mc.player!=null) {
                if(Boolean.getBoolean("lootablebodies.control.deaths")) {
                    delayNativePackets(mc);
                    if(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                        if(++deathTicks==60){mc.player.respawn();System.out.println("NATIVE_RESPAWN "+(++respawns));}
                    }else if(mc.player.isAlive())deathTicks=0;
                    if(respawns>=8){finish(mc);return;}
                    wait=0;return;
                }
                System.out.println("NATIVE_FORGE_TCP_JOIN "+(++connections));
                mc.level.disconnect();mc.clearLevel();mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());stage=0;wait=0;
            }else if(stage==1 && mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
                throw new AssertionError("NATIVE_FORGE_TCP_LOGIN_FAILURE joins="+connections);
            }
        }
        private void finish(net.minecraft.client.Minecraft mc) {
            System.out.println("FORGE_PLATFORM_CONTROL_PASS");mc.stop();stage=3;
        }
        private void createWorld(net.minecraft.client.Minecraft client) {
            net.minecraft.core.RegistryAccess.RegistryHolder registries=net.minecraft.core.RegistryAccess.builtin();
            net.minecraft.core.Registry<net.minecraft.world.level.dimension.DimensionType> types=registries.registryOrThrow(net.minecraft.core.Registry.DIMENSION_TYPE_REGISTRY);
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes=registries.registryOrThrow(net.minecraft.core.Registry.BIOME_REGISTRY);
            net.minecraft.world.level.levelgen.WorldGenSettings settings=new net.minecraft.world.level.levelgen.WorldGenSettings(4267321L,false,false,
                net.minecraft.world.level.levelgen.WorldGenSettings.withOverworld(types,
                    net.minecraft.world.level.dimension.DimensionType.defaultDimensions(types,biomes,registries.registryOrThrow(net.minecraft.core.Registry.NOISE_GENERATOR_SETTINGS_REGISTRY),4267321L),
                    new net.minecraft.world.level.levelgen.FlatLevelSource(net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(biomes))));
            client.createLevel("platform-fixture",new net.minecraft.world.level.LevelSettings("Platform fixture",net.minecraft.world.level.GameType.CREATIVE,false,net.minecraft.world.Difficulty.NORMAL,false,new net.minecraft.world.level.GameRules(),net.minecraft.world.level.DataPackConfig.DEFAULT),registries,settings);
        }
    }
}
