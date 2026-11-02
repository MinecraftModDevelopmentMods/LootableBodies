package cyano.lootable.buildtest;

import java.nio.file.*;
import java.util.UUID;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import cyano.lootable.upgrade.Native117RegionFixtures;

/** Native18 codec fixture preparation, never authentic historical-writer proof. */
public final class Native118FixtureGenerator {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One new owned output root required");
        Path root=Path.of(args[0]);Files.createDirectory(root);
        new Native117TestEnvironment().launcherSessionOpened(null);
        RegistryAccess.Frozen registries=RegistryAccess.BUILTIN.get();
        Registry<Biome> biomes=registries.registryOrThrow(Registry.BIOME_REGISTRY);
        Registry<net.minecraft.world.level.levelgen.structure.StructureSet> structures=registries.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
        WorldGenSettings generation=new WorldGenSettings(4267321L,false,false,WorldGenSettings.withOverworld(
                registries.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY),DimensionType.defaultDimensions(registries,4267321L),
                new FlatLevelSource(structures,FlatLevelGeneratorSettings.getDefault(biomes,structures))));
        LevelSettings settings=new LevelSettings("Native18 disposable fixture",GameType.CREATIVE,false,Difficulty.NORMAL,true,new GameRules(),DataPackConfig.DEFAULT);
        PrimaryLevelData nativeData=new PrimaryLevelData(settings,generation,Lifecycle.stable());
        nativeData.setSpawn(new BlockPos(0,80,0),0);nativeData.setGameTime(0);nativeData.setDayTime(6000);
        CompoundTag data=nativeData.createTag(registries,null);data.putLong("LastPlayed",0);
        if(data.getInt("DataVersion")!=2975 || !data.contains("WorldGenSettings",10))throw new AssertionError("Native18 header was not encoded");
        CompoundTag header=new CompoundTag();header.put("Data",data);
        NbtIo.writeCompressed(header,root.resolve("native118-flat-level.dat").toFile());
        Path world=root.resolve("controlled-entity-source");Files.createDirectory(world);
        CompoundTag originalOwned=new CompoundTag();originalOwned.putString("unknownField","retained-native18-header");originalOwned.putLongArray("unknownData",new long[]{Long.MIN_VALUE,1234567890123L,Long.MAX_VALUE});
        header.put("lootablebodies",originalOwned);NbtIo.writeCompressed(header,world.resolve("level.dat").toFile());
        CompoundTag corpse=Native117RegionFixtures.body(UUID.fromString("00000000-1171-4171-8171-000000000099"),7);
        Native117RegionFixtures.write(world,Native117RegionFixtures.chunk(2730,corpse));
        System.out.println("NATIVE118_CODEC_FIXTURE_PREPARED version=2975 markerSource=7/2730 synthetic=true root="+root);
    }
}
