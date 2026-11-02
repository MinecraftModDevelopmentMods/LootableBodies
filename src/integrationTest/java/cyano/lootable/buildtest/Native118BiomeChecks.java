package cyano.lootable.buildtest;

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
import net.minecraft.world.level.chunk.storage.ChunkStorage;

/** Compare every old quart sample by native biome identity, not obsolete registry IDs. */
final class Native118BiomeChecks {
    private Native118BiomeChecks() { }
    static void verify(ServerLevel world,CompoundTag record,int wave) {
        int x=record.getInt("ChunkX"),z=record.getInt("ChunkZ");
        CompoundTag root=new CompoundTag(),level=new CompoundTag();root.putInt("DataVersion",2730);root.put("Level",level);
        level.putInt("xPos",x);level.putInt("zPos",z);level.putString("Status","full");level.put("Sections",new ListTag());
        level.putIntArray("Biomes",HistoricalBiomes.nativeValues(record.getIntArray("Data")));
        ChunkStorage.injectDatafixingContext(root,world.dimension(),Optional.empty());
        CompoundTag nativeResult=cyano.lootable.upgrade.NativeNbt.update(NativeVanillaDataFixer.get(),References.CHUNK,root,2730,2975);
        Registry<Biome> registry=world.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        com.mojang.serialization.Codec<PalettedContainer<Holder<Biome>>> codec=PalettedContainer.codec(registry.asHolderIdMap(),registry.holderByNameCodec(),PalettedContainer.Strategy.SECTION_BIOMES,registry.getHolderOrThrow(Biomes.PLAINS));
        LevelChunk chunk=world.getChunk(x,z);int samples=0;
        for(net.minecraft.nbt.Tag entry:nativeResult.getList("sections",10)) {
            CompoundTag section=(CompoundTag)entry;int sectionY=section.getInt("Y");
            if(sectionY<0 || sectionY>=16)continue; // All 1024 samples from the saved 0..255 range.
            PalettedContainer<Holder<Biome>> expected=codec.parse(NbtOps.INSTANCE,section.getCompound("biomes")).getOrThrow(false,message->{throw new AssertionError(message);});
            for(int y=0;y<4;y++)for(int zz=0;zz<4;zz++)for(int xx=0;xx<4;xx++) {
                Holder<Biome> actual=chunk.getNoiseBiome(xx,sectionY*4+y,zz);
                RuntimeChecks.require(expected.get(xx,y,zz).unwrapKey().equals(actual.unwrapKey()),"Historical biome changed in dimension "+record.getInt("Dimension")+" wave "+wave+" section "+sectionY+" sample "+xx+","+y+","+zz);
                samples++;
            }
        }
        RuntimeChecks.require(samples==1024,"Native biome conversion omitted saved quart samples");
    }
}
