package cyano.lootable.buildtest;

import java.nio.file.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import cyano.lootable.upgrade.Native117RegionFixtures;

/** Derive negative fixtures from a saved Forge 61 flat header. */
public final class Native120FixtureGenerator {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Owned output root and sealed native header required");
        Path root=Path.of(args[0]);Files.createDirectory(root);
        new Native117TestEnvironment().launcherSessionOpened(null);
        Path source=Path.of(args[1]);
        CompoundTag header=NbtIo.readCompressed(source,NbtAccounter.create(64L*1024*1024));
        CompoundTag data=header.getCompoundOrEmpty("Data");
        int target=net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        CompoundTag generator=data.getCompoundOrEmpty("WorldGenSettings").getCompoundOrEmpty("dimensions")
                .getCompoundOrEmpty("minecraft:overworld").getCompoundOrEmpty("generator");
        if(data.getIntOr("DataVersion", 0)!=target || !generator.getStringOr("type", "").equals("minecraft:flat"))
            throw new AssertionError("Wrong native flat header");
        Files.copy(source,root.resolve("native12111-flat-level.dat"));
        Path world=root.resolve("controlled-entity-source");Files.createDirectory(world);
        CompoundTag originalOwned=new CompoundTag();originalOwned.putString("unknownField","retained-native12111-header");originalOwned.putLongArray("unknownData",new long[]{Long.MIN_VALUE,1234567890123L,Long.MAX_VALUE});
        header.put("lootablebodies",originalOwned);NbtIo.writeCompressed(header,world.resolve("level.dat"));
        CompoundTag corpse=Native117RegionFixtures.body(UUID.fromString("00000000-1171-4171-8171-000000000099"),7);
        Native117RegionFixtures.write(world,Native117RegionFixtures.chunk(2730,corpse));
        System.out.println("NATIVE12111_CODEC_FIXTURE_PREPARED version="+target+" markerSource=7/2730 synthetic=true root="+root);
    }
}
