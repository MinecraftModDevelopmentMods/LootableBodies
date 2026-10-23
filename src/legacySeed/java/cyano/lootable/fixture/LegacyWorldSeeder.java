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
import net.minecraftforge.fml.common.event.*;

/** Uses the installed historical mod's own writer; never part of a release jar. */
@Mod(modid="lootablebodiesbuildtest",name="Lootable Bodies World Fixtures",version="1",dependencies="required-after:lootablebodies")
public final class LegacyWorldSeeder {
    private static final UUID PLAYER=UUID.fromString("00000000-2222-4222-8222-000000000001");
    private static boolean oldMinecraft,native112;
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event){FixtureItems.register();}
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        MinecraftServer server=FMLCommonHandler.instance().getMinecraftServerInstance();
        try {seed(server);} catch(Throwable error) {error.printStackTrace();} finally {server.initiateShutdown();}
    }
    private static void seed(MinecraftServer server) throws Exception {
        String minecraft=server.getMinecraftVersion(),version=Loader.instance().getIndexedModList().get("lootablebodies").getVersion();
        oldMinecraft=minecraft.equals("1.10.2");native112=minecraft.equals("1.12.2");
        if(!oldMinecraft && !minecraft.equals("1.11.2") && !minecraft.equals("1.12.2")) throw new AssertionError("Wrong fixture Minecraft "+minecraft);
        if(!version.equals(System.getProperty("lootablebodies.fixture.version"))) throw new AssertionError("Wrong historical mod "+version);
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        NBTTagCompound manifest=new NBTTagCompound();manifest.setString("Minecraft",minecraft);manifest.setString("ModVersion",version);
        NBTTagList bodies=new NBTTagList(),containers=new NBTTagList(),vanilla=new NBTTagList();
        for(int dimension:new int[]{0,-1,1}) {
            WorldServer world=server.getWorld(dimension);
            for(int wave=0;wave<3;wave++) {
                int coordinate=wave==0?128:wave==1?4096:8192;
                world.getChunk(coordinate>>4,coordinate>>4);
                EntityLootableBody body=new EntityLootableBody(world);
                body.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/"+version+"/"+dimension+"/"+wave).getBytes("UTF-8")));
                body.setPosition(coordinate+.5,80,coordinate+.5);body.setNoGravity(true);body.setUserName("SavedFixtureOwner");body.setRotation(37);
                for(int slot=0;slot<48;slot++)body.setInventorySlotContents(slot,item(slot));
                for(int slot=70;slot<73;slot++)body.addItem(item(slot));
                if(Boolean.getBoolean("lootablebodies.fixture.recovery")) {
                    if(!version.equals("2.4.1.110021") && !version.equals("2.4.2.111021") && !version.equals("2.4.3.112021")) throw new AssertionError("Recovery fixtures require a checksum-pinned maintained writer");
                    NBTTagCompound damaged=new NBTTagCompound();body.writeToNBTOptional(damaged);
                    NBTTagList equipment=damaged.getTagList("Equipment",10);
                    equipment.getCompoundTagAt(20).setString("id","lootablebodiesbuildtest:returning_item");
                    NBTTagCompound duplicate=equipment.getCompoundTagAt(21).copy();duplicate.setByte("Count",(byte)9);duplicate.getCompoundTag("tag").setString("extension","duplicate slot");equipment.appendTag(duplicate);
                    NBTTagCompound invalid=equipment.getCompoundTagAt(22).copy();invalid.setInteger("Slot",999);invalid.setByte("Count",(byte)7);invalid.getCompoundTag("tag").setString("extension","invalid slot");equipment.appendTag(invalid);
                    damaged.setInteger("Name",17);damaged.setDouble("DeathTime",Double.NaN);damaged.setString("Health","opaque health");
                    damaged.setString("ActiveEffects","opaque effects");damaged.setString("HurtTime","opaque hurt timer");damaged.setDouble("FallDistance",Double.POSITIVE_INFINITY);
                    body.readFromNBT(damaged);body.setPosition(coordinate+.5,80,coordinate+.5);
                }
                java.lang.reflect.Field vacuum=EntityLootableBody.class.getDeclaredField("vacuumTime");vacuum.setAccessible(true);vacuum.setByte(body,(byte)20);
                if(!world.spawnEntity(body))throw new AssertionError("Historical fixture corpse spawn");
                NBTTagCompound record=new NBTTagCompound(),payload=new NBTTagCompound();
                if(!body.writeToNBTOptional(payload))throw new AssertionError("Historical corpse writer");
                record.setInteger("Dimension",dimension);record.setInteger("Wave",wave);record.setTag("Body",payload);bodies.appendTag(record);
                BlockPos chestPos=new BlockPos(coordinate+2,80,coordinate+2);world.setBlockState(chestPos,Blocks.CHEST.getDefaultState());
                TileEntityChest chest=(TileEntityChest)world.getTileEntity(chestPos);for(int slot=0;slot<16;slot++)chest.setInventorySlotContents(slot,item(slot+6));
                NBTTagCompound chestRecord=new NBTTagCompound(),chestNbt=new NBTTagCompound();
                chest.writeToNBT(chestNbt);
                chestRecord.setInteger("Dimension",dimension);chestRecord.setInteger("Wave",wave);chestRecord.setTag("Tile",chestNbt);containers.appendTag(chestRecord);
                if(wave==0) {
                    EntityPig pig=new EntityPig(world);pig.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/pig/"+dimension).getBytes("UTF-8")));pig.setPosition(coordinate+4,82,coordinate+4);pig.setNoGravity(true);pig.setNoAI(true);pig.setCustomNameTag("Saved pig");world.spawnEntity(pig);
                    NBTTagCompound p=new NBTTagCompound();pig.writeToNBTOptional(p);NBTTagCompound v=new NBTTagCompound();v.setInteger("Dimension",dimension);v.setString("ExpectedType","minecraft:pig");v.setTag("Entity",p);vanilla.appendTag(v);
                    EntityArmorStand stand=new EntityArmorStand(world);stand.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/stand/"+dimension).getBytes("UTF-8")));stand.setPosition(coordinate+5,82,coordinate+5);stand.setNoGravity(true);stand.setItemStackToSlot(EntityEquipmentSlot.HEAD,item(0));world.spawnEntity(stand);
                    p=new NBTTagCompound();stand.writeToNBTOptional(p);v=new NBTTagCompound();v.setInteger("Dimension",dimension);v.setString("ExpectedType","minecraft:armor_stand");v.setTag("Entity",p);vanilla.appendTag(v);
                    EntityMinecartChest cart=new EntityMinecartChest(world,coordinate+6,82,coordinate+6);cart.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/cart/"+dimension).getBytes("UTF-8")));cart.setNoGravity(true);for(int slot=0;slot<8;slot++)cart.setInventorySlotContents(slot,item(slot+6));world.spawnEntity(cart);
                    p=new NBTTagCompound();cart.writeToNBTOptional(p);v=new NBTTagCompound();v.setInteger("Dimension",dimension);v.setString("ExpectedType","minecraft:chest_minecart");v.setTag("Entity",p);vanilla.appendTag(v);
                }
            }
        }
        WorldServer world=server.getWorld(0);
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
    private static ItemStack item(int slot) {
        ItemStack stack;
        if(slot<4)stack=new ItemStack(new Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}[slot],1,slot+2);
        else if(slot==4)stack=new ItemStack(Items.DIAMOND_SWORD,1,17);
        else if(slot==5)stack=new ItemStack(Items.SHIELD,1,9);
        else if(slot==6)stack=new ItemStack(Items.SPAWN_EGG,3);
        else if(slot==7)stack=new ItemStack(Item.getItemFromBlock(Blocks.CHEST),2);
        else if(slot==8)stack=new ItemStack(Items.BANNER,2,oldMinecraft?0:5);
        else if(slot==9)stack=new ItemStack(FixtureItems.keepsake,11);
        else if(slot==10)stack=new ItemStack(Items.BED,1,0);
        else if(slot==11 && native112)stack=new ItemStack(Items.BED,1,5);
        else stack=new ItemStack(Items.DIAMOND,slot%32+1);
        NBTTagCompound tag=new NBTTagCompound();tag.setString("fixture_slot","original-"+slot);tag.setLong("typed_long",1234567890123L);tag.setByteArray("typed_bytes",new byte[]{0,-1,17});
        if(slot==6){NBTTagCompound pig=new NBTTagCompound();pig.setString("id",oldMinecraft?"Pig":"minecraft:pig");pig.setString("CustomName","Saved pig egg");tag.setTag("EntityTag",pig);}
        if(slot==7){NBTTagCompound tile=new NBTTagCompound();NBTTagList items=new NBTTagList();NBTTagCompound nested=new ItemStack(Items.EMERALD,17).writeToNBT(new NBTTagCompound());nested.setByte("Slot",(byte)3);items.appendTag(nested);tile.setTag("Items",items);tag.setTag("BlockEntityTag",tile);}
        if(slot==8 && oldMinecraft){NBTTagCompound banner=new NBTTagCompound();banner.setInteger("Base",5);tag.setTag("BlockEntityTag",banner);}
        if(native112) {
            try {tag.setTag("typed_longs",(NBTBase)Class.forName("net.minecraft.nbt.NBTTagLongArray").getConstructor(long[].class).newInstance((Object)new long[]{Long.MIN_VALUE,slot,Long.MAX_VALUE}));}
            catch(ReflectiveOperationException failure){throw new IllegalStateException("Native 1.12 long-array fixture",failure);}
        }
        stack.setTagCompound(tag);stack.setStackDisplayName("Original keepsake "+slot);if(slot==4)stack.addEnchantment(Enchantments.UNBREAKING,2);return stack;
    }
}
