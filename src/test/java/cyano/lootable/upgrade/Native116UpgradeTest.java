package cyano.lootable.upgrade;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;
import static cyano.lootable.buildtest.TestNativeNbt.readUuid;
import static cyano.lootable.buildtest.TestNativeNbt.hasUuid;

import com.mojang.serialization.Dynamic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.DataFixers;
import com.mojang.datafixers.DataFixer;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native UUID and world-settings preservation, including damaged save stamps. */
class Native116UpgradeTest {
    private static final UUID CORPSE=UUID.fromString("00000000-1111-4111-8111-000000000001");
    private CompoundTag settings() throws Exception {
        return TagParser.parseCompoundFully(new String(Files.readAllBytes(
            Paths.get("src/test/resources/native116-worldgen.snbt")),StandardCharsets.UTF_8));
    }
    private CompoundTag legacy() {
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");
        body.putInt(UpgradePreservation.VERSION,5);
        body.putLong("UUIDMost",CORPSE.getMostSignificantBits());body.putLong("UUIDLeast",CORPSE.getLeastSignificantBits());
        ListTag inventory=new ListTag();
        for(int slot=0;slot<48;slot++) {
            CompoundTag item=new CompoundTag();item.putByte("Slot",(byte)slot);item.putString("id","minecraft:diamond");
            item.putByte("Count",(byte)(slot+1));CompoundTag tag=new CompoundTag();tag.putLong("typed",1234567890123L+slot);
            item.put("tag",tag);inventory.add(item);
        }
        body.put("Equipment",inventory);return body;
    }
    @Test void currentFormatUsesThe119SchemaAndMarker() {
        assertEquals(4790,UpgradePreservation.MINECRAFT_VERSION);assertEquals(14,UpgradePreservation.DATA_VERSION);
    }
    @Test void legacyCorpseUuidSurvivesShieldingWithoutTruncatingCustomInventory() {
        CompoundTag body=legacy(),before=body.copy(),context=new CompoundTag();context.putInt("DataVersion",2230);
        CompoundTag fixed=NativeSafeFixer.stageCorpse(body,context);
        assertTrue(hasUuid(fixed, "UUID"));assertEquals(CORPSE,readUuid(fixed, "UUID"));
        assertEquals(48,typedList(fixed, "LootableBodiesUnresolved", 10).size());
        for(int slot=0;slot<48;slot++)assertEquals(typedList(body, "Equipment", 10).getCompoundOrEmpty(slot),
            typedList(fixed, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(slot).getCompoundOrEmpty("Data"));
        assertEquals(before,body);
    }
    @Test void released115MarkerKeepsItsOwnItemContextUnderAnOlderOuterStamp() {
        CompoundTag body=legacy(),before=body.copy();
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(DataFixers.getDataFixer()),
            References.ENTITY_TREE,body,512,2586);
        assertEquals(2230,typedList(fixed, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(0)
            .getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion", 0));
        assertEquals(before,body);
    }
    @Test void actualNativeCurrentSettingsSurviveOldAndMissingStampContexts() throws Exception {
        for(int source:new int[]{-1,0,512,922,1343,1631,1976,2230,2586}) {
            CompoundTag raw=settings(),before=raw.copy();
            CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(DataFixers.getDataFixer()),
                References.WORLD_GEN_SETTINGS,raw,source,2586);
            assertEquals(before,fixed,"Source version "+source);assertEquals(before,raw);
            assertEquals(4267321L,fixed.getLongOr("seed", 0L));
            assertEquals("minecraft:flat",fixed.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld")
                .getCompoundOrEmpty("generator").getStringOr("type", ""));
        }
    }
    @Test void nonSingletonNbtOpsArePreservedWithCurrentWorldSettings() throws Exception {
        var ops=cyano.lootable.buildtest.Native119Registries.lookup().createSerializationContext(NbtOps.INSTANCE);CompoundTag raw=settings(),before=raw.copy();
        Dynamic<Tag> result=UpgradePreservation.wrapper(DataFixers.getDataFixer()).update(
            References.WORLD_GEN_SETTINGS,new Dynamic<Tag>(ops,raw),-1,2586);
        assertSame(ops,result.getOps());assertEquals(before,result.getValue());assertEquals(before,raw);
    }
    @Test void levelEnvelopeProtectsCurrentSettingsWithoutMutatingTheOriginal() throws Exception {
        CompoundTag root=new CompoundTag();root.putString("LevelName","Disposable fixture");
        root.put("WorldGenSettings",settings());CompoundTag before=root.copy();
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(DataFixers.getDataFixer()),
            References.LEVEL,root,-1,2586);
        assertEquals(before.get("WorldGenSettings"),fixed.get("WorldGenSettings"));assertEquals(before,root);
    }
    @Test void nativeAccessorWrapperProtectsLevelSettingsBeforeServerCreation() throws Exception {
        DataFixer nativeFixer=DataFixers.getDataFixer();
        UpgradePreservation.register(nativeFixer);
        CompoundTag raw=settings(),before=raw.copy();
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(nativeFixer),
            References.WORLD_GEN_SETTINGS,raw,-1,2586);
        assertEquals(before,fixed,"The world reader obtains its fixer before server-about-to-start");
        assertEquals(before,raw);
    }
    @Test void nativeLoadingModListSuppliesChunkContextWhenStampsAreDamaged() {
        CompoundTag versions=new CompoundTag(),fml=new CompoundTag(),mod=new CompoundTag();
        mod.putString("ModId","minecraft");mod.putString("ModVersion","1.15.2");
        ListTag mods=new ListTag();mods.add(mod);fml.put("LoadingModList",mods);versions.put("WorldFML",fml);
        versions.putInt("DataVersion",922);versions.putString("ChunkDataVersion","damaged");
        CompoundTag before=versions.copy();assertEquals(2230,UpgradePreservation.chunkSourceVersion(versions));assertEquals(before,versions);
    }
}
