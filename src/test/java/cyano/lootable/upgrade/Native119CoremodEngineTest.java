package cyano.lootable.upgrade;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** Forge uses required Mixins. Packaged runs separately exercise their actual application. */
class Native119CoremodEngineTest {
    static ClassNode classFile(String name) throws Exception {
        try (var input = Native119CoremodEngineTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    static ClassNode mixin(String name, String target) throws Exception {
        ClassNode node = classFile("cyano/lootable/mixin/"+name);
        var declaration = node.invisibleAnnotations.stream()
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                .findFirst().orElseThrow();
        assertTrue(declaration.values.stream().anyMatch(value -> value instanceof java.util.List<?> types
                && types.equals(java.util.List.of(Type.getObjectType(target)))), name);
        return node;
    }

    static MethodNode method(ClassNode node, String name, String descriptor) {
        var methods = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        assertEquals(1, methods.size(), node.name+"."+name+descriptor);
        return methods.getFirst();
    }

    static MethodNode named(ClassNode node, String name) {
        var methods = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        assertEquals(1, methods.size(), node.name+"."+name);
        return methods.getFirst();
    }

    static long calls(MethodNode method, String owner, String name) {
        return Arrays.stream(method.instructions.toArray()).filter(instruction ->
                instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)).count();
    }

    static void requiredCall(MethodNode method, String owner, String name) {
        assertEquals(1, calls(method, owner, name), method.name+" -> "+owner+"."+name);
    }

    @Test void packagedConfigRequiresEveryPreservationHook() throws Exception {
        var loader = getClass().getClassLoader();
        assertNull(loader.getResource("META-INF/coremods.json"));
        try (var input = loader.getResourceAsStream("lootablebodies.mixins.json")) {
            assertNotNull(input);
            var config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.get("required").getAsBoolean());
            assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            assertEquals(java.util.Set.of("DataFixersMixin","SaveAccessMixin","RegionStorageMixin","FileFixerMixin","FileFixLevelDataMixin","DimensionStorageMixin","CapabilityDataMixin"),
                    config.getAsJsonArray("mixins").asList().stream().map(v -> v.getAsString()).collect(java.util.stream.Collectors.toSet()));
            assertEquals("DedicatedWorldMixin", config.getAsJsonArray("server").get(0).getAsString());
            assertEquals(1, config.getAsJsonArray("client").size());
            assertEquals("CorpseRenderDispatcherMixin", config.getAsJsonArray("client").get(0).getAsString());
        }
    }

    @Test void nativeAccessorHasExactlyOneWrapper() throws Exception {
        String target = "net/minecraft/util/datafix/DataFixers";
        method(classFile(target), "getDataFixer", "()Lcom/mojang/datafixers/DataFixer;");
        var mixin = mixin("DataFixersMixin",target);
        requiredCall(named(mixin,"wrapNativeFixer"), "cyano/lootable/upgrade/UpgradePreservation","wrapper");
    }

    @Test void dedicatedReaderGuardsBothActualHeaderReads() throws Exception {
        String target = "net/minecraft/server/Main";
        String save = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
        var entry = method(classFile(target),"main","([Ljava/lang/String;)V");
        String fileFixer = "net/minecraft/util/filefix/FileFixerUpper";
        requiredCall(entry,save,"getUnfixedDataTagWithFallback");
        requiredCall(entry,fileFixer,"fix");
        var mixin = mixin("DedicatedWorldMixin",target);
        requiredCall(named(mixin,"readWorld"),"cyano/lootable/upgrade/EarlyWorldPreservation","readDedicatedWorld");
        requiredCall(named(mixin,"fixWorldFiles"),"cyano/lootable/upgrade/EarlyWorldPreservation","handleDedicatedFileRefusal");
        var layout = mixin("FileFixerMixin",fileFixer);
        assertTrue(layout.interfaces.contains("cyano/lootable/upgrade/EarlyWorldPreservation$LayoutAttached"));
        requiredCall(named(layout,"beforeFileChanges"),"cyano/lootable/upgrade/EarlyWorldPreservation","beforeFileFix");
        requiredCall(named(layout,"afterFileChanges"),"cyano/lootable/upgrade/EarlyWorldPreservation","afterFileFix");
    }

    @Test void regionHookProtectsTheActualVersionShortcut() throws Exception {
        String target = "net/minecraft/world/level/chunk/storage/SimpleRegionStorage";
        method(classFile(target),"upgradeChunkTag","(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;");
        var mixin = mixin("RegionStorageMixin",target);
        assertTrue(mixin.interfaces.contains("cyano/lootable/upgrade/UpgradePreservation$RegionAttached"));
        requiredCall(named(mixin,"preserveRegion"),"cyano/lootable/upgrade/UpgradePreservation","upgradeRegion");
    }

    @Test void capabilitiesHookUsesTheActualDimensionDataReader() throws Exception {
        String target = "net/minecraft/world/level/storage/SavedDataStorage";
        method(classFile(target),"readTagFromDisk","(Ljava/nio/file/Path;Lnet/minecraft/util/datafix/DataFixTypes;I)Lnet/minecraft/nbt/CompoundTag;");
        requiredCall(named(mixin("CapabilityDataMixin",target),"preserveOldCapabilities"),
                "cyano/lootable/upgrade/UpgradePreservation","upgradeLevelCapabilities");
    }

    @Test void corpseSubmissionHookUsesTheNativeStateDispatcher() throws Exception {
        String target = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
        var dispatch = method(classFile(target), "getRenderer",
                "(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;");
        assertTrue(Arrays.stream(dispatch.instructions.toArray()).anyMatch(instruction ->
                instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                        && type.getOpcode() == org.objectweb.asm.Opcodes.INSTANCEOF
                        && type.desc.equals("net/minecraft/client/renderer/entity/state/AvatarRenderState")));
        requiredCall(named(mixin("CorpseRenderDispatcherMixin", target), "useCorpseRenderer"),
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", "setReturnValue");
    }

    @Test void serialSetupRefusesAnUnattachedNativeReaderBeforeAnyWorldCanOpen() throws Exception {
        var installed = EarlyWorldPreservation.class.getDeclaredField("installed");
        installed.setAccessible(true);
        boolean before = installed.getBoolean(null);
        try {
            installed.setBoolean(null,false);
            assertThrows(IllegalStateException.class,EarlyWorldPreservation::install);
            assertFalse(installed.getBoolean(null));
        } finally {
            installed.setBoolean(null,before);
        }
    }
}
