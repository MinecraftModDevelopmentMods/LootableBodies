package cyano.lootable.upgrade;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native oversized-chunk writer, never historical-world provenance. */
class Native117ExternalSidecarTest {
    @TempDir Path temporary;
    @Test void actualNativeExternalChunkRefusesConservativelyWithAllOriginalBytesIntact() throws Exception {
        String exported=System.getProperty("lootablebodies.exportNativeExternalWorld");
        Path world=exported==null?temporary.resolve("world"):Paths.get(exported);
        assertFalse(Files.exists(world),"Refusing an existing native external-chunk fixture");
        Path directory=world.resolve("entities");Files.createDirectories(directory);
        Path header=Paths.get("tools/fixtures/native117-flat-level.dat");
        String headerHash=String.format(Locale.ROOT,"%064X",new java.math.BigInteger(1,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(header))));
        assertEquals("358E4B91F290AFB219D85B09435B4A641035412686E31A09CF5C69F5A28515CB",headerHash);
        Files.copy(header,world.resolve("level.dat"));
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");
        UUID identity=UUID.fromString("00000000-1171-4171-8171-000000000099");body.putUUID("UUID",identity);
        body.putInt(UpgradePreservation.VERSION,7);body.putString("Name","Native external control");
        ListTag equipment=new ListTag();
        for(int slot=0;slot<48;slot++) {
            CompoundTag item=new CompoundTag();item.putByte("Slot",(byte)slot);item.putString("id","minecraft:diamond");item.putByte("Count",(byte)1);equipment.add(item);
        }
        body.put("Equipment",equipment);
        byte[] opaque=new byte[2*1024*1024];new Random(1170199L).nextBytes(opaque);body.putByteArray("NativeExternalControl",opaque);
        CompoundTag chunk=new CompoundTag();chunk.putInt("DataVersion",2730);chunk.putIntArray("Position",new int[]{0,0});
        ListTag entities=new ListTag();entities.add(body);chunk.put("Entities",entities);
        Path file=directory.resolve("r.0.0.mca");
        try(RegionFile region=new RegionFile(file.toFile(),directory.toFile(),false)) {
            try(DataOutputStream out=region.getChunkDataOutputStream(new ChunkPos(0,0))){NbtIo.write(chunk,out);}
        }
        Path sidecar=directory.resolve("c.0.0.mcc");assertTrue(Files.isRegularFile(sidecar));assertTrue(Files.size(sidecar)>1024*1024);
        byte[] raw=Files.readAllBytes(file),payload=Files.readAllBytes(sidecar);
        int location=ByteBuffer.wrap(raw).getInt(),offset=(location>>>8)*4096;
        assertEquals(0x82,raw[offset+4]&255,"Native writer must actually select external zlib storage");
        try(RegionFile region=new RegionFile(file.toFile(),directory.toFile(),false);
                DataInputStream in=region.getChunkDataInputStream(new ChunkPos(0,0))) {
            assertEquals(chunk,NbtIo.read(in),"Native reader must decode the original external entity chunk");
        }
        IOException refusal=assertThrows(IOException.class,()->UpgradeJournal.scan(world));
        assertTrue(refusal.getMessage().startsWith("Invalid compressed chunk size:"),
            "The existing conservative reader must reject the native one-byte external stub before decoding any entity");
        assertArrayEquals(raw,Files.readAllBytes(file));assertArrayEquals(payload,Files.readAllBytes(sidecar));
        assertArrayEquals(Files.readAllBytes(header),Files.readAllBytes(world.resolve("level.dat")));
    }
}
