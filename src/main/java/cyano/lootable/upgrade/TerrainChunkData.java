package cyano.lootable.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Distinguish native pre-1.18 Level payloads from the flattened 1.18 schema. */
final class TerrainChunkData {
    static final int FLATTENED_SCHEMA=2842;
    private TerrainChunkData() { }

    static CompoundTag payload(CompoundTag root) {
        if(root.contains("Level")) {
            if(!root.contains("Level",10) || root.contains("xPos") || root.contains("zPos") ||
                    root.contains("sections") || root.contains("entities") || root.contains("block_entities"))
                throw new IllegalStateException("Ambiguous legacy and flattened terrain payload");
            CompoundTag level=root.getCompound("Level");
            compounds(level,"Entities");compounds(level,"TileEntities");compounds(level,"Sections");
            return level;
        }
        if(!root.contains("xPos",3) || !root.contains("zPos",3) || (root.contains("yPos")&&!root.contains("yPos",3)) || !root.contains("sections",9) ||
                root.contains("Entities") || root.contains("TileEntities") || root.contains("Sections"))
            throw new IllegalStateException("Missing or ambiguous flattened terrain coordinates/schema");
        compounds(root,"sections");compounds(root,"entities");compounds(root,"block_entities");
        ListTag sections=root.getList("sections",10);
        for(Tag value:sections) {
            CompoundTag section=(CompoundTag)value;
            // Native height conversion retains stored byte Ys and creates int
            // Ys for new sections. Reject other types and byte-range overflow.
            if((!section.contains("Y",1)&&!section.contains("Y",3)) || section.getInt("Y") < -128 || section.getInt("Y") > 127 || (section.contains("block_states")&&!section.contains("block_states",10)) ||
                    (section.contains("biomes")&&!section.contains("biomes",10)))
                throw new IllegalStateException("Unreadable flattened terrain section");
        }
        flattenedVersion(root);
        return root;
    }

    static String entityKey(CompoundTag root) { return root.contains("Level",10)?"Entities":"entities"; }

    static int flattenedVersion(CompoundTag root) {
        int stamp=root.contains("DataVersion",3)?root.getInt("DataVersion"):-1;
        if(stamp>UpgradePreservation.MINECRAFT_VERSION)throw new IllegalStateException("Terrain was saved by a newer Minecraft version");
        // Valid root coordinates/sections prove the 2842 schema minimum,
        // not the current game release. Let genuine later native fixes run.
        return Math.max(stamp,FLATTENED_SCHEMA);
    }

    private static void compounds(CompoundTag parent,String key) {
        if(!parent.contains(key))return;
        Tag value=parent.get(key);
        if(!(value instanceof ListTag) || (((ListTag)value).size()>0 && ((ListTag)value).getElementType()!=10))
            throw new IllegalStateException("Unreadable terrain list: "+key);
    }
}
