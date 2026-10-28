package cyano.lootable.upgrade;

import com.mojang.serialization.Dynamic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.datafix.TypeReferences;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native UUID and world-settings preservation, including damaged save stamps. */
class Native116UpgradeTest {
    private static final UUID CORPSE=UUID.fromString("00000000-1111-4111-8111-000000000001");
    private CompoundNBT settings() throws Exception {
        return JsonToNBT.getTagFromJson(new String(Files.readAllBytes(
            Paths.get("src/test/resources/native116-worldgen.snbt")),StandardCharsets.UTF_8));
    }
    private CompoundNBT legacy() {
        CompoundNBT body=new CompoundNBT();body.putString("id","lootablebodies:corpse");
        body.putInt(UpgradePreservation.VERSION,5);
        body.putLong("UUIDMost",CORPSE.getMostSignificantBits());body.putLong("UUIDLeast",CORPSE.getLeastSignificantBits());
        ListNBT inventory=new ListNBT();
        for(int slot=0;slot<48;slot++) {
            CompoundNBT item=new CompoundNBT();item.putByte("Slot",(byte)slot);item.putString("id","minecraft:diamond");
            item.putByte("Count",(byte)(slot+1));CompoundNBT tag=new CompoundNBT();tag.putLong("typed",1234567890123L+slot);
            item.put("tag",tag);inventory.add(item);
        }
        body.put("Equipment",inventory);return body;
    }
    @Test void currentFormatUsesThe116SchemaAndMarker() {
        assertEquals(2586,UpgradePreservation.MINECRAFT_VERSION);assertEquals(6,UpgradePreservation.DATA_VERSION);
    }
    @Test void legacyCorpseUuidSurvivesShieldingWithoutTruncatingCustomInventory() {
        CompoundNBT body=legacy(),before=body.copy(),context=new CompoundNBT();context.putInt("DataVersion",2230);
        CompoundNBT fixed=NativeSafeFixer.stageCorpse(body,context);
        assertTrue(fixed.hasUniqueId("UUID"));assertEquals(CORPSE,fixed.getUniqueId("UUID"));
        assertEquals(48,fixed.getList("LootableBodiesUnresolved",10).size());
        for(int slot=0;slot<48;slot++)assertEquals(body.getList("Equipment",10).getCompound(slot),
            fixed.getList("LootableBodiesUnresolved",10).getCompound(slot).getCompound("Data"));
        assertEquals(before,body);
    }
    @Test void released115MarkerKeepsItsOwnItemContextUnderAnOlderOuterStamp() {
        CompoundNBT body=legacy(),before=body.copy();
        CompoundNBT fixed=NativeNbt.update(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()),
            TypeReferences.ENTITY_TYPE,body,512,2586);
        assertEquals(2230,fixed.getList("LootableBodiesUnresolved",10).getCompound(0)
            .getCompound("SourceVersions").getInt("DataVersion"));
        assertEquals(before,body);
    }
    @Test void actualNativeCurrentSettingsSurviveOldAndMissingStampContexts() throws Exception {
        for(int source:new int[]{-1,0,512,922,1343,1631,1976,2230,2586}) {
            CompoundNBT raw=settings(),before=raw.copy();
            CompoundNBT fixed=NativeNbt.update(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()),
                TypeReferences.WORLD_GEN_SETTINGS,raw,source,2586);
            assertEquals(before,fixed,"Source version "+source);assertEquals(before,raw);
            assertEquals(4267321L,fixed.getLong("seed"));
            assertEquals("minecraft:flat",fixed.getCompound("dimensions").getCompound("minecraft:overworld")
                .getCompound("generator").getString("type"));
        }
    }
    @Test void nonSingletonNbtOpsArePreservedWithCurrentWorldSettings() throws Exception {
        NBTDynamicOps ops=new NBTDynamicOps(){};CompoundNBT raw=settings(),before=raw.copy();
        Dynamic<INBT> result=UpgradePreservation.wrapper(DataFixesManager.getDataFixer()).update(
            TypeReferences.WORLD_GEN_SETTINGS,new Dynamic<INBT>(ops,raw),-1,2586);
        assertSame(ops,result.getOps());assertEquals(before,result.getValue());assertEquals(before,raw);
    }
    @Test void levelEnvelopeProtectsCurrentSettingsWithoutMutatingTheOriginal() throws Exception {
        CompoundNBT root=new CompoundNBT();root.putString("LevelName","Disposable fixture");
        root.put("WorldGenSettings",settings());CompoundNBT before=root.copy();
        CompoundNBT fixed=NativeNbt.update(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()),
            net.minecraft.util.datafix.DefaultTypeReferences.LEVEL.getTypeReference(),root,-1,2586);
        assertEquals(before.get("WorldGenSettings"),fixed.get("WorldGenSettings"));assertEquals(before,root);
    }
    @Test void registrationProtectsTheNativeLevelReaderBeforeServerCreation() throws Exception {
        UpgradePreservation.register(DataFixesManager.getDataFixer());
        CompoundNBT raw=settings(),before=raw.copy();
        CompoundNBT fixed=NativeNbt.update(DataFixesManager.getDataFixer(),
            TypeReferences.WORLD_GEN_SETTINGS,raw,-1,2586);
        assertEquals(before,fixed,"The world reader obtains its fixer before server-about-to-start");
        assertEquals(before,raw);
    }
    @Test void nativeLoadingModListSuppliesChunkContextWhenStampsAreDamaged() {
        CompoundNBT versions=new CompoundNBT(),fml=new CompoundNBT(),mod=new CompoundNBT();
        mod.putString("ModId","minecraft");mod.putString("ModVersion","1.15.2");
        ListNBT mods=new ListNBT();mods.add(mod);fml.put("LoadingModList",mods);versions.put("WorldFML",fml);
        versions.putInt("DataVersion",922);versions.putString("ChunkDataVersion","damaged");
        CompoundNBT before=versions.copy();assertEquals(2230,UpgradePreservation.chunkSourceVersion(versions));assertEquals(before,versions);
    }
}
