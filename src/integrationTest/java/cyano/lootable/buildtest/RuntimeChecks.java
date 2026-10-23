package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import cyano.lootable.graphics.GUIHandler;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Build-only verifier. Never included in the production source set or jars. */
@Mod("lootablebodiesbuildtest")
public final class RuntimeChecks {
    static final UUID NEAR = UUID.fromString("00000000-0000-4000-8000-000000000101");
    static final UUID FAR = UUID.fromString("00000000-0000-4000-8000-000000000102");
    static final UUID DEATH = UUID.fromString("00000000-0000-4000-8000-000000000103");
    public static volatile Throwable failure;
    private static boolean defaultsChecked;
    public RuntimeChecks() {
        MultiplayerChecks.registerChannel();
        net.minecraftforge.eventbus.api.IEventBus bus=net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        bus.register(new TestRegistries());FixtureItems.register(bus);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.DistExecutor.runWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,()->()->{
            new ClientChecks().register();new MultiplayerClientChecks().register();new VisibilityClientChecks().register();new RespawnPacketClientChecks().register();
        });
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public void fixtureAdmission(net.minecraftforge.event.entity.EntityJoinWorldEvent event) {
        if(Boolean.getBoolean("lootablebodies.assertFixtureAdmission") && event.getEntity() instanceof EntityLootableBody && !event.getWorld().isRemote && !event.getEntity().addedToChunk) {
            EntityLootableBody body=(EntityLootableBody)event.getEntity();
            require(event.getWorld().getChunk(body.getPosition()).isLoaded(),"Manual corpse fixture spawned before chunk admission: "+body.getPosition()+" name="+body.getCustomNameTag());
        }
    }
    @SubscribeEvent public void started(FMLServerStartedEvent event) {
        if ((net.minecraftforge.fml.loading.FMLEnvironment.dist==net.minecraftforge.api.distmarker.Dist.CLIENT)) return;
        MinecraftServer server = net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
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
        } finally { server.initiateShutdown(); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static boolean sameStack(ItemStack first,ItemStack second) {
        return ItemStack.areItemStacksEqual(first==null?ItemStack.EMPTY:first,second==null?ItemStack.EMPTY:second);
    }
    public static void run(MinecraftServer server, String phase) throws Exception {
        WorldServer world = server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));
        File folder = world.getSaveHandler().getWorldDirectory();
        require(LootableBodies.MODID.equals("lootablebodies") && LootableBodies.VERSION.equals("2.4.4.113021"), "identity/version");
        require(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").get().getModInfo().getVersion().toString().equals(phase.equals("seed") ? "2.4.0" : phase.equals("issues") ?
                        System.getProperty("lootablebodies.expectedIssueVersion","2.4.4.113021") : "2.4.4.113021"), "loaded release version");
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
        if(phase.equals("upgrade-contracts")) {
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
            world.getChunk(0, 0);
            EntityLootableBody near = find(world, NEAR);
            verifyExpected(folder, "near", near);
            world.getChunk(2, 0);
            verifyExpected(folder, "death", find(world, DEATH));
            if(new File(folder,"rejected-release.nbt").isFile()) {
                world.getChunk(3,0);
                EntityLootableBody retained=find(world,UUID.fromString("00000000-0000-4000-8000-000000000105"));
                require(retained.isAlive(),"Blocked-release owner did not survive reload");
                // The saved profile is recreated lazily on the first entity tick.
                retained.baseTick();
                verifyExpected(folder,"rejected-release",retained);
            }
            if (phase.equals("deferred")) {
                world.getChunk(256, 256);
                verifyExpected(folder, "far", find(world, FAR));
            } else {
                require(!world.getChunkProvider().chunkExists(256, 256), "deferred chunk opened early");
            }
        } else throw new AssertionError("Unknown phase " + phase);
        server.saveAllWorlds(false);
        File marker = new File(folder, phase + ".pass");
        try (FileWriter output = new FileWriter(marker)) { output.write("corpse-inventory-preserved\n"); }
        System.out.println("LOOTABLE_BODIES_RUNTIME_PASS phase=" + phase);
    }
    static net.minecraft.world.chunk.Chunk emptyChunk(WorldServer world,int x,int z) {
        net.minecraft.world.biome.Biome[] biomes=new net.minecraft.world.biome.Biome[256];java.util.Arrays.fill(biomes,net.minecraft.init.Biomes.PLAINS);return new net.minecraft.world.chunk.Chunk(world,x,z,biomes,net.minecraft.world.chunk.UpgradeData.EMPTY,world.getPendingBlockTicks(),world.getPendingFluidTicks(),0L);
    }
    static ItemStack stack(int index) {
        ItemStack stack=index<4?new ItemStack(new net.minecraft.item.Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}[index]):index==4?new ItemStack(Items.DIAMOND_SWORD):index==5?new ItemStack(Items.SHIELD):new ItemStack(Items.LAPIS_LAZULI,1+index%32);
        NBTTagCompound custom = new NBTTagCompound();
        custom.setString("fixture", "slot-" + index);
        custom.setTag("typed_longs",new NBTTagLongArray(new long[]{Long.MIN_VALUE,index,Long.MAX_VALUE}));
        NBTTagCompound nested = new NBTTagCompound(); nested.setInt("unchanged", index * 17);
        NBTTagList contents = new NBTTagList();
        NBTTagCompound item = new NBTTagCompound(); new ItemStack(Items.DIAMOND,3).write(item); contents.add(item);
        nested.setTag("contents", contents); custom.setTag("nested", nested);
        stack.setTag(custom);if(index<=5)stack.setDamage(index<4?index+2:index==4?17:9); stack.setDisplayName(new net.minecraft.util.text.TextComponentString("Fixture " + index));
        if (index == 4) stack.addEnchantment(net.minecraft.init.Enchantments.UNBREAKING, 2);
        return stack;
    }
    static net.minecraft.world.chunk.Chunk awaitChunk(WorldServer world,int x,int z) {
        net.minecraft.world.chunk.Chunk admitted=world.getChunk(x,z);
        // Generation completes on a worker, then queues Chunk.onLoad on the
        // server. Admit this disposable chunk before manually populating it.
        for(int tasks=0;!admitted.isLoaded();tasks++) {
            require(tasks<10000,"Native fixture chunk admission did not complete");
            java.util.concurrent.FutureTask<?> pending=world.getServer().futureTaskQueue.poll();
            require(pending!=null,"Missing native chunk admission task");pending.run();
            try{pending.get();}catch(Exception failed){throw new AssertionError("Native fixture admission failed",failed);}
        }
        require(admitted.isLoaded(),"Fixture must await native chunk admission before spawning a corpse");
        return admitted;
    }
    static EntityLootableBody fixture(WorldServer world, UUID uuid, int coordinate, boolean full) {
        awaitChunk(world,coordinate >> 4,coordinate >> 4);
        EntityLootableBody body = new EntityLootableBody(world);
        body.setUniqueId(uuid); body.setPosition(coordinate+0.5, 80, coordinate+0.5);
        body.setNoGravity(true); body.setUserName("BuildFixture"); body.setRotation(37);
        require(body.getSizeInventory() == 48, "48 inventory slots");
        for (int i=0; i<48; i++) if (full || i % 7 == 0 || i == 47) body.setInventorySlotContents(i,stack(i));
        if (full) { body.addItem(stack(70)); body.addItem(stack(71)); }
        disableVacuum(body);
        require(world.spawnEntity(body), "fixture spawn");
        return body;
    }
    static void verifyRoundTrip(WorldServer world, EntityLootableBody body) {
        NBTTagCompound before = new NBTTagCompound(); body.writeUnlessPassenger(before);
        require(before.getString("id").equals("lootablebodies:corpse"), "persistent entity name: " + before.getString("id"));
        Entity copy = net.minecraft.entity.EntityType.create(before.copy(), world);
        require(copy instanceof EntityLootableBody, "NBT factory registration");
        NBTTagCompound after = new NBTTagCompound(); copy.writeUnlessPassenger(after);
        compare(before,after);
        if (body.getUniqueID().equals(NEAR)) {
            require(before.getList("Equipment",10).size()==48, "all indexed stacks");
            require(before.getList("Aux",10).size()==2, "ordered overflow");
        }
    }
    static void death(MinecraftServer server, WorldServer world, File folder) throws Exception {
        EntityPlayerMP player = new EntityPlayerMP(server, world,
                new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000104"),"BuildDeath"),
                new net.minecraft.server.management.PlayerInteractionManager(world));
        player.connection = new net.minecraft.network.NetHandlerPlayServer(server,
                new net.minecraft.network.NetworkManager(net.minecraft.network.EnumPacketDirection.SERVERBOUND), player);
        player.setPosition(32.5,80,0.5); require(world.getChunk(2,0).isLoaded(),"Death fixture must await native chunk admission before spawning its corpse");
        world.getGameRules().setOrCreateGameRule("keepInventory","false",null);
        player.inventory.mainInventory.set(0,stack(4));
        player.inventory.mainInventory.set(9,stack(19));
        player.setItemStackToSlot(EntityEquipmentSlot.HEAD,stack(0));
        player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND,stack(5));
        player.onDeath(DamageSource.OUT_OF_WORLD);
        EntityLootableBody corpse = null;
        for (Entity entity : new ArrayList<Entity>(world.loadedEntityList))
            if (entity instanceof EntityLootableBody && "BuildDeath".equals(((EntityLootableBody)entity).getCustomNameTag())) corpse=(EntityLootableBody)entity;
        require(corpse != null, "actual player death created corpse");
        require(ItemStack.areItemStacksEqual(corpse.getStackInSlot(0),stack(0)),"death helmet");
        require(ItemStack.areItemStacksEqual(corpse.getStackInSlot(4),stack(4)),"death main hand");
        require(ItemStack.areItemStacksEqual(corpse.getStackInSlot(5),stack(5)),"death offhand");
        require(ItemStack.areItemStacksEqual(corpse.getStackInSlot(6),stack(19)),"death inventory custom NBT");
        Object gui = GUIHandler.getInstance().getServerGuiElement(0,player,world,corpse.getEntityId(),0,0);
        require(gui instanceof CorpseContainer,"server inventory GUI");
        CorpseContainer container = (CorpseContainer)gui;
        require(container.inventorySlots.size()==84 && container.canInteractWith(player),"container slots/access");
        ItemStack recovered = container.transferStackInSlot(player,6);
        require(ItemStack.areItemStacksEqual(recovered,stack(19)),"shift-click recovery");
        require((corpse.getStackInSlot(6)).isEmpty(),"no duplicate in corpse");
        corpse.setUniqueId(DEATH); corpse.setNoGravity(true);
        corpse.motionX=0; corpse.motionY=0; corpse.motionZ=0;
        disableVacuum(corpse);
        saveExpected(folder,"death",corpse);
    }
    static EntityLootableBody find(WorldServer world, UUID uuid) {
        for(Entity entity : world.loadedEntityList) if(entity.getUniqueID().equals(uuid)) return (EntityLootableBody)entity;
        throw new AssertionError("Saved corpse missing " + uuid);
    }
    static void disableVacuum(EntityLootableBody body) {
        try {
            java.lang.reflect.Field field = EntityLootableBody.class.getDeclaredField("vacuumTime");
            field.setAccessible(true); field.setByte(body,(byte)20);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    static void saveExpected(File folder,String name,EntityLootableBody body) throws Exception {
        NBTTagCompound data = new NBTTagCompound(); body.writeUnlessPassenger(data);
        CompressedStreamTools.writeCompressed(data,new FileOutputStream(new File(folder,name+".nbt")));
    }
    static void verifyExpected(File folder,String name,EntityLootableBody body) throws Exception {
        NBTTagCompound expected = CompressedStreamTools.readCompressed(new FileInputStream(new File(folder,name+".nbt")));
        NBTTagCompound actual = new NBTTagCompound(); body.writeUnlessPassenger(actual); compare(expected,actual);
    }
    static void compare(NBTTagCompound expected,NBTTagCompound actual) {
        for(String key : Arrays.asList("id","UUIDMost","UUIDLeast","Pos","Rotation","CustomName","Equipment","Aux","DeathTime","DecayTime"))
            require(Objects.equals(expected.getTag(key),actual.getTag(key)),"Saved field differs " + key + ": " + expected.getTag(key) + " != " + actual.getTag(key));
        if(expected.hasKey("Name")) require(expected.getString("Name").equals(actual.getString("Name")),"profile name");
    }
}
