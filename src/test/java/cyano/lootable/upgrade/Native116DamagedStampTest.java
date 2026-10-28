package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.TypeReferences;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ChunkLoader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Captured from the sealed released 1.15 writer; never candidate-generated. */
class Native116DamagedStampTest {
    static {net.minecraft.util.registry.Bootstrap.register();}
    @TempDir Path world;
    @AfterEach void close(){UpgradePreservation.close();}
    private CompoundNBT original()throws Exception {
        Path fixture=Paths.get("src/test/resources/native115-release-chunk.nbt");
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture));StringBuilder text=new StringBuilder();for(byte value:hash)text.append(String.format("%02X",value));
        assertEquals("B6D03C7E0962715745FCD93A31EA27C01B4601020A51F542898BCE09F14C10C2",text.toString());
        try(InputStream input=Files.newInputStream(fixture)){return CompressedStreamTools.readCompressed(input);}
    }
    private void preserve(CompoundNBT chunk)throws Exception {
        CompoundNBT header=new CompoundNBT(),data=new CompoundNBT(),fml=new CompoundNBT();
        data.putInt("DataVersion",2586);header.put("Data",data);
        ListNBT mods=new ListNBT();CompoundNBT minecraft=new CompoundNBT();minecraft.putString("ModId","minecraft");minecraft.putString("ModVersion","1.16.5");mods.add(minecraft);
        fml.put("LoadingModList",mods);header.put("fml",fml);
        try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){CompressedStreamTools.writeCompressed(header,output);}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(chunk,output);}
        int sectors=(bytes.size()+5+4095)/4096;ByteBuffer region=ByteBuffer.allocate(8192+sectors*4096);
        CompoundNBT level=chunk.getCompound("Level");int index=(level.getInt("xPos")&31)+(level.getInt("zPos")&31)*32;
        region.putInt(index*4,(2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());
        Path file=world.resolve("region/r.0.0.mca");Files.createDirectories(file.getParent());Files.write(file,region.array());UpgradePreservation.prepare(world);
    }
    @Test void currentHeaderCannotOverrideThePreservedWriterOfAnUnstampedChunk() {
        CompoundNBT context=new CompoundNBT(),fml=new CompoundNBT(),minecraft=new CompoundNBT();
        context.putInt("DataVersion",2230);context.putInt("CorpseWriterDataVersion",2230);minecraft.putString("ModId","minecraft");minecraft.putString("ModVersion","1.16.5");
        ListNBT mods=new ListNBT();mods.add(minecraft);fml.put("LoadingModList",mods);context.put("WorldFML",fml);
        assertEquals(2230,UpgradePreservation.chunkSourceVersion(context));
        context.putInt("ChunkDataVersion",1343);assertEquals(2230,UpgradePreservation.chunkSourceVersion(context));
        context.putInt("ChunkDataVersion",2586);assertEquals(2586,UpgradePreservation.chunkSourceVersion(context));
        context.putInt("VerifiedChunkDataVersion",2586);assertEquals(2586,UpgradePreservation.chunkSourceVersion(context));
    }
    @Test void bothNativeAnvilPassesRetainNestedChestDataWithMissingChunkStamps()throws Exception {
        CompoundNBT source=original();assertEquals(2230,source.getInt("DataVersion"));
        CompoundNBT expected=NativeNbt.update(NativeVanillaDataFixer.get(),TypeReferences.CHUNK,source.copy(),2230,2586);
        CompoundNBT damaged=source.copy();damaged.remove("DataVersion");preserve(damaged);
        try(ChunkLoader loader=new ChunkLoader(world.resolve("reader").toFile(),UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),false)) {
            CompoundNBT actual=loader.func_235968_a_(World.OVERWORLD,()->{throw new AssertionError("Current source must not replay legacy structure conversion");},damaged.copy());
            assertEquals(expected.getCompound("Level").get("TileEntities"),actual.getCompound("Level").get("TileEntities"));
            assertEquals(source.getCompound("Level").get("TileEntities"),original().getCompound("Level").get("TileEntities"));
        }
    }
}
