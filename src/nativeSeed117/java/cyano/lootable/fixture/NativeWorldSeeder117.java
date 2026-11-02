package cyano.lootable.fixture;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.buildtest.FixtureItems;
import java.io.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.*;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkBiomeContainer;
import net.minecraft.world.level.storage.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fmlserverevents.FMLServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Compiled against released17 source; runtime contains public854A, never target18. */
@Mod("lootablebodiesbuildtest")
public final class NativeWorldSeeder117 {
    private static final UUID PLAYER=UUID.fromString("00000000-2222-4222-8222-000000000001");
    public NativeWorldSeeder117(){FixtureItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void started(FMLServerStartedEvent event){
        MinecraftServer server=event.getServer();
        try{seed(server);}catch(Throwable failure){failure.printStackTrace();}finally{server.halt(false);}
    }
    private static void seed(MinecraftServer server)throws Exception {
        String minecraft=server.getServerVersion(),version=net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").orElseThrow().getModInfo().getVersion().toString();
        require(minecraft.equals("1.17.1") && version.equals("2.4.8.117011") && version.equals(System.getProperty("lootablebodies.fixture.version")),"Wrong public17 native writer identity");
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        CompoundTag manifest=new CompoundTag();manifest.putString("Minecraft",minecraft);manifest.putString("ModVersion",version);
        ListTag bodies=new ListTag(),containers=new ListTag(),vanilla=new ListTag(),biomes=new ListTag();
        for(int dimension:new int[]{0,-1,1}) {
            ServerLevel world=server.getLevel(dimensionKey(dimension));
            for(int wave=0;wave<3;wave++) {
                int coordinate=wave==0?128:wave==1?4096:8192;
                awaitChunk(world,coordinate>>4,coordinate>>4);
                Biome[] samples=biomes(world.getChunk(coordinate>>4,coordinate>>4).getBiomes());
                Registry<Biome> registry=world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
                Biome[] choices={registry.getOrThrow(Biomes.PLAINS),registry.getOrThrow(Biomes.DESERT),registry.getOrThrow(Biomes.FOREST),registry.getOrThrow(Biomes.TAIGA)};
                int[] savedBiomes=new int[samples.length];
                for(int i=0;i<samples.length;i++){samples[i]=choices[(i/4+i%7)%choices.length];savedBiomes[i]=registry.getId(samples[i]);}
                CompoundTag biomeRecord=new CompoundTag();biomeRecord.putInt("Dimension",dimension);biomeRecord.putInt("Wave",wave);biomeRecord.putInt("ChunkX",coordinate>>4);biomeRecord.putInt("ChunkZ",coordinate>>4);biomeRecord.putIntArray("Data",savedBiomes);biomes.add(biomeRecord);
                EntityLootableBody body=new EntityLootableBody(world);
                body.setUUID(UUID.nameUUIDFromBytes((minecraft+"/"+version+"/"+dimension+"/"+wave).getBytes("UTF-8")));
                body.setPos(coordinate+.5,80,coordinate+.5);body.setNoGravity(true);body.setUserName("SavedFixtureOwner");body.setRotation(37);body.setHealth(7.5F);
                for(int slot=0;slot<48;slot++)body.setItem(slot,item(slot));
                for(int slot=70;slot<73;slot++)body.addItem(item(slot));
                if(Boolean.getBoolean("lootablebodies.fixture.recovery")) {
                    CompoundTag damaged=saved(body);ListTag equipment=damaged.getList("Equipment",10);
                    equipment.getCompound(20).putString("id","lootablebodiesbuildtest:returning_item");
                    CompoundTag duplicate=equipment.getCompound(21).copy();duplicate.putByte("Count",(byte)9);duplicate.getCompound("tag").putString("extension","duplicate slot");equipment.add(duplicate);
                    CompoundTag invalid=equipment.getCompound(22).copy();invalid.putInt("Slot",999);invalid.putByte("Count",(byte)7);invalid.getCompound("tag").putString("extension","invalid slot");equipment.add(invalid);
                    damaged.putInt("Name",17);damaged.putDouble("DeathTime",Double.NaN);damaged.putString("Health","opaque health");damaged.putString("ActiveEffects","opaque effects");damaged.putString("HurtTime","opaque hurt timer");damaged.putDouble("FallDistance",Double.POSITIVE_INFINITY);
                    body.load(damaged);body.setPos(coordinate+.5,80,coordinate+.5);
                }
                java.lang.reflect.Field vacuum=EntityLootableBody.class.getDeclaredField("vacuumTime");vacuum.setAccessible(true);vacuum.setByte(body,(byte)20);
                require(world.addFreshEntity(body),"Historical17 corpse admission");
                CompoundTag record=new CompoundTag();record.putInt("Dimension",dimension);record.putInt("Wave",wave);record.put("Body",saved(body));bodies.add(record);
                BlockPos chestPos=new BlockPos(coordinate+2,80,coordinate+2);world.setBlockAndUpdate(chestPos,Blocks.CHEST.defaultBlockState());
                ChestBlockEntity chest=(ChestBlockEntity)world.getBlockEntity(chestPos);for(int slot=0;slot<16;slot++)chest.setItem(slot,item(slot+6));
                CompoundTag chestRecord=new CompoundTag();chestRecord.putInt("Dimension",dimension);chestRecord.putInt("Wave",wave);chestRecord.put("Tile",chest.save(new CompoundTag()));containers.add(chestRecord);
                if(wave==0)seedVanilla(world,dimension,coordinate,vanilla);
            }
        }
        ServerLevel world=server.getLevel(Level.OVERWORLD);ServerPlayer player=new ServerPlayer(server,world,new GameProfile(PLAYER,"UpgradeFixture"));player.setPos(136.5,82,136.5);
        for(int slot=0;slot<36;slot++)player.getInventory().setItem(slot,item(slot+6));
        for(int slot=0;slot<4;slot++)player.setItemSlot(new EquipmentSlot[]{EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET}[slot],item(slot));
        player.setItemSlot(EquipmentSlot.OFFHAND,item(5));for(int slot=0;slot<27;slot++)player.getEnderChestInventory().setItem(slot,item(slot+6));
        player.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(server,new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),player){@Override public void send(net.minecraft.network.protocol.Packet<?> packet){}};
        net.minecraft.world.item.crafting.Recipe<?> sugar=server.getRecipeManager().byKey(new ResourceLocation("minecraft:sugar_from_sugar_cane")).orElseThrow();player.awardRecipes(Collections.singleton(sugar));
        net.minecraft.advancements.Advancement advancement=server.getAdvancements().getAdvancement(new ResourceLocation("minecraft:recipes/misc/sugar_from_sugar_cane"));require(advancement!=null,"Missing native17 sugar advancement");
        for(String criterion:advancement.getCriteria().keySet())player.getAdvancements().award(advancement,criterion);player.getAdvancements().save();playerData(server).save(player);
        manifest.put("BiomeSamples",biomes);manifest.putString("Recipe","minecraft:sugar_from_sugar_cane");manifest.putString("Advancement","minecraft:recipes/misc/sugar_from_sugar_cane");manifest.putUUID("Player",PLAYER);manifest.put("Bodies",bodies);manifest.put("Containers",containers);manifest.put("Vanilla",vanilla);
        server.saveAllChunks(true,true,false);File folder=server.getWorldPath(LevelResource.ROOT).toFile();
        try(FileOutputStream out=new FileOutputStream(new File(folder,"upgrade-fixtures.nbt"))){NbtIo.writeCompressed(manifest,out);}
        try(FileWriter out=new FileWriter(new File(folder,"legacy-seed.pass"))){out.write(minecraft+" "+version+"\n");}
        System.out.println("LOOTABLE_BODIES_LEGACY_SEED_PASS minecraft="+minecraft+" mod="+version+" corpses=9 dimensions=3");
    }
    private static void seedVanilla(ServerLevel world,int dimension,int c,ListTag manifest){
        VillagerProfession[] professions={VillagerProfession.FARMER,VillagerProfession.LIBRARIAN,VillagerProfession.CLERIC,VillagerProfession.ARMORER,VillagerProfession.BUTCHER,VillagerProfession.NITWIT};
        for(int i=0;i<professions.length;i++) {
            Villager villager=new Villager(EntityType.VILLAGER,world);villager.setUUID(id("villager/"+dimension+"/"+i));villager.setPos(c+8+i,82,c+8);villager.setNoGravity(true);villager.setNoAi(true);villager.setVillagerData(villager.getVillagerData().setProfession(professions[i]).setLevel(3));
            CompoundTag data=saved(villager);ListTag inventory=new ListTag();inventory.add(item(7).save(new CompoundTag()));data.put("Inventory",inventory);villager.load(data);addVanilla(world,dimension,villager,"minecraft:villager",manifest);
        }
        Pig pig=new Pig(EntityType.PIG,world);pig.setUUID(id("pig/"+dimension));pig.setPos(c+4,82,c+4);pig.setNoGravity(true);pig.setNoAi(true);pig.setCustomName(new TextComponent("Saved pig"));addVanilla(world,dimension,pig,"minecraft:pig",manifest);
        ArmorStand stand=new ArmorStand(EntityType.ARMOR_STAND,world);stand.setUUID(id("stand/"+dimension));stand.setPos(c+5,82,c+5);stand.setNoGravity(true);stand.setItemSlot(EquipmentSlot.HEAD,item(0));addVanilla(world,dimension,stand,"minecraft:armor_stand",manifest);
        MinecartChest cart=new MinecartChest(world,c+6,82,c+6);cart.setUUID(id("cart/"+dimension));cart.setNoGravity(true);for(int slot=0;slot<8;slot++)cart.setItem(slot,item(slot+6));addVanilla(world,dimension,cart,"minecraft:chest_minecart",manifest);
    }
    private static void addVanilla(ServerLevel world,int dimension,Entity entity,String type,ListTag manifest){require(world.addFreshEntity(entity),"Native17 vanilla admission "+type);CompoundTag record=new CompoundTag();record.putInt("Dimension",dimension);record.putString("ExpectedType",type);record.put("Entity",saved(entity));manifest.add(record);}
    private static UUID id(String suffix){return UUID.nameUUIDFromBytes(("1.17.1/"+suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static CompoundTag saved(Entity entity){CompoundTag n=new CompoundTag();require(entity.saveAsPassenger(n),"Historical17 serialization");return n;}
    private static ResourceKey<Level> dimensionKey(int id){if(id==0)return Level.OVERWORLD;if(id==-1)return Level.NETHER;if(id==1)return Level.END;throw new IllegalArgumentException("dimension "+id);}
    private static void awaitChunk(ServerLevel world,int x,int z){world.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.ChunkStatus.FULL,true);require(world.getChunkSource().hasChunk(x,z),"Native17 chunk admission");}
    private static Biome[] biomes(ChunkBiomeContainer container)throws Exception {java.lang.reflect.Field found=null;for(java.lang.reflect.Field f:ChunkBiomeContainer.class.getDeclaredFields())if(f.getType()==Biome[].class){require(found==null,"Ambiguous native17 biome array");found=f;}require(found!=null,"Missing native17 biome array");found.setAccessible(true);return (Biome[])found.get(container);}
    private static PlayerDataStorage playerData(MinecraftServer server)throws Exception {java.lang.reflect.Field found=null;for(Class<?> owner=server.getClass();owner!=null;owner=owner.getSuperclass())for(java.lang.reflect.Field f:owner.getDeclaredFields())if(f.getType()==PlayerDataStorage.class){require(found==null,"Ambiguous native17 player storage");found=f;}require(found!=null,"Missing native17 player storage");found.setAccessible(true);return (PlayerDataStorage)found.get(server);}
    private static ItemStack item(int slot){
        ItemStack stack;
        if(slot<4)stack=new ItemStack(new Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS}[slot]);
        else if(slot==4)stack=new ItemStack(Items.DIAMOND_SWORD);else if(slot==5)stack=new ItemStack(Items.SHIELD);else if(slot==6)stack=new ItemStack(Items.PIG_SPAWN_EGG,3);else if(slot==7)stack=new ItemStack(Blocks.CHEST,2);else if(slot==8)stack=new ItemStack(Items.LIME_BANNER,2);else if(slot==9)stack=new ItemStack(FixtureItems.keepsake,11);else if(slot==10)stack=new ItemStack(Items.WHITE_BED);else if(slot==11)stack=new ItemStack(Items.LIME_BED);else if(slot==12)stack=new ItemStack(Items.OAK_SIGN,11);else stack=new ItemStack(Items.DIAMOND,slot%32+1);
        CompoundTag tag=new CompoundTag();tag.putString("fixture_slot","original-"+slot);tag.putLong("typed_long",1234567890123L);tag.putByteArray("typed_bytes",new byte[]{0,-1,17});tag.putLongArray("typed_longs",new long[]{Long.MIN_VALUE,slot,Long.MAX_VALUE});
        if(slot==6){CompoundTag pig=new CompoundTag();pig.putString("id","minecraft:pig");pig.putString("CustomName",net.minecraft.network.chat.Component.Serializer.toJson(new TextComponent("Saved pig egg")));tag.put("EntityTag",pig);}
        if(slot==7){CompoundTag tile=new CompoundTag();ListTag items=new ListTag();CompoundTag nested=new ItemStack(Items.EMERALD,17).save(new CompoundTag());nested.putByte("Slot",(byte)3);items.add(nested);tile.put("Items",items);tag.put("BlockEntityTag",tile);}
        stack.setTag(tag);if(slot<6)stack.setDamageValue(slot<4?slot+2:slot==4?17:9);stack.setHoverName(new TextComponent("Original keepsake "+slot));if(slot==4)stack.enchant(Enchantments.UNBREAKING,2);return stack;
    }
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
