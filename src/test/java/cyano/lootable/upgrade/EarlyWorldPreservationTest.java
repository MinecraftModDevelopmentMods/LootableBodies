package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.io.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
import net.minecraft.world.storage.SaveFormat;
import net.minecraftforge.fml.WorldPersistenceHooks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EarlyWorldPreservationTest {
    @TempDir Path directory;
    @AfterEach void close(){UpgradePreservation.close();}
    private CompoundNBT level(int version) {
        CompoundNBT root=new CompoundNBT(),data=new CompoundNBT();data.putInt("DataVersion",version);root.put("Data",data);
        CompoundNBT owned=new CompoundNBT();owned.putString("unknown","retained world metadata");root.put("lootablebodies",owned);return root;
    }
    private void write(Path world,CompoundNBT root)throws Exception {
        Files.createDirectories(world);try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){CompressedStreamTools.writeCompressed(root,output);}
    }
    @Test void nativeReadHookAbortsBeforeFallbackOrLevelWrites()throws Exception {
        Path world=directory.resolve("fixture");CompoundNBT root=level(2230);write(world,root);
        Path journal=world.resolve("data/lootablebodies-upgrade");Files.createDirectories(journal);Files.write(journal.resolve("index.nbt"),new byte[]{1,2,3});
        byte[] before=Files.readAllBytes(world.resolve("level.dat"));EarlyWorldPreservation.install();
        SaveFormat format=new SaveFormat(directory,directory.resolve("backups"),NativeVanillaDataFixer.get());
        try(SaveFormat.LevelSave save=format.getLevelSave("fixture")) {
            Error failure=assertThrows(Error.class,()->WorldPersistenceHooks.handleWorldDataLoad(save,null,root));
            assertTrue(failure.getMessage().contains("Lootable Bodies could not preserve this world's corpses"));
        }
        assertArrayEquals(before,Files.readAllBytes(world.resolve("level.dat")));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(journal.resolve("index.nbt")));
    }
    @Test void startupKeepsItsEarlySourceContextAndUnknownOwnedMetadata()throws Exception {
        Path world=directory.resolve("fixture");CompoundNBT root=level(2230);write(world,root);EarlyWorldPreservation.install();
        SaveFormat format=new SaveFormat(directory,directory.resolve("backups"),NativeVanillaDataFixer.get());
        try(SaveFormat.LevelSave save=format.getLevelSave("fixture")) {
            WorldPersistenceHooks.handleWorldDataLoad(save,null,root);
            UpgradeJournal early=UpgradePreservation.journal();assertEquals(2230,early.worldVersions().getInt("WorldDataVersion"));
            write(world,level(2586));assertSame(early,UpgradePreservation.prepareForStart(world));
            assertEquals(2230,early.worldVersions().getInt("WorldDataVersion"));
            CompoundNBT output=new CompoundNBT();WorldPersistenceHooks.handleWorldDataSave(save,null,output);
            assertEquals(root.get("lootablebodies"),output.get("lootablebodies"));
            output.getCompound("lootablebodies").putString("unknown","changed copy");
            CompoundNBT again=new CompoundNBT();WorldPersistenceHooks.handleWorldDataSave(save,null,again);
            assertEquals(root.get("lootablebodies"),again.get("lootablebodies"));
        }
    }
    @Test void nativeSessionLockOnlyOverwritesItsThreeByteMarker()throws Exception {
        byte[] old=new byte[]{0,0,1,2,3,4,5,6};Files.write(directory.resolve("session.lock"),old);
        try(net.minecraft.server.SessionLockManager ignored=net.minecraft.server.SessionLockManager.func_232998_a_(directory)) {
            assertTrue(Files.isRegularFile(directory.resolve("session.lock")));
        }
        // Windows denies a separate reader while this native lock is held.
        assertArrayEquals(new byte[]{(byte)0xe2,(byte)0x98,(byte)0x83,2,3,4,5,6},Files.readAllBytes(directory.resolve("session.lock")));
    }
}
