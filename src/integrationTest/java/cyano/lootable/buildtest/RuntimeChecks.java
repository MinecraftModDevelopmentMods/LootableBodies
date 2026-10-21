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
import net.minecraftforge.fml.common.event.*;

/** Build-only verifier. Never included in the production source set or jars. */
@Mod(modid="lootablebodiesbuildtest", name="Lootable Bodies Build Tests", version="1", dependencies="required-after:lootablebodies")
public final class RuntimeChecks {
    static final UUID NEAR = UUID.fromString("00000000-0000-4000-8000-000000000101");
    static final UUID FAR = UUID.fromString("00000000-0000-4000-8000-000000000102");
    static final UUID DEATH = UUID.fromString("00000000-0000-4000-8000-000000000103");
    public static volatile Throwable failure;
    private static boolean defaultsChecked;
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event){MultiplayerChecks.registerChannel();NbtWorldChecks.registerItem();EffectHurtChecks.registerPotion();}
    @Mod.EventHandler public void clientInit(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) {
            new ClientChecks().register();new MultiplayerClientChecks().register();new VisibilityClientChecks().register();new RespawnPacketClientChecks().register();
        }
    }
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
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
    public static void run(MinecraftServer server, String phase) throws Exception {
        WorldServer world = server.worldServerForDimension(0);
        File folder = world.getSaveHandler().getWorldDirectory();
        require(LootableBodies.MODID.equals("lootablebodies") && LootableBodies.VERSION.equals("2.4.1.110021"), "identity/version");
        require(net.minecraftforge.fml.common.Loader.instance().getIndexedModList().get("lootablebodies")
                .getVersion().equals(phase.equals("seed") ? "2.2.0" : phase.equals("issues") ?
                        System.getProperty("lootablebodies.expectedIssueVersion","2.4.1.110021") : "2.4.1.110021"), "loaded release version");
        if (!defaultsChecked) require(LootableBodies.addBonesToCorpse && LootableBodies.displayNameTag && !LootableBodies.hurtByEnvironment &&
                !LootableBodies.hurtByAttacks && !LootableBodies.hurtByMisc && LootableBodies.corpseHP == 20 &&
                LootableBodies.ticksPerItemDecay == 6000 && LootableBodies.corpseDecayTime == 72000 &&
                LootableBodies.allowCorpseDecay && LootableBodies.decayOnlyWhenEmpty, "default configuration");
        defaultsChecked=true;
        // Freeze timers only in this verifier while comparing exact inventories.
        LootableBodies.allowCorpseDecay = false;
        LootableBodies.ticksPerItemDecay = -1;
        if(phase.equals("visibility-reload")) {
            VisibilityChecks.reload(server);
        } else if(phase.equals("issues")) {
            ReportedIssueChecks.run(server,world,folder);
            ReportedIssueChecks.seedPaused();
        } else if(phase.equals("issues-reload")) {
            ReportedIssueChecks.coldReload(server,world,folder);
        } else if(phase.equals("multiplayer-reload")) {
            MultiplayerChecks.verifyReload(server);
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
            if(new File(folder,"issue17-chunks.txt").isFile()) ReportedIssueChecks.coldReload(server,world,folder);
            world.getChunkFromChunkCoords(0, 0);
            EntityLootableBody near = find(world, NEAR);
            verifyExpected(folder, "near", near);
            world.getChunkFromChunkCoords(2, 0);
            verifyExpected(folder, "death", find(world, DEATH));
            if(new File(folder,"rejected-release.nbt").isFile()) {
                world.getChunkFromChunkCoords(3,0);
                EntityLootableBody retained=find(world,UUID.fromString("00000000-0000-4000-8000-000000000105"));
                require(retained.isEntityAlive(),"Blocked-release owner did not survive reload");
                // The saved profile is recreated lazily on the first entity tick.
                retained.onEntityUpdate();
                verifyExpected(folder,"rejected-release",retained);
            }
            if (phase.equals("deferred")) {
                world.getChunkFromChunkCoords(256, 256);
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
    static ItemStack stack(int index) {
        ItemStack stack = index < 4 ? new ItemStack(new net.minecraft.item.Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}[index],1,index+2) :
                index == 4 ? new ItemStack(Items.DIAMOND_SWORD,1,17) : index == 5 ? new ItemStack(Items.SHIELD,1,9) :
                new ItemStack(Items.DYE, 1 + index % 32, index % 16);
        NBTTagCompound custom = new NBTTagCompound();
        custom.setString("fixture", "slot-" + index);
        NBTTagCompound nested = new NBTTagCompound(); nested.setInteger("unchanged", index * 17);
        NBTTagList contents = new NBTTagList();
        NBTTagCompound item = new NBTTagCompound(); new ItemStack(Items.DIAMOND,3).writeToNBT(item); contents.appendTag(item);
        nested.setTag("contents", contents); custom.setTag("nested", nested);
        stack.setTagCompound(custom); stack.setStackDisplayName("Fixture " + index);
        if (index == 4) stack.addEnchantment(net.minecraft.init.Enchantments.UNBREAKING, 2);
        return stack;
    }
    static EntityLootableBody fixture(WorldServer world, UUID uuid, int coordinate, boolean full) {
        world.getChunkFromChunkCoords(coordinate >> 4, coordinate >> 4);
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
        NBTTagCompound before = new NBTTagCompound(); body.writeToNBTOptional(before);
        require(before.getString("id").equals("lootablebodies.Corpse"), "persistent entity name: " + before.getString("id"));
        Entity copy = net.minecraft.entity.EntityList.createEntityFromNBT(before.copy(), world);
        require(copy instanceof EntityLootableBody, "NBT factory registration");
        NBTTagCompound after = new NBTTagCompound(); copy.writeToNBTOptional(after);
        compare(before,after);
        if (body.getUniqueID().equals(NEAR)) {
            require(before.getTagList("Equipment",10).tagCount()==48, "all indexed stacks");
            require(before.getTagList("Aux",10).tagCount()==2, "ordered overflow");
        }
    }
    static void death(MinecraftServer server, WorldServer world, File folder) throws Exception {
        EntityPlayerMP player = new EntityPlayerMP(server, world,
                new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000104"),"BuildDeath"),
                new net.minecraft.server.management.PlayerInteractionManager(world));
        player.connection = new net.minecraft.network.NetHandlerPlayServer(server,
                new net.minecraft.network.NetworkManager(net.minecraft.network.EnumPacketDirection.SERVERBOUND), player);
        player.setPosition(32.5,80,0.5); world.getChunkFromChunkCoords(2,0);
        world.getGameRules().setOrCreateGameRule("keepInventory","false");
        player.inventory.mainInventory[0] = stack(4);
        player.inventory.mainInventory[9] = stack(19);
        player.setItemStackToSlot(EntityEquipmentSlot.HEAD,stack(0));
        player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND,stack(5));
        player.onDeath(DamageSource.outOfWorld);
        EntityLootableBody corpse = null;
        for (Entity entity : new ArrayList<Entity>(world.loadedEntityList))
            if (entity instanceof EntityLootableBody && "BuildDeath".equals(entity.getCustomNameTag())) corpse=(EntityLootableBody)entity;
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
        require(corpse.getStackInSlot(6)==null,"no duplicate in corpse");
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
        NBTTagCompound data = new NBTTagCompound(); body.writeToNBTOptional(data);
        CompressedStreamTools.writeCompressed(data,new FileOutputStream(new File(folder,name+".nbt")));
    }
    static void verifyExpected(File folder,String name,EntityLootableBody body) throws Exception {
        NBTTagCompound expected = CompressedStreamTools.readCompressed(new FileInputStream(new File(folder,name+".nbt")));
        NBTTagCompound actual = new NBTTagCompound(); body.writeToNBTOptional(actual); compare(expected,actual);
    }
    static void compare(NBTTagCompound expected,NBTTagCompound actual) {
        for(String key : Arrays.asList("id","UUIDMost","UUIDLeast","Pos","Rotation","CustomName","Equipment","Aux","DeathTime","DecayTime"))
            require(Objects.equals(expected.getTag(key),actual.getTag(key)),"Saved field differs " + key + ": " + expected.getTag(key) + " != " + actual.getTag(key));
        if(expected.hasKey("Name")) require(expected.getString("Name").equals(actual.getString("Name")),"profile name");
    }
}
