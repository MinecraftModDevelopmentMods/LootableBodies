package cyano.lootable.buildtest;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import cyano.lootable.graphics.GUIHandler;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Build-only verifier. Never included in the production source set or jars. */
@Mod("lootablebodiesbuildtest")
public final class RuntimeChecks {
    static final UUID NEAR = UUID.fromString("00000000-0000-4000-8000-000000000101");
    static final UUID FAR = UUID.fromString("00000000-0000-4000-8000-000000000102");
    static final UUID DEATH = UUID.fromString("00000000-0000-4000-8000-000000000103");
    public static volatile Throwable failure;
    private static boolean defaultsChecked;
    private static UUID pendingDeathId;
    private boolean rejectedFirstCorpseOpen;
    public RuntimeChecks() {
        MultiplayerChecks.registerChannel();
        net.minecraftforge.eventbus.api.IEventBus bus=net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        NativePreflightChecks.install(bus);
        bus.register(new TestRegistries());FixtureItems.register(bus);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.DistExecutor.runWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,()->()->{
            new ClientChecks().register();new MultiplayerClientChecks().register();new VisibilityClientChecks().register();new RespawnPacketClientChecks().register();
        });
    }
    @SubscribeEvent public void rejectFirstCorpseOpen(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if(Boolean.getBoolean("lootablebodies.rejectFirstCorpseOpen") && !rejectedFirstCorpseOpen
                && !event.getWorld().isClientSide && event.getTarget() instanceof EntityLootableBody) {
            rejectedFirstCorpseOpen=true;
            event.setCanceled(true);
            System.out.println("CORPSE_GUI_FIRST_INTERACTION_REJECTED_CONTROL");
        }
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public void fixtureAdmission(net.minecraftforge.event.entity.EntityJoinWorldEvent event) {
        if(pendingDeathId!=null && event.getEntity() instanceof EntityLootableBody && !event.getWorld().isClientSide
                && "BuildDeath".equals(((EntityLootableBody)event.getEntity()).getCustomNameTag()))event.getEntity().setUUID(pendingDeathId);
        if(Boolean.getBoolean("lootablebodies.assertFixtureAdmission") && event.getEntity() instanceof EntityLootableBody && !event.getWorld().isClientSide && !event.getEntity().isAddedToWorld()) {
            EntityLootableBody body=(EntityLootableBody)event.getEntity();
            require(event.getWorld().getChunkSource().hasChunk(net.minecraft.util.Mth.floor(body.getX())>>4,net.minecraft.util.Mth.floor(body.getZ())>>4),"Manual corpse fixture spawned before chunk admission: "+body.blockPosition()+" name="+body.getCustomNameTag());
        }
    }
    @SubscribeEvent public void started(FMLServerStartedEvent event) {
        if ((net.minecraftforge.fml.loading.FMLEnvironment.dist==net.minecraftforge.api.distmarker.Dist.CLIENT)) return;
        MinecraftServer server = net.minecraftforge.fmllegacy.server.ServerLifecycleHooks.getCurrentServer();
        if(Boolean.getBoolean("lootablebodies.respawn")) {
            try{new RespawnPacketChecks(server);}catch(Exception error){throw new RuntimeException(error);}return;
        }
        if(Boolean.getBoolean("lootablebodies.visibility")) {
            try{new VisibilityChecks(server);}catch(Exception error){throw new RuntimeException(error);}return;
        }
        if(Boolean.getBoolean("lootablebodies.multiplayer.server")) {
            try{new MultiplayerChecks(server);}catch(Exception error){throw new RuntimeException(error);}return;
        }
        try {
            run(server, System.getProperty("lootablebodies.phase", "fresh"));
        } catch (Throwable error) {
            failure = error;
            error.printStackTrace();
        } finally { server.halt(false); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static boolean sameStack(ItemStack first,ItemStack second) {
        return ItemStack.matches(first==null?ItemStack.EMPTY:first,second==null?ItemStack.EMPTY:second);
    }
    public static void run(MinecraftServer server, String phase) throws Exception {
        ServerLevel world = server.getLevel(FixtureApi.dimensionKey(0));
        File folder = world.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        require(LootableBodies.MODID.equals("lootablebodies") && LootableBodies.VERSION.equals("2.4.8.117011"), "identity/version");
        require(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").get().getModInfo().getVersion().toString().equals(phase.equals("seed") ? "2.4.0" : phase.equals("issues") ?
                        System.getProperty("lootablebodies.expectedIssueVersion","2.4.8.117011") : "2.4.8.117011"), "loaded release version");
        if (!defaultsChecked) require(LootableBodies.addBonesToCorpse && LootableBodies.displayNameTag && !LootableBodies.hurtByEnvironment &&
                !LootableBodies.hurtByAttacks && !LootableBodies.hurtByMisc && LootableBodies.corpseHP == 20 &&
                LootableBodies.ticksPerItemDecay == 6000 && LootableBodies.corpseDecayTime == 72000 &&
                LootableBodies.allowCorpseDecay && LootableBodies.decayOnlyWhenEmpty &&
                (phase.equals("seed") || !LootableBodies.corpseBuoyancy && !LootableBodies.voidPlatform && LootableBodies.dimensionSpecificPlatform), "default configuration");
        defaultsChecked=true;
        if(phase.equals("fresh")&&(net.minecraftforge.fml.loading.FMLEnvironment.dist==net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER))
            RespawnPacketChecks.verifySlowClientStartup(server);
        // Freeze timers only in this verifier while comparing exact inventories.
        LootableBodies.allowCorpseDecay = false;
        LootableBodies.ticksPerItemDecay = -1;
        if(phase.equals("preflight-conversion")) {
            NativePreflightChecks.verify(server);
        } else if(phase.equals("upgrade-contracts")) {
            UpgradeChecks.run(server);
        } else if(phase.startsWith("upgrade-")) {
            WorldUpgradeChecks.run(server,phase);
        } else if(phase.equals("visibility-reload")) {
            VisibilityChecks.reload(server);
        } else if(phase.equals("issues")) {
            ReportedIssueChecks.run(server,world,folder);
            ReportedIssueChecks.seedPaused();
        } else if(phase.equals("issues-reload")) {
            ReportedIssueChecks.coldReload(server,world,folder);
        } else if(phase.equals("multiplayer-reload")) {
            MultiplayerChecks.verifyReload(server);
        } else if (phase.equals("wall-escape")) {
            WallEscapeChecks.run(server,world,folder);
        } else if (phase.equals("wall-escape-reload")) {
            WallEscapeChecks.reload(world,folder);
        } else if (phase.startsWith("wall-historical")) {
            WallEscapeChecks.historical(server,folder,phase.endsWith("reload"));
        } else if (phase.equals("rotation")) {
            RotationFallChecks.run(server,world,folder);
        } else if (phase.equals("geometry")) {
            PositionMotionChecks.run(server,world,folder);
        } else if (phase.equals("effects")) {
            EffectHurtChecks.run(server,world,folder);
        } else if (phase.equals("health")) {
            HealthAttributeChecks.run(server,world,folder);
        } else if (phase.equals("scalars")) {
            NameLifetimeChecks.run(server,world,folder);
        } else if (phase.startsWith("nbt-")) {
            NbtWorldChecks.run(server,phase);
        } else if (phase.equals("nbt")) {
            NbtRecoveryChecks.run(server,world,folder);
        } else if (phase.equals("drops")) {
            ContainerInteractionChecks.runDrops(server,world,folder);
        } else if (phase.equals("interactions")) {
            ContainerInteractionChecks.run(server,world,folder);
        } else if (phase.equals("inventory")) {
            InventorySafetyChecks.run(server,world,folder);
        } else if (phase.equals("fresh") || phase.equals("seed")) {
            if (phase.equals("fresh")) PortBaselineChecks.run(server);
            if (phase.equals("fresh")) Recovery112Checks.run(server);
            if (phase.equals("fresh")) ReportedIssueChecks.run(server,world,folder);
            if (phase.equals("fresh")) InventorySafetyChecks.run(server,world,folder);
            if (phase.equals("fresh")) ContainerInteractionChecks.run(server,world,folder);
            if (phase.equals("fresh")) ContainerInteractionChecks.runDrops(server,world,folder);
            if (phase.equals("fresh")) NbtRecoveryChecks.run(server,world,folder);
            if (phase.equals("fresh")) NameLifetimeChecks.run(server,world,folder);
            if (phase.equals("fresh")) HealthAttributeChecks.run(server,world,folder);
            if (phase.equals("fresh")) EffectHurtChecks.run(server,world,folder);
            if (phase.equals("fresh")) PositionMotionChecks.run(server,world,folder);
            if (phase.equals("fresh")) RotationFallChecks.run(server,world,folder);
            if (phase.equals("fresh")) WallEscapeChecks.run(server,world,folder);
            require(!new File(folder, "near.nbt").exists(), "fresh fixture already exists");
            EntityLootableBody near = fixture(world, NEAR, 8, true);
            EntityLootableBody far = fixture(world, FAR, 4096, false);
            saveExpected(folder, "near", near);
            saveExpected(folder, "far", far);
            verifyRoundTrip(world, near);
            verifyRoundTrip(world, far);
            death(server, world, folder);
            if (phase.equals("fresh")) InventorySafetyChecks.seedBlockedRelease(world,folder);
            if (phase.equals("fresh")) ReportedIssueChecks.seedPaused();
        } else if (phase.equals("reload") || phase.equals("deferred")) {
            if (phase.equals("reload") && new File(folder,"wall-escape-checks.txt").isFile()) WallEscapeChecks.reload(world,folder);
            if(new File(folder,"issue17-chunks.txt").isFile()) ReportedIssueChecks.coldReload(server,world,folder);
            awaitChunk(world,0,0);
            EntityLootableBody near = find(world, NEAR);
            verifyExpected(folder, "near", near);
            awaitChunk(world,2,0);
            verifyExpected(folder, "death", find(world, DEATH));
            if(new File(folder,"rejected-release.nbt").isFile()) {
                awaitChunk(world,3,0);
                EntityLootableBody retained=find(world,UUID.fromString("00000000-0000-4000-8000-000000000105"));
                require(retained.isAlive(),"Blocked-release owner did not survive reload");
                // The saved profile is recreated lazily on the first entity tick.
                retained.baseTick();
                verifyExpected(folder,"rejected-release",retained);
            }
            if (phase.equals("deferred")) {
                awaitChunk(world,256,256);
                verifyExpected(folder, "far", find(world, FAR));
            } else {
                require(!world.getChunkSource().hasChunk(256, 256), "deferred chunk opened early");
            }
        } else throw new AssertionError("Unknown phase " + phase);
        server.saveAllChunks(false,true,false);
        File marker = new File(folder, phase + ".pass");
        try (FileWriter output = new FileWriter(marker)) { output.write("corpse-inventory-preserved\n"); }
        System.out.println("LOOTABLE_BODIES_RUNTIME_PASS phase=" + phase);
    }
    static net.minecraft.world.level.chunk.LevelChunk emptyChunk(ServerLevel world,int x,int z) {
        net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> registry=world.registryAccess().registryOrThrow(net.minecraft.core.Registry.BIOME_REGISTRY);
        int[] biomes=new int[16*((world.getHeight()+3)/4)];java.util.Arrays.fill(biomes,registry.getId(registry.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS)));
        return new net.minecraft.world.level.chunk.LevelChunk(world,new net.minecraft.world.level.ChunkPos(x,z),new net.minecraft.world.level.chunk.ChunkBiomeContainer(registry,world,biomes));
    }
    static ItemStack stack(int index) {
        ItemStack stack=index<4?new ItemStack(new net.minecraft.world.item.Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}[index]):index==4?new ItemStack(Items.DIAMOND_SWORD):index==5?new ItemStack(Items.SHIELD):new ItemStack(Items.LAPIS_LAZULI,1+index%32);
        CompoundTag custom = new CompoundTag();
        custom.putString("fixture", "slot-" + index);
        custom.put("typed_longs",new LongArrayTag(new long[]{Long.MIN_VALUE,index,Long.MAX_VALUE}));
        CompoundTag nested = new CompoundTag(); nested.putInt("unchanged", index * 17);
        ListTag contents = new ListTag();
        CompoundTag item = new CompoundTag(); new ItemStack(Items.DIAMOND,3).save(item); contents.add(item);
        nested.put("contents", contents); custom.put("nested", nested);
        stack.setTag(custom);if(index<=5)stack.setDamageValue(index<4?index+2:index==4?17:9); stack.setHoverName(new net.minecraft.network.chat.TextComponent("Fixture " + index));
        if (index == 4) stack.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 2);
        return stack;
    }
    static net.minecraft.world.level.chunk.LevelChunk awaitChunk(ServerLevel world,int x,int z) {
        net.minecraft.world.level.chunk.LevelChunk admitted=(net.minecraft.world.level.chunk.LevelChunk)world.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.ChunkStatus.FULL,true);
        require(world.getChunkSource().hasChunk(x,z),"Fixture must await native chunk admission before spawning a corpse");
        FixtureApi.awaitEntityInbox(world,x,z);
        return admitted;
    }
    static EntityLootableBody fixture(ServerLevel world, UUID uuid, int coordinate, boolean full) {
        awaitChunk(world,coordinate >> 4,coordinate >> 4);
        EntityLootableBody body = new EntityLootableBody(world);
        body.setUUID(uuid); body.setPos(coordinate+0.5, 80, coordinate+0.5);
        body.setNoGravity(true); body.setUserName("BuildFixture"); body.setRotation(37);
        require(body.getContainerSize() == 48, "48 inventory slots");
        for (int i=0; i<48; i++) if (full || i % 7 == 0 || i == 47) body.setItem(i,stack(i));
        if (full) { body.addItem(stack(70)); body.addItem(stack(71)); }
        disableVacuum(body);
        require(world.addFreshEntity(body), "fixture spawn");
        require(body.isAddedToWorld(),"Fresh fixture missed Forge's admission callback");
        return body;
    }
    static void verifyRoundTrip(ServerLevel world, EntityLootableBody body) {
        CompoundTag before = new CompoundTag(); body.saveAsPassenger(before);
        require(before.getString("id").equals("lootablebodies:corpse"), "persistent entity name: " + before.getString("id"));
        Entity copy = FixtureApi.loadEntity(before.copy(), world);
        require(copy instanceof EntityLootableBody, "NBT factory registration");
        CompoundTag after = new CompoundTag(); copy.saveAsPassenger(after);
        compare(before,after);
        if (body.getUUID().equals(NEAR)) {
            require(before.getList("Equipment",10).size()==48, "all indexed stacks");
            require(before.getList("Aux",10).size()==2, "ordered overflow");
        }
    }
    static void death(MinecraftServer server, ServerLevel world, File folder) throws Exception {
        ServerPlayer player = new ServerPlayer(server, world,
                new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000104"),"BuildDeath"));
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player);
        player.setPos(32.5,80,0.5); awaitChunk(world,2,0); require(world.getChunkSource().hasChunk(2,0),"Death fixture must await native chunk admission before spawning its corpse");
        world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);
        player.getInventory().items.set(0,stack(4));
        player.getInventory().items.set(9,stack(19));
        player.setItemSlot(EquipmentSlot.HEAD,stack(0));
        player.setItemSlot(EquipmentSlot.OFFHAND,stack(5));
        // Assign the fixture ID before the native entity-section manager indexes
        // the real death-created body, never by mutating an admitted UUID.
        pendingDeathId=DEATH;
        try {player.die(DamageSource.OUT_OF_WORLD);}finally{pendingDeathId=null;}
        EntityLootableBody corpse = null;
        for (Entity entity : new ArrayList<Entity>(FixtureApi.entities(world)))
            if (entity instanceof EntityLootableBody && "BuildDeath".equals(((EntityLootableBody)entity).getCustomNameTag())) corpse=(EntityLootableBody)entity;
        require(corpse != null, "actual player death created corpse");
        require(ItemStack.matches(corpse.getItem(0),stack(0)),"death helmet");
        require(ItemStack.matches(corpse.getItem(4),stack(4)),"death main hand");
        require(ItemStack.matches(corpse.getItem(5),stack(5)),"death offhand");
        require(ItemStack.matches(corpse.getItem(6),stack(19)),"death inventory custom NBT");
        Object gui = GUIHandler.getInstance().getServerGuiElement(0,player,world,corpse.getId(),0,0);
        require(gui instanceof CorpseContainer,"server inventory GUI");
        CorpseContainer container = (CorpseContainer)gui;
        require(container.slots.size()==84 && container.stillValid(player),"container slots/access");
        ItemStack recovered = container.quickMoveStack(player,6);
        require(ItemStack.matches(recovered,stack(19)),"shift-click recovery");
        require((corpse.getItem(6)).isEmpty(),"no duplicate in corpse");
        require(corpse.getUUID().equals(DEATH) && world.getEntity(DEATH)==corpse,"Death fixture UUID was not indexed before admission");corpse.setNoGravity(true);
        corpse.setDeltaMovement(0,corpse.getDeltaMovement().y,corpse.getDeltaMovement().z); corpse.setDeltaMovement(corpse.getDeltaMovement().x,0,corpse.getDeltaMovement().z); corpse.setDeltaMovement(corpse.getDeltaMovement().x,corpse.getDeltaMovement().y,0);
        disableVacuum(corpse);
        saveExpected(folder,"death",corpse);
    }
    static EntityLootableBody find(ServerLevel world, UUID uuid) {
        for(Entity entity : FixtureApi.entities(world)) if(entity.getUUID().equals(uuid)) return (EntityLootableBody)entity;
        throw new AssertionError("Saved corpse missing " + uuid);
    }
    static void disableVacuum(EntityLootableBody body) {
        try {
            java.lang.reflect.Field field = EntityLootableBody.class.getDeclaredField("vacuumTime");
            field.setAccessible(true); field.setByte(body,(byte)20);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    static void saveExpected(File folder,String name,EntityLootableBody body) throws Exception {
        CompoundTag data = new CompoundTag(); body.saveAsPassenger(data);
        NbtIo.writeCompressed(data,new FileOutputStream(new File(folder,name+".nbt")));
    }
    static void verifyExpected(File folder,String name,EntityLootableBody body) throws Exception {
        CompoundTag expected = NbtIo.readCompressed(new FileInputStream(new File(folder,name+".nbt")));
        CompoundTag actual = new CompoundTag(); body.saveAsPassenger(actual); compare(expected,actual);
    }
    static void compare(CompoundTag expected,CompoundTag actual) {
        for(String key : Arrays.asList("id","UUID","UUIDMost","UUIDLeast","Pos","Rotation","CustomName","Equipment","Aux","DeathTime","DecayTime"))
            require(Objects.equals(expected.get(key),actual.get(key)),"Saved field differs " + key + ": " + expected.get(key) + " != " + actual.get(key));
        if(expected.contains("Name")) require(expected.getString("Name").equals(actual.getString("Name")),"profile name");
    }
}
