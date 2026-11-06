package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.io.*;
import java.nio.file.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.minecraft.world.level.validation.PathAllowList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EarlyWorldPreservationTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    @AfterEach void close(){UpgradePreservation.close();}
    private CompoundTag level(int version) {
        CompoundTag root=new CompoundTag(),data=new CompoundTag();data.putInt("DataVersion",version);root.put("Data",data);
        CompoundTag owned=new CompoundTag();owned.putString("unknown","retained world metadata");root.put("lootablebodies",owned);return root;
    }
    private void write(Path world,CompoundTag root)throws Exception {
        Files.createDirectories(world);try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){NbtIo.writeCompressed(root,output);}
    }
    @Test void nativeReadHookAbortsBeforeFallbackOrLevelWrites()throws Exception {
        Path world=directory.resolve("fixture");CompoundTag root=level(2230);write(world,root);
        Path journal=world.resolve("data/lootablebodies-upgrade");Files.createDirectories(journal);Files.write(journal.resolve("index.nbt"),new byte[]{1,2,3});
        byte[] before=Files.readAllBytes(world.resolve("level.dat"));Native118UnitLifecycle.activate();
        LevelStorageSource format=new LevelStorageSource(directory,directory.resolve("backups"),new DirectoryValidator(new PathAllowList(java.util.List.of())),NativeVanillaDataFixer.get());
        try(LevelStorageSource.LevelStorageAccess save=format.createAccess("fixture")) {
            Error failure=assertThrows(Error.class,()->EarlyWorldPreservation.beforeRead(save));
            assertTrue(failure.getMessage().contains("Lootable Bodies could not preserve this world's corpses"));
        }
        assertArrayEquals(before,Files.readAllBytes(world.resolve("level.dat")));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(journal.resolve("index.nbt")));
    }
    @Test void startupKeepsItsEarlySourceContextAndUnknownOwnedMetadata()throws Exception {
        Path world=directory.resolve("fixture");CompoundTag root=level(2230);write(world,root);Native118UnitLifecycle.activate();
        LevelStorageSource format=new LevelStorageSource(directory,directory.resolve("backups"),new DirectoryValidator(new PathAllowList(java.util.List.of())),NativeVanillaDataFixer.get());
        try(LevelStorageSource.LevelStorageAccess save=format.createAccess("fixture")) {
            EarlyWorldPreservation.beforeRead(save);
            UpgradeJournal early=UpgradePreservation.journal();assertEquals(2230,early.worldVersions().getInt("WorldDataVersion"));
            write(world,level(2586));assertSame(early,UpgradePreservation.prepareForStart(world));
            assertEquals(2230,early.worldVersions().getInt("WorldDataVersion"));
            CompoundTag output=new CompoundTag();EarlyWorldPreservation.beforeWrite(save,output);
            assertEquals(root.get("lootablebodies"),output.get("lootablebodies"));
            output.getCompound("lootablebodies").putString("unknown","changed copy");
            CompoundTag again=new CompoundTag();EarlyWorldPreservation.beforeWrite(save,again);
            assertEquals(root.get("lootablebodies"),again.get("lootablebodies"));
        }
    }
    @Test void nativeSessionLockOnlyOverwritesItsThreeByteMarker()throws Exception {
        byte[] old=new byte[]{0,0,1,2,3,4,5,6};Files.write(directory.resolve("session.lock"),old);
        try(net.minecraft.util.DirectoryLock ignored=net.minecraft.util.DirectoryLock.create(directory)) {
            assertTrue(Files.isRegularFile(directory.resolve("session.lock")));
        }
        // Windows denies a separate reader while this native lock is held.
        assertArrayEquals(new byte[]{(byte)0xe2,(byte)0x98,(byte)0x83,2,3,4,5,6},Files.readAllBytes(directory.resolve("session.lock")));
    }
}
