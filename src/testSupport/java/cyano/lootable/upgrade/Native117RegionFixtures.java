package cyano.lootable.upgrade;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

/** Synthetic native-codec inputs, separate from genuine released-writer worlds. */
public final class Native117RegionFixtures {
    private Native117RegionFixtures(){}
    public static CompoundTag body(UUID id,int marker) {
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");body.putUUID("UUID",id);
        body.putInt(UpgradePreservation.VERSION,marker);body.putString("Name","Kept native owner");
        ListTag position=new ListTag();for(double coordinate:new double[]{8.5,64,8.5})position.add(DoubleTag.valueOf(coordinate));body.put("Pos",position);
        ListTag items=new ListTag();
        for(int slot=0;slot<48;slot++) {
            CompoundTag item=new CompoundTag();item.putByte("Slot",(byte)slot);item.putString("id","minecraft:diamond");item.putByte("Count",(byte)(slot+1));
            CompoundTag custom=new CompoundTag();custom.putString("keep","slot-"+slot);item.put("tag",custom);items.add(item);
        }
        body.put("Equipment",items);ListTag aux=new ListTag();aux.add(items.getCompound(47).copy());body.put("Aux",aux);
        CompoundTag recovered=new CompoundTag();recovered.putString("Source","FixtureUnknown");recovered.put("Data",items.getCompound(46).copy());
        ListTag recovery=new ListTag();recovery.add(recovered);body.put("LootableBodiesUnresolved",recovery);return body;
    }
    public static CompoundTag chunk(int version,CompoundTag... roots) {
        CompoundTag chunk=new CompoundTag();if(version>=0)chunk.putInt("DataVersion",version);chunk.putIntArray("Position",new int[]{0,0});
        ListTag entities=new ListTag();for(CompoundTag root:roots)entities.add(root.copy());chunk.put("Entities",entities);return chunk;
    }
    public static Path write(Path world,CompoundTag chunk) throws Exception {
        Path folder=world.resolve("entities");Files.createDirectories(folder);Path path=folder.resolve("r.0.0.mca");
        try(RegionFile region=new RegionFile(path.toFile(),folder.toFile(),false)) {
            try(DataOutputStream output=region.getChunkDataOutputStream(new ChunkPos(0,0))){NbtIo.write(chunk,output);}
        }
        return path;
    }
}
