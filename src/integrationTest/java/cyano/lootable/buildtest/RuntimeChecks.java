package cyano.lootable.buildtest;
import net.minecraft.world.biome.Biomes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.network.play.ServerPlayNetHandler;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.nbt.LongArrayNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.CompoundNBT;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import cyano.lootable.graphics.GUIHandler;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
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
    private boolean rejectedFirstCorpseOpen;
    public RuntimeChecks() {
        MultiplayerChecks.registerChannel();
        net.minecraftforge.eventbus.api.IEventBus bus=net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        bus.register(new TestRegistries());FixtureItems.register(bus);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.DistExecutor.runWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,()->()->{
            new ClientChecks().register();new MultiplayerClientChecks().register();new VisibilityClientChecks().register();new RespawnPacketClientChecks().register();
        });
    }
    @SubscribeEvent public void rejectFirstCorpseOpen(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if(Boolean.getBoolean("lootablebodies.rejectFirstCorpseOpen") && !rejectedFirstCorpseOpen
                && !event.getWorld().isRemote && event.getTarget() instanceof EntityLootableBody) {
            rejectedFirstCorpseOpen=true;
            event.setCanceled(true);
            System.out.println("CORPSE_GUI_FIRST_INTERACTION_REJECTED_CONTROL");
        }
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.LOWEST) public void fixtureAdmission(net.minecraftforge.event.entity.EntityJoinWorldEvent event) {
        if(Boolean.getBoolean("lootablebodies.assertFixtureAdmission") && event.getEntity() instanceof EntityLootableBody && !event.getWorld().isRemote && !event.getEntity().addedToChunk) {
            EntityLootableBody body=(EntityLootableBody)event.getEntity();
            require(event.getWorld().getChunkProvider().chunkExists(net.minecraft.util.math.MathHelper.floor(body.posX)>>4,net.minecraft.util.math.MathHelper.floor(body.posZ)>>4),"Manual corpse fixture spawned before chunk admission: "+body.getPosition()+" name="+body.getCustomNameTag());
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
        } finally { server.initiateShutdown(false); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static boolean sameStack(ItemStack first,ItemStack second) {
        return ItemStack.areItemStacksEqual(first==null?ItemStack.EMPTY:first,second==null?ItemStack.EMPTY:second);
    }
    public static void run(MinecraftServer server, String phase) throws Exception {
        ServerWorld world = server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));
        File folder = world.getSaveHandler().getWorldDirectory();
        require(LootableBodies.MODID.equals("lootablebodies") && LootableBodies.VERSION.equals("2.4.5.114041"), "identity/version");
        require(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").get().getModInfo().getVersion().toString().equals(phase.equals("seed") ? "2.4.0" : phase.equals("issues") ?
                        System.getProperty("lootablebodies.expectedIssueVersion","2.4.5.114041") : "2.4.5.114041"), "loaded release version");
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
        server.save(false,true,false);
        File marker = new File(folder, phase + ".pass");
        try (FileWriter output = new FileWriter(marker)) { output.write("corpse-inventory-preserved\n"); }
        System.out.println("LOOTABLE_BODIES_RUNTIME_PASS phase=" + phase);
    }
    static net.minecraft.world.chunk.Chunk emptyChunk(ServerWorld world,int x,int z) {
        net.minecraft.world.biome.Biome[] biomes=new net.minecraft.world.biome.Biome[256];java.util.Arrays.fill(biomes,net.minecraft.world.biome.Biomes.PLAINS);return new net.minecraft.world.chunk.Chunk(world,new net.minecraft.util.math.ChunkPos(x,z),biomes);
    }
    static ItemStack stack(int index) {
        ItemStack stack=index<4?new ItemStack(new net.minecraft.item.Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}[index]):index==4?new ItemStack(Items.DIAMOND_SWORD):index==5?new ItemStack(Items.SHIELD):new ItemStack(Items.LAPIS_LAZULI,1+index%32);
        CompoundNBT custom = new CompoundNBT();
        custom.putString("fixture", "slot-" + index);
        custom.put("typed_longs",new LongArrayNBT(new long[]{Long.MIN_VALUE,index,Long.MAX_VALUE}));
        CompoundNBT nested = new CompoundNBT(); nested.putInt("unchanged", index * 17);
        ListNBT contents = new ListNBT();
        CompoundNBT item = new CompoundNBT(); new ItemStack(Items.DIAMOND,3).write(item); contents.add(item);
        nested.put("contents", contents); custom.put("nested", nested);
        stack.setTag(custom);if(index<=5)stack.setDamage(index<4?index+2:index==4?17:9); stack.setDisplayName(new net.minecraft.util.text.StringTextComponent("Fixture " + index));
        if (index == 4) stack.addEnchantment(net.minecraft.enchantment.Enchantments.UNBREAKING, 2);
        return stack;
    }
    static net.minecraft.world.chunk.Chunk awaitChunk(ServerWorld world,int x,int z) {
        net.minecraft.world.chunk.Chunk admitted=(net.minecraft.world.chunk.Chunk)world.getChunkProvider().getChunk(x,z,net.minecraft.world.chunk.ChunkStatus.FULL,true);
        require(world.getChunkProvider().chunkExists(x,z),"Fixture must await native chunk admission before spawning a corpse");
        return admitted;
    }
    static EntityLootableBody fixture(ServerWorld world, UUID uuid, int coordinate, boolean full) {
        awaitChunk(world,coordinate >> 4,coordinate >> 4);
        EntityLootableBody body = new EntityLootableBody(world);
        body.setUniqueId(uuid); body.setPosition(coordinate+0.5, 80, coordinate+0.5);
        body.setNoGravity(true); body.setUserName("BuildFixture"); body.setRotation(37);
        require(body.getSizeInventory() == 48, "48 inventory slots");
        for (int i=0; i<48; i++) if (full || i % 7 == 0 || i == 47) body.setInventorySlotContents(i,stack(i));
        if (full) { body.addItem(stack(70)); body.addItem(stack(71)); }
        disableVacuum(body);
        require(world.addEntity(body), "fixture spawn");
        return body;
    }
    static void verifyRoundTrip(ServerWorld world, EntityLootableBody body) {
        CompoundNBT before = new CompoundNBT(); body.writeUnlessPassenger(before);
        require(before.getString("id").equals("lootablebodies:corpse"), "persistent entity name: " + before.getString("id"));
        Entity copy = FixtureApi.loadEntity(before.copy(), world);
        require(copy instanceof EntityLootableBody, "NBT factory registration");
        CompoundNBT after = new CompoundNBT(); copy.writeUnlessPassenger(after);
        compare(before,after);
        if (body.getUniqueID().equals(NEAR)) {
            require(before.getList("Equipment",10).size()==48, "all indexed stacks");
            require(before.getList("Aux",10).size()==2, "ordered overflow");
        }
    }
    static void death(MinecraftServer server, ServerWorld world, File folder) throws Exception {
        ServerPlayerEntity player = new ServerPlayerEntity(server, world,
                new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000104"),"BuildDeath"),
                new net.minecraft.server.management.PlayerInteractionManager(world));
        player.connection = new net.minecraft.network.play.ServerPlayNetHandler(server,
                new net.minecraft.network.NetworkManager(net.minecraft.network.PacketDirection.SERVERBOUND), player);
        player.setPosition(32.5,80,0.5); awaitChunk(world,2,0); require(world.getChunkProvider().chunkExists(2,0),"Death fixture must await native chunk admission before spawning its corpse");
        world.getGameRules().get(net.minecraft.world.GameRules.KEEP_INVENTORY).set(false,null);
        player.inventory.mainInventory.set(0,stack(4));
        player.inventory.mainInventory.set(9,stack(19));
        player.setItemStackToSlot(EquipmentSlotType.HEAD,stack(0));
        player.setItemStackToSlot(EquipmentSlotType.OFFHAND,stack(5));
        player.onDeath(DamageSource.OUT_OF_WORLD);
        EntityLootableBody corpse = null;
        for (Entity entity : new ArrayList<Entity>(FixtureApi.entities(world)))
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
        corpse.setMotion(0,corpse.getMotion().y,corpse.getMotion().z); corpse.setMotion(corpse.getMotion().x,0,corpse.getMotion().z); corpse.setMotion(corpse.getMotion().x,corpse.getMotion().y,0);
        disableVacuum(corpse);
        saveExpected(folder,"death",corpse);
    }
    static EntityLootableBody find(ServerWorld world, UUID uuid) {
        for(Entity entity : FixtureApi.entities(world)) if(entity.getUniqueID().equals(uuid)) return (EntityLootableBody)entity;
        throw new AssertionError("Saved corpse missing " + uuid);
    }
    static void disableVacuum(EntityLootableBody body) {
        try {
            java.lang.reflect.Field field = EntityLootableBody.class.getDeclaredField("vacuumTime");
            field.setAccessible(true); field.setByte(body,(byte)20);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    static void saveExpected(File folder,String name,EntityLootableBody body) throws Exception {
        CompoundNBT data = new CompoundNBT(); body.writeUnlessPassenger(data);
        CompressedStreamTools.writeCompressed(data,new FileOutputStream(new File(folder,name+".nbt")));
    }
    static void verifyExpected(File folder,String name,EntityLootableBody body) throws Exception {
        CompoundNBT expected = CompressedStreamTools.readCompressed(new FileInputStream(new File(folder,name+".nbt")));
        CompoundNBT actual = new CompoundNBT(); body.writeUnlessPassenger(actual); compare(expected,actual);
    }
    static void compare(CompoundNBT expected,CompoundNBT actual) {
        for(String key : Arrays.asList("id","UUIDMost","UUIDLeast","Pos","Rotation","CustomName","Equipment","Aux","DeathTime","DecayTime"))
            require(Objects.equals(expected.get(key),actual.get(key)),"Saved field differs " + key + ": " + expected.get(key) + " != " + actual.get(key));
        if(expected.contains("Name")) require(expected.getString("Name").equals(actual.getString("Name")),"profile name");
    }
}
