package cyano.lootable.buildtest;

import static cyano.lootable.buildtest.TestNativeNbt.hasType;
import static cyano.lootable.buildtest.TestNativeNbt.typedList;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/** Compare every old quart sample by native biome identity, not obsolete registry IDs. */
final class Native118BiomeChecks {
    private Native118BiomeChecks() { }
    static void verify(ServerLevel world,CompoundTag record,int wave) {
        if(hasType(record, "Sections", 9)) {verifyNative18Sections(world,record,wave);return;}
        int x=record.getIntOr("ChunkX", 0),z=record.getIntOr("ChunkZ", 0);
        CompoundTag root=new CompoundTag(),level=new CompoundTag();root.putInt("DataVersion",2730);root.put("Level",level);
        level.putInt("xPos",x);level.putInt("zPos",z);level.putString("Status","full");level.put("Sections",new ListTag());
        level.putIntArray("Biomes",HistoricalBiomes.nativeValues(record.getIntArray("Data").orElseGet(() -> new int[0])));
        CompoundTag context = new CompoundTag();
        context.putString("dimension",world.dimension().identifier().toString());
        SimpleRegionStorage.injectDatafixingContext(root,context);
        CompoundTag nativeResult=cyano.lootable.upgrade.NativeNbt.update(NativeVanillaDataFixer.get(),References.CHUNK,root,2730,3465);
        var codec = PalettedContainerFactory.create(world.registryAccess()).biomeContainerCodec();
        LevelChunk chunk=world.getChunk(x,z);int samples=0;
        for(net.minecraft.nbt.Tag entry:typedList(nativeResult, "sections", 10)) {
            CompoundTag section=(CompoundTag)entry;int sectionY=section.getIntOr("Y", 0);
            if(sectionY<0 || sectionY>=16)continue; // All 1024 samples from the saved 0..255 range.
            PalettedContainerRO<Holder<Biome>> expected=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("biomes")) .getOrThrow(message->{throw new AssertionError(message);});
            for(int y=0;y<4;y++)for(int zz=0;zz<4;zz++)for(int xx=0;xx<4;xx++) {
                Holder<Biome> actual=chunk.getNoiseBiome(xx,sectionY*4+y,zz);
                RuntimeChecks.require(expected.get(xx,y,zz).unwrapKey().equals(actual.unwrapKey()),"Historical biome changed in dimension "+record.getIntOr("Dimension", 0)+" wave "+wave+" section "+sectionY+" sample "+xx+","+y+","+zz);
                samples++;
            }
        }
        RuntimeChecks.require(samples==1024,"Native biome conversion omitted saved quart samples");
    }
    private static void verifyNative18Sections(ServerLevel world,CompoundTag record,int wave) {
        LevelChunk chunk=world.getChunk(record.getIntOr("ChunkX", 0),record.getIntOr("ChunkZ", 0));
        ListTag sections=typedList(record, "Sections", 10);java.util.Set<Integer> seen=new java.util.HashSet<>();int samples=0;
        RuntimeChecks.require(sections.size()==chunk.getSectionsCount(),"Native18 saved biome section coverage changed");
        for(net.minecraft.nbt.Tag entry:sections) {
            CompoundTag section=(CompoundTag)entry;int y=section.getIntOr("Y", 0);
            RuntimeChecks.require(hasType(section, "Y", 3) && seen.add(y) && y>=chunk.getMinSectionY() && y<=chunk.getMaxSectionY(),"Native18 biome section identity changed");
            net.minecraft.nbt.Tag raw=section.get("Names");
            RuntimeChecks.require(TestNativeNbt.typedList(section,"Names",8).size()==64,"Native18 biome samples are incomplete");
            ListTag names=(ListTag)raw;int index=0;
            for(int yy=0;yy<4;yy++)for(int zz=0;zz<4;zz++)for(int xx=0;xx<4;xx++) {
                String expected=names.getStringOr(index++, "");
                String actual=chunk.getNoiseBiome(xx,y*4+yy,zz).unwrapKey().orElseThrow().identifier().toString();
                RuntimeChecks.require(expected.equals(actual),"Native18 biome identity changed in dimension "+record.getIntOr("Dimension", 0)+" wave "+wave+" section "+y+" sample "+xx+","+yy+","+zz);
                samples++;
            }
        }
        RuntimeChecks.require(samples==chunk.getSectionsCount()*64,"Native18 biome samples were omitted");
    }
}
