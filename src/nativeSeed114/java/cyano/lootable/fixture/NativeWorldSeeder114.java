package cyano.lootable.fixture;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.buildtest.FixtureItems;
import java.io.*;
import java.util.*;
import net.minecraft.entity.item.ArmorStandEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.item.minecart.ChestMinecartEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.tileentity.ChestTileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.event.server.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Uses the installed historical mod's own writer; never part of a release jar. */
@Mod("lootablebodiesbuildtest")
public final class NativeWorldSeeder114 {
    private static final UUID PLAYER=UUID.fromString("00000000-2222-4222-8222-000000000001");
    private static boolean oldMinecraft,native112;
    public NativeWorldSeeder114(){FixtureItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void started(FMLServerStartedEvent event) {
        MinecraftServer server=net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
        try {seed(server);} catch(Throwable error) {error.printStackTrace();} finally {server.initiateShutdown(false);}
    }
    private static void seed(MinecraftServer server) throws Exception {
        String minecraft=server.getMinecraftVersion(),version=net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").get().getModInfo().getVersion().toString();
        oldMinecraft=minecraft.equals("1.10.2");native112=minecraft.equals("1.12.2");
        if(!minecraft.equals("1.14.4") || !version.equals("2.4.5.114041")) throw new AssertionError("Wrong fixture Minecraft "+minecraft);
        if(!version.equals(System.getProperty("lootablebodies.fixture.version"))) throw new AssertionError("Wrong historical mod "+version);
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        CompoundNBT manifest=new CompoundNBT();manifest.putString("Minecraft",minecraft);manifest.putString("ModVersion",version);
        ListNBT bodies=new ListNBT(),containers=new ListNBT(),vanilla=new ListNBT(),biomes=new ListNBT();
        for(int dimension:new int[]{0,-1,1}) {
            ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(dimension));
            for(int wave=0;wave<3;wave++) {
                int coordinate=wave==0?128:wave==1?4096:8192;
                awaitChunk(world,coordinate>>4,coordinate>>4);
                net.minecraft.world.biome.Biome[] sourceBiomes=world.getChunk(coordinate>>4,coordinate>>4).getBiomes();
                net.minecraft.world.biome.Biome[] choices={net.minecraft.world.biome.Biomes.PLAINS,net.minecraft.world.biome.Biomes.DESERT,net.minecraft.world.biome.Biomes.FOREST,net.minecraft.world.biome.Biomes.TAIGA};
                int[] savedBiomes=new int[sourceBiomes.length];
                for(int b=0;b<sourceBiomes.length;b++){sourceBiomes[b]=choices[(b/4+b%7)%choices.length];savedBiomes[b]=net.minecraft.util.registry.Registry.BIOME.getId(sourceBiomes[b]);}
                CompoundNBT biomeRecord=new CompoundNBT();biomeRecord.putInt("Dimension",dimension);biomeRecord.putInt("Wave",wave);biomeRecord.putInt("ChunkX",coordinate>>4);biomeRecord.putInt("ChunkZ",coordinate>>4);biomeRecord.putIntArray("Data",savedBiomes);biomes.add(biomeRecord);
                EntityLootableBody body=new EntityLootableBody(world);
                body.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/"+version+"/"+dimension+"/"+wave).getBytes("UTF-8")));
                body.setPosition(coordinate+.5,80,coordinate+.5);body.setNoGravity(true);body.setUserName("SavedFixtureOwner");body.setRotation(37);
                for(int slot=0;slot<48;slot++)body.setInventorySlotContents(slot,item(slot));
                for(int slot=70;slot<73;slot++)body.addItem(item(slot));
                if(Boolean.getBoolean("lootablebodies.fixture.recovery")) {
                    if(!version.equals("2.4.1.110021") && !version.equals("2.4.2.111021") && !version.equals("2.4.3.112021") && !version.equals("2.4.5.114041")) throw new AssertionError("Recovery fixtures require a checksum-pinned maintained writer");
                    CompoundNBT damaged=new CompoundNBT();body.writeUnlessPassenger(damaged);
                    ListNBT equipment=damaged.getList("Equipment",10);
                    equipment.getCompound(20).putString("id","lootablebodiesbuildtest:returning_item");
                    CompoundNBT duplicate=equipment.getCompound(21).copy();duplicate.putByte("Count",(byte)9);duplicate.getCompound("tag").putString("extension","duplicate slot");equipment.add(duplicate);
                    CompoundNBT invalid=equipment.getCompound(22).copy();invalid.putInt("Slot",999);invalid.putByte("Count",(byte)7);invalid.getCompound("tag").putString("extension","invalid slot");equipment.add(invalid);
                    damaged.putInt("Name",17);damaged.putDouble("DeathTime",Double.NaN);damaged.putString("Health","opaque health");
                    damaged.putString("ActiveEffects","opaque effects");damaged.putString("HurtTime","opaque hurt timer");damaged.putDouble("FallDistance",Double.POSITIVE_INFINITY);
                    body.read(damaged);body.setPosition(coordinate+.5,80,coordinate+.5);
                }
                java.lang.reflect.Field vacuum=EntityLootableBody.class.getDeclaredField("vacuumTime");vacuum.setAccessible(true);vacuum.setByte(body,(byte)20);
                if(!world.addEntity(body))throw new AssertionError("Historical fixture corpse spawn");
                CompoundNBT record=new CompoundNBT(),payload=new CompoundNBT();
                if(!body.writeUnlessPassenger(payload))throw new AssertionError("Historical corpse writer");
                record.putInt("Dimension",dimension);record.putInt("Wave",wave);record.put("Body",payload);bodies.add(record);
                BlockPos chestPos=new BlockPos(coordinate+2,80,coordinate+2);world.setBlockState(chestPos,Blocks.CHEST.getDefaultState());
                ChestTileEntity chest=(ChestTileEntity)world.getTileEntity(chestPos);for(int slot=0;slot<16;slot++)chest.setInventorySlotContents(slot,item(slot+6));
                CompoundNBT chestRecord=new CompoundNBT(),chestNbt=new CompoundNBT();
                chest.write(chestNbt);
                chestRecord.putInt("Dimension",dimension);chestRecord.putInt("Wave",wave);chestRecord.put("Tile",chestNbt);containers.add(chestRecord);
                if(wave==0) {
                    for(int profession=0;profession<6;profession++) {
                        net.minecraft.entity.merchant.villager.VillagerEntity villager=new net.minecraft.entity.merchant.villager.VillagerEntity(EntityType.VILLAGER,world);
                        villager.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/villager/"+dimension+"/"+profession).getBytes("UTF-8")));
                        villager.setPosition(coordinate+8+profession,82,coordinate+8);villager.setNoGravity(true);villager.setNoAI(true);villager.setVillagerData(villager.getVillagerData().withProfession(new net.minecraft.entity.merchant.villager.VillagerProfession[]{net.minecraft.entity.merchant.villager.VillagerProfession.FARMER,net.minecraft.entity.merchant.villager.VillagerProfession.LIBRARIAN,net.minecraft.entity.merchant.villager.VillagerProfession.CLERIC,net.minecraft.entity.merchant.villager.VillagerProfession.ARMORER,net.minecraft.entity.merchant.villager.VillagerProfession.BUTCHER,net.minecraft.entity.merchant.villager.VillagerProfession.NITWIT}[profession]).withLevel(3));
                        CompoundNBT data=new CompoundNBT();villager.writeUnlessPassenger(data);
                        ListNBT inventory=new ListNBT();CompoundNBT carried=item(7).write(new CompoundNBT());inventory.add(carried);data.put("Inventory",inventory);villager.read(data);
                        if(!world.addEntity(villager))throw new AssertionError("Historical villager spawn");
                        CompoundNBT recordVillager=new CompoundNBT(),savedVillager=new CompoundNBT();villager.writeUnlessPassenger(savedVillager);
                        recordVillager.putInt("Dimension",dimension);recordVillager.putString("ExpectedType","minecraft:villager");recordVillager.put("Entity",savedVillager);vanilla.add(recordVillager);
                    }
                    PigEntity pig=new PigEntity(EntityType.PIG,world);pig.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/pig/"+dimension).getBytes("UTF-8")));pig.setPosition(coordinate+4,82,coordinate+4);pig.setNoGravity(true);pig.setNoAI(true);pig.setCustomName(new net.minecraft.util.text.StringTextComponent("Saved pig"));world.addEntity(pig);
                    CompoundNBT p=new CompoundNBT();pig.writeUnlessPassenger(p);CompoundNBT v=new CompoundNBT();v.putInt("Dimension",dimension);v.putString("ExpectedType","minecraft:pig");v.put("Entity",p);vanilla.add(v);
                    ArmorStandEntity stand=new ArmorStandEntity(EntityType.ARMOR_STAND,world);stand.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/stand/"+dimension).getBytes("UTF-8")));stand.setPosition(coordinate+5,82,coordinate+5);stand.setNoGravity(true);stand.setItemStackToSlot(EquipmentSlotType.HEAD,item(0));world.addEntity(stand);
                    p=new CompoundNBT();stand.writeUnlessPassenger(p);v=new CompoundNBT();v.putInt("Dimension",dimension);v.putString("ExpectedType","minecraft:armor_stand");v.put("Entity",p);vanilla.add(v);
                    ChestMinecartEntity cart=new ChestMinecartEntity(world,coordinate+6,82,coordinate+6);cart.setUniqueId(UUID.nameUUIDFromBytes((minecraft+"/cart/"+dimension).getBytes("UTF-8")));cart.setNoGravity(true);for(int slot=0;slot<8;slot++)cart.setInventorySlotContents(slot,item(slot+6));world.addEntity(cart);
                    p=new CompoundNBT();cart.writeUnlessPassenger(p);v=new CompoundNBT();v.putInt("Dimension",dimension);v.putString("ExpectedType","minecraft:chest_minecart");v.put("Entity",p);vanilla.add(v);
                }
            }
        }
        ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));
        ServerPlayerEntity player=new ServerPlayerEntity(server,world,new GameProfile(PLAYER,"UpgradeFixture"),new PlayerInteractionManager(world));player.setPosition(136.5,82,136.5);
        for(int slot=0;slot<36;slot++)player.inventory.setInventorySlotContents(slot,item(slot+6));
        for(int slot=0;slot<4;slot++)player.setItemStackToSlot(new EquipmentSlotType[]{EquipmentSlotType.HEAD,EquipmentSlotType.CHEST,EquipmentSlotType.LEGS,EquipmentSlotType.FEET}[slot],item(slot));
        player.setItemStackToSlot(EquipmentSlotType.OFFHAND,item(5));for(int slot=0;slot<27;slot++)player.getInventoryEnderChest().setInventorySlotContents(slot,item(slot+6));
        player.connection=new net.minecraft.network.play.ServerPlayNetHandler(server,new net.minecraft.network.NetworkManager(net.minecraft.network.PacketDirection.SERVERBOUND),player) {
            @Override public void sendPacket(net.minecraft.network.IPacket<?> packet){ }
        };
        net.minecraft.item.crafting.IRecipe<?> sugar=server.getRecipeManager().getRecipe(new net.minecraft.util.ResourceLocation("minecraft:sugar")).orElseThrow(()->new AssertionError("Missing historical14sugar recipe"));
        player.unlockRecipes(java.util.Collections.singleton(sugar));
        net.minecraft.advancements.Advancement advancement=server.getAdvancementManager().getAdvancement(new net.minecraft.util.ResourceLocation("minecraft:recipes/misc/sugar"));
        if(advancement==null)throw new AssertionError("Missing historical14sugar advancement");
        for(String criterion:advancement.getCriteria().keySet())player.getAdvancements().grantCriterion(advancement,criterion);
        player.getAdvancements().save();
        world.getSaveHandler().writePlayerData(player);
        manifest.put("BiomeSamples",biomes);manifest.putString("Recipe","minecraft:sugar");manifest.putString("Advancement","minecraft:recipes/misc/sugar");
        manifest.putUniqueId("Player",PLAYER);manifest.put("Bodies",bodies);manifest.put("Containers",containers);manifest.put("Vanilla",vanilla);
        server.save(true,true,false);
        File folder=world.getSaveHandler().getWorldDirectory();try(FileOutputStream file=new FileOutputStream(new File(folder,"upgrade-fixtures.nbt"))){CompressedStreamTools.writeCompressed(manifest,file);}
        try(FileWriter marker=new FileWriter(new File(folder,"legacy-seed.pass"))){marker.write(minecraft+" "+version+"\n");}
        System.out.println("LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft="+minecraft+" mod="+version+" corpses=9 dimensions=3");
    }
    private static boolean minecraftLongArrays(){return true;}
    private static void awaitChunk(ServerWorld world,int x,int z)throws Exception {
        world.getChunkProvider().getChunk(x,z,net.minecraft.world.chunk.ChunkStatus.FULL,true);
        if(!world.getChunkProvider().chunkExists(x,z))throw new AssertionError("Historical14native chunk admission failed");
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
        else if(slot==12)stack=new ItemStack(Items.OAK_SIGN,11);
        else stack=new ItemStack(Items.DIAMOND,slot%32+1);
        CompoundNBT tag=new CompoundNBT();tag.putString("fixture_slot","original-"+slot);tag.putLong("typed_long",1234567890123L);tag.putByteArray("typed_bytes",new byte[]{0,-1,17});
        if(slot==6){CompoundNBT pig=new CompoundNBT();pig.putString("id",oldMinecraft?"Pig":"minecraft:pig");pig.putString("CustomName",net.minecraft.util.text.ITextComponent.Serializer.toJson(new net.minecraft.util.text.StringTextComponent("Saved pig egg")));tag.put("EntityTag",pig);}
        if(slot==7){CompoundNBT tile=new CompoundNBT();ListNBT items=new ListNBT();CompoundNBT nested=new ItemStack(Items.EMERALD,17).write(new CompoundNBT());nested.putByte("Slot",(byte)3);items.add(nested);tile.put("Items",items);tag.put("BlockEntityTag",tile);}
        if(slot==8 && oldMinecraft){CompoundNBT banner=new CompoundNBT();banner.putInt("Base",5);tag.put("BlockEntityTag",banner);}
        if(minecraftLongArrays()) {
            tag.put("typed_longs",new LongArrayNBT(new long[]{Long.MIN_VALUE,slot,Long.MAX_VALUE}));
        }
        stack.setTag(tag);if(slot<6)stack.setDamage(slot<4?slot+2:slot==4?17:9);stack.setDisplayName(new net.minecraft.util.text.StringTextComponent("Original keepsake "+slot));if(slot==4)stack.addEnchantment(Enchantments.UNBREAKING,2);return stack;
    }
}
