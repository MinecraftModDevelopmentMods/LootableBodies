package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Captured from the sealed released 1.15 writer; never candidate-generated. */
class Native116DamagedStampTest {
    static {net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    @TempDir Path world;
    @AfterEach void close(){UpgradePreservation.close();}
    private CompoundTag original()throws Exception {
        Path fixture=Paths.get("src/test/resources/native115-release-chunk.nbt");
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture));StringBuilder text=new StringBuilder();for(byte value:hash)text.append(String.format("%02X",value));
        assertEquals("B6D03C7E0962715745FCD93A31EA27C01B4601020A51F542898BCE09F14C10C2",text.toString());
        try(InputStream input=Files.newInputStream(fixture)){return NbtIo.readCompressed(input);}
    }
    private void preserve(CompoundTag chunk)throws Exception {
        CompoundTag header=new CompoundTag(),data=new CompoundTag(),fml=new CompoundTag();
        data.putInt("DataVersion",2586);header.put("Data",data);
        ListTag mods=new ListTag();CompoundTag minecraft=new CompoundTag();minecraft.putString("ModId","minecraft");minecraft.putString("ModVersion","1.16.5");mods.add(minecraft);
        fml.put("LoadingModList",mods);header.put("fml",fml);
        try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){NbtIo.writeCompressed(header,output);}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(new DeflaterOutputStream(bytes))){NbtIo.write(chunk,output);}
        int sectors=(bytes.size()+5+4095)/4096;ByteBuffer region=ByteBuffer.allocate(8192+sectors*4096);
        CompoundTag level=chunk.getCompound("Level");int index=(level.getInt("xPos")&31)+(level.getInt("zPos")&31)*32;
        region.putInt(index*4,(2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());
        Path file=world.resolve("region/r.0.0.mca");Files.createDirectories(file.getParent());Files.write(file,region.array());UpgradePreservation.prepare(world);
    }
    @Test void currentHeaderCannotOverrideThePreservedWriterOfAnUnstampedChunk() {
        CompoundTag context=new CompoundTag(),fml=new CompoundTag(),minecraft=new CompoundTag();
        context.putInt("DataVersion",2230);context.putInt("CorpseWriterDataVersion",2230);minecraft.putString("ModId","minecraft");minecraft.putString("ModVersion","1.16.5");
        ListTag mods=new ListTag();mods.add(minecraft);fml.put("LoadingModList",mods);context.put("WorldFML",fml);
        assertEquals(2230,UpgradePreservation.chunkSourceVersion(context));
        context.putInt("ChunkDataVersion",1343);assertEquals(2230,UpgradePreservation.chunkSourceVersion(context));
        context.putInt("ChunkDataVersion",2586);assertEquals(2586,UpgradePreservation.chunkSourceVersion(context));
        context.putInt("VerifiedChunkDataVersion",2586);assertEquals(2586,UpgradePreservation.chunkSourceVersion(context));
    }
    @Test void bothNativeAnvilPassesRetainNestedChestDataWithMissingChunkStamps()throws Exception {
        CompoundTag source=original();assertEquals(2230,source.getInt("DataVersion"));
        CompoundTag expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.CHUNK,source.copy(),2230,2586);
        CompoundTag damaged=source.copy();damaged.remove("DataVersion");preserve(damaged);
        try(ChunkStorage loader=new ChunkStorage(world.resolve("reader").toFile(),UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),false)) {
            CompoundTag actual=loader.upgradeChunkTag(Level.OVERWORLD,()->{throw new AssertionError("Current source must not replay legacy structure conversion");},damaged.copy());
            assertEquals(expected.getCompound("Level").get("TileEntities"),actual.getCompound("Level").get("TileEntities"));
            assertEquals(source.getCompound("Level").get("TileEntities"),original().getCompound("Level").get("TileEntities"));
        }
    }
}
