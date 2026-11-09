package cyano.lootable.upgrade;

import static cyano.lootable.upgrade.NativeNbt.hasType;
import static cyano.lootable.upgrade.NativeNbt.typedList;
import static cyano.lootable.upgrade.NativeNbt.isListOf;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Distinguish native pre-1.18 Level payloads from the flattened 1.18 schema. */
final class TerrainChunkData {
    static final int FLATTENED_SCHEMA=2842;
    private TerrainChunkData() { }

    static CompoundTag payload(CompoundTag root) {
        if(root.contains("Level")) {
            if(!hasType(root, "Level", 10) || root.contains("xPos") || root.contains("zPos") ||
                    root.contains("sections") || root.contains("entities") || root.contains("block_entities"))
                throw new IllegalStateException("Ambiguous legacy and flattened terrain payload");
            CompoundTag level=root.getCompoundOrEmpty("Level");
            compounds(level,"Entities");compounds(level,"TileEntities");compounds(level,"Sections");
            return level;
        }
        if(!hasType(root, "xPos", 3) || !hasType(root, "zPos", 3) || (root.contains("yPos")&&!hasType(root, "yPos", 3)) || !hasType(root, "sections", 9) ||
                root.contains("Entities") || root.contains("TileEntities") || root.contains("Sections"))
            throw new IllegalStateException("Missing or ambiguous flattened terrain coordinates/schema");
        compounds(root,"sections");compounds(root,"entities");compounds(root,"block_entities");
        ListTag sections=typedList(root, "sections", 10);
        for(Tag value:sections) {
            CompoundTag section=(CompoundTag)value;
            // Native height conversion retains stored byte Ys and creates int
            // Ys for new sections. Reject other types and byte-range overflow.
            if((!hasType(section, "Y", 1)&&!hasType(section, "Y", 3)) || section.getIntOr("Y", 0) < -128 || section.getIntOr("Y", 0) > 127 || (section.contains("block_states")&&!hasType(section, "block_states", 10)) ||
                    (section.contains("biomes")&&!hasType(section, "biomes", 10)))
                throw new IllegalStateException("Unreadable flattened terrain section");
        }
        flattenedVersion(root);
        return root;
    }

    static String entityKey(CompoundTag root) { return hasType(root, "Level", 10)?"Entities":"entities"; }

    static int flattenedVersion(CompoundTag root) {
        int stamp=hasType(root, "DataVersion", 3)?root.getIntOr("DataVersion", 0):-1;
        if(stamp>UpgradePreservation.MINECRAFT_VERSION)throw new IllegalStateException("Terrain was saved by a newer Minecraft version");
        // Valid root coordinates/sections prove the 2842 schema minimum,
        // not the current game release. Let genuine later native fixes run.
        return Math.max(stamp,FLATTENED_SCHEMA);
    }

    private static void compounds(CompoundTag parent,String key) {
        if(!parent.contains(key))return;
        Tag value=parent.get(key);
        if(!isListOf(value, 10))
            throw new IllegalStateException("Unreadable terrain list: "+key);
    }
}
