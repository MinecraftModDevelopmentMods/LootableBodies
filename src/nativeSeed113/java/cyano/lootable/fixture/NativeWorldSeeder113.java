package cyano.lootable.fixture;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.buildtest.FixtureItems;
import java.io.*;
import java.util.*;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Uses the installed historical mod's own writer; never part of a release jar. */
@Mod("lootablebodiesbuildtest")
public final class NativeWorldSeeder113 {
    private static final UUID PLAYER=UUID.fromString("00000000-2222-4222-8222-000000000001");
    private static boolean oldMinecraft,native112;
    public NativeWorldSeeder113(){FixtureItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void started(FMLServerStartedEvent event) {
        MinecraftServer server=net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
        try {seed(server);} catch(Throwable error) {error.printStackTrace();} finally {server.initiateShutdown();}
    }
    private static void seed(MinecraftServer server) throws Exception {
        String minecraft=server.getMinecraftVersion(),version=net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").get().getModInfo().getVersion().toString();
        oldMinecraft=minecraft.equals("1.10.2");native112=minecraft.equals("1.12.2");
        if(!minecraft.equals("1.13.2") || !version.equals("2.4.4.113021")) throw new AssertionError("Wrong fixture Minecraft "+minecraft);
        if(!version.equals(System.getProperty("lootablebodies.fixture.version"))) throw new AssertionError("Wrong historical mod "+version);
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        NBTTagCompound manifest=new NBTTagCompound();manifest.setString("Minecraft",minecraft);manifest.setString("ModVersion",version);
        NBTTagList bodies=new NBTTagList(),containers=new NBTTagList(),vanilla=new NBTTagList();
        for(int dimension:new int[]{0,-1,1}) {
            WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(dimension));
            for(int wave=0;wave<3;wave++) {
                int coordinate=wave==0?128:wave==1?4096:8192;
                awaitChunk(world,coordinate>>4,coordinate>>4);
                EntityLootableBody body=new EntityLootableBody(world);
                body.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/"+version+"/"+dimension+"/"+wave).getBytes("UTF-8")));
                body.setPosition(coordinate+.5,80,coordinate+.5);body.setNoGravity(true);body.setUserName("SavedFixtureOwner");body.setRotation(37);
                for(int slot=0;slot<48;slot++)body.setInventorySlotContents(slot,item(slot));
                for(int slot=70;slot<73;slot++)body.addItem(item(slot));
                if(Boolean.getBoolean("lootablebodies.fixture.recovery")) {
                    if(!version.equals("2.4.1.110021") && !version.equals("2.4.2.111021") && !version.equals("2.4.3.112021") && !version.equals("2.4.4.113021")) throw new AssertionError("Recovery fixtures require a checksum-pinned maintained writer");
                    NBTTagCompound damaged=new NBTTagCompound();body.writeUnlessPassenger(damaged);
                    NBTTagList equipment=damaged.getList("Equipment",10);
                    equipment.getCompound(20).setString("id","lootablebodiesbuildtest:returning_item");
                    NBTTagCompound duplicate=equipment.getCompound(21).copy();duplicate.setByte("Count",(byte)9);duplicate.getCompound("tag").setString("extension","duplicate slot");equipment.add(duplicate);
                    NBTTagCompound invalid=equipment.getCompound(22).copy();invalid.setInt("Slot",999);invalid.setByte("Count",(byte)7);invalid.getCompound("tag").setString("extension","invalid slot");equipment.add(invalid);
                    damaged.setInt("Name",17);damaged.setDouble("DeathTime",Double.NaN);damaged.setString("Health","opaque health");
                    damaged.setString("ActiveEffects","opaque effects");damaged.setString("HurtTime","opaque hurt timer");damaged.setDouble("FallDistance",Double.POSITIVE_INFINITY);
                    body.read(damaged);body.setPosition(coordinate+.5,80,coordinate+.5);
                }
                java.lang.reflect.Field vacuum=EntityLootableBody.class.getDeclaredField("vacuumTime");vacuum.setAccessible(true);vacuum.setByte(body,(byte)20);
                if(!world.spawnEntity(body))throw new AssertionError("Historical fixture corpse spawn");
                NBTTagCompound record=new NBTTagCompound(),payload=new NBTTagCompound();
                if(!body.writeUnlessPassenger(payload))throw new AssertionError("Historical corpse writer");
                record.setInt("Dimension",dimension);record.setInt("Wave",wave);record.setTag("Body",payload);bodies.add(record);
                BlockPos chestPos=new BlockPos(coordinate+2,80,coordinate+2);world.setBlockState(chestPos,Blocks.CHEST.getDefaultState());
                TileEntityChest chest=(TileEntityChest)world.getTileEntity(chestPos);for(int slot=0;slot<16;slot++)chest.setInventorySlotContents(slot,item(slot+6));
                NBTTagCompound chestRecord=new NBTTagCompound(),chestNbt=new NBTTagCompound();
                chest.write(chestNbt);
                chestRecord.setInt("Dimension",dimension);chestRecord.setInt("Wave",wave);chestRecord.setTag("Tile",chestNbt);containers.add(chestRecord);
                if(wave==0) {
                    for(int profession=0;profession<6;profession++) {
                        net.minecraft.entity.passive.EntityVillager villager=new net.minecraft.entity.passive.EntityVillager(world);
                        villager.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/villager/"+dimension+"/"+profession).getBytes("UTF-8")));
                        villager.setPosition(coordinate+8+profession,82,coordinate+8);villager.setNoGravity(true);villager.setNoAI(true);villager.setProfession(profession);
                        NBTTagCompound data=new NBTTagCompound();villager.writeUnlessPassenger(data);data.setInt("Career",1);data.setInt("CareerLevel",3);
                        NBTTagList inventory=new NBTTagList();NBTTagCompound carried=item(7).write(new NBTTagCompound());inventory.add(carried);data.setTag("Inventory",inventory);villager.read(data);
                        if(!world.spawnEntity(villager))throw new AssertionError("Historical villager spawn");
                        NBTTagCompound recordVillager=new NBTTagCompound(),savedVillager=new NBTTagCompound();villager.writeUnlessPassenger(savedVillager);
                        recordVillager.setInt("Dimension",dimension);recordVillager.setString("ExpectedType","minecraft:villager");recordVillager.setTag("Entity",savedVillager);vanilla.add(recordVillager);
                    }
                    EntityPig pig=new EntityPig(world);pig.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/pig/"+dimension).getBytes("UTF-8")));pig.setPosition(coordinate+4,82,coordinate+4);pig.setNoGravity(true);pig.setNoAI(true);pig.setCustomName(new net.minecraft.util.text.TextComponentString("Saved pig"));world.spawnEntity(pig);
                    NBTTagCompound p=new NBTTagCompound();pig.writeUnlessPassenger(p);NBTTagCompound v=new NBTTagCompound();v.setInt("Dimension",dimension);v.setString("ExpectedType","minecraft:pig");v.setTag("Entity",p);vanilla.add(v);
                    EntityArmorStand stand=new EntityArmorStand(world);stand.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/stand/"+dimension).getBytes("UTF-8")));stand.setPosition(coordinate+5,82,coordinate+5);stand.setNoGravity(true);stand.setItemStackToSlot(EntityEquipmentSlot.HEAD,item(0));world.spawnEntity(stand);
                    p=new NBTTagCompound();stand.writeUnlessPassenger(p);v=new NBTTagCompound();v.setInt("Dimension",dimension);v.setString("ExpectedType","minecraft:armor_stand");v.setTag("Entity",p);vanilla.add(v);
                    EntityMinecartChest cart=new EntityMinecartChest(world,coordinate+6,82,coordinate+6);cart.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/cart/"+dimension).getBytes("UTF-8")));cart.setNoGravity(true);for(int slot=0;slot<8;slot++)cart.setInventorySlotContents(slot,item(slot+6));world.spawnEntity(cart);
                    p=new NBTTagCompound();cart.writeUnlessPassenger(p);v=new NBTTagCompound();v.setInt("Dimension",dimension);v.setString("ExpectedType","minecraft:chest_minecart");v.setTag("Entity",p);vanilla.add(v);
                }
            }
        }
        WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));
        EntityPlayerMP player=new EntityPlayerMP(server,world,new GameProfile(PLAYER,"UpgradeFixture"),new PlayerInteractionManager(world));player.setPosition(136.5,82,136.5);
        for(int slot=0;slot<36;slot++)player.inventory.setInventorySlotContents(slot,item(slot+6));
        for(int slot=0;slot<4;slot++)player.setItemStackToSlot(new EntityEquipmentSlot[]{EntityEquipmentSlot.HEAD,EntityEquipmentSlot.CHEST,EntityEquipmentSlot.LEGS,EntityEquipmentSlot.FEET}[slot],item(slot));
        player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND,item(5));for(int slot=0;slot<27;slot++)player.getInventoryEnderChest().setInventorySlotContents(slot,item(slot+6));
        world.getSaveHandler().getPlayerNBTManager().writePlayerData(player);
        manifest.setUniqueId("Player",PLAYER);manifest.setTag("Bodies",bodies);manifest.setTag("Containers",containers);manifest.setTag("Vanilla",vanilla);
        server.saveAllWorlds(false);net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        File folder=world.getSaveHandler().getWorldDirectory();try(FileOutputStream file=new FileOutputStream(new File(folder,"upgrade-fixtures.nbt"))){CompressedStreamTools.writeCompressed(manifest,file);}
        try(FileWriter marker=new FileWriter(new File(folder,"legacy-seed.pass"))){marker.write(minecraft+" "+version+"\n");}
        System.out.println("LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft="+minecraft+" mod="+version+" corpses=9 dimensions=3");
    }
    private static boolean minecraftLongArrays(){return true;}
    private static void awaitChunk(WorldServer world,int x,int z)throws Exception {
        net.minecraft.world.chunk.Chunk chunk=world.getChunk(x,z);
        for(int attempt=0;!chunk.isLoaded();attempt++) {
            if(attempt>10000)throw new AssertionError("Historical native chunk admission timed out");
            java.util.concurrent.FutureTask<?> task=world.getServer().futureTaskQueue.poll();
            if(task==null)throw new AssertionError("Missing historical admission task");task.run();task.get();
        }
    }
    private static ItemStack item(int slot) {
        ItemStack stack;
        if(slot<4)stack=new ItemStack(new Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}[slot]);
        else if(slot==4)stack=new ItemStack(Items.DIAMOND_SWORD);
        else if(slot==5)stack=new ItemStack(Items.SHIELD);
        else if(slot==6)stack=new ItemStack(Items.PIG_SPAWN_EGG,3);
        else if(slot==7)stack=new ItemStack(Blocks.CHEST,2);
        else if(slot==8)stack=new ItemStack(Items.LIME_BANNER,2);
        else if(slot==9)stack=new ItemStack(FixtureItems.keepsake,11);
        else if(slot==10)stack=new ItemStack(Items.WHITE_BED,1);
        else if(slot==11)stack=new ItemStack(Items.LIME_BED);
        else if(slot==12)stack=new ItemStack(Items.SIGN,11);
        else stack=new ItemStack(Items.DIAMOND,slot%32+1);
        NBTTagCompound tag=new NBTTagCompound();tag.setString("fixture_slot","original-"+slot);tag.setLong("typed_long",1234567890123L);tag.setByteArray("typed_bytes",new byte[]{0,-1,17});
        if(slot==6){NBTTagCompound pig=new NBTTagCompound();pig.setString("id",oldMinecraft?"Pig":"minecraft:pig");pig.setString("CustomName","Saved pig egg");tag.setTag("EntityTag",pig);}
        if(slot==7){NBTTagCompound tile=new NBTTagCompound();NBTTagList items=new NBTTagList();NBTTagCompound nested=new ItemStack(Items.EMERALD,17).write(new NBTTagCompound());nested.setByte("Slot",(byte)3);items.add(nested);tile.setTag("Items",items);tag.setTag("BlockEntityTag",tile);}
        if(slot==8 && oldMinecraft){NBTTagCompound banner=new NBTTagCompound();banner.setInt("Base",5);tag.setTag("BlockEntityTag",banner);}
        if(minecraftLongArrays()) {
            tag.setTag("typed_longs",new NBTTagLongArray(new long[]{Long.MIN_VALUE,slot,Long.MAX_VALUE}));
        }
        stack.setTag(tag);if(slot<6)stack.setDamage(slot<4?slot+2:slot==4?17:9);stack.setDisplayName(new net.minecraft.util.text.TextComponentString("Original keepsake "+slot));if(slot==4)stack.addEnchantment(Enchantments.UNBREAKING,2);return stack;
    }
}
