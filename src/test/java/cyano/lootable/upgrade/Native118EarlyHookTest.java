package cyano.lootable.upgrade;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class Native118EarlyHookTest {
    private static final String SAVE = "net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
    private static final String HOOK = "cyano/lootable/upgrade/EarlyWorldPreservation";

    @Test void requiredSaveHooksUseTheActualEarlyReadAndHeaderWriteSeams() throws Exception {
        var nativeClass = Native119CoremodEngineTest.classFile(SAVE);
        var mixin = Native119CoremodEngineTest.mixin("SaveAccessMixin", SAVE);
        assertTrue(mixin.interfaces.contains(HOOK+"$Attached"));
        Native119CoremodEngineTest.method(nativeClass,"getDataTag","(Z)Lcom/mojang/serialization/Dynamic;");
        var write = Native119CoremodEngineTest.method(nativeClass, "saveDataTag",
                "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V");
        assertEquals(1, Native119CoremodEngineTest.calls(write, "net/minecraftforge/common/ForgeHooks", "writeAdditionalLevelSaveData"));
        assertEquals(1, Native119CoremodEngineTest.calls(Native119CoremodEngineTest.named(mixin,"preserveBeforeRead"), HOOK, "beforeRead"));
        assertEquals(1, Native119CoremodEngineTest.calls(Native119CoremodEngineTest.named(mixin,"preserveBeforeWrite"), HOOK, "beforeWrite"));
    }

    @Test void missingNativeWriteSeamIsRejectedByTheLayoutContract() throws Exception {
        var nativeClass = Native119CoremodEngineTest.classFile(SAVE);
        var write = Native119CoremodEngineTest.method(nativeClass, "saveDataTag",
                "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V");
        for (var instruction : write.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraftforge/common/ForgeHooks")
                    && call.name.equals("writeAdditionalLevelSaveData")) write.instructions.remove(instruction);
        }
        assertThrows(AssertionError.class, () -> Native119CoremodEngineTest.requiredCall(
                write, "net/minecraftforge/common/ForgeHooks", "writeAdditionalLevelSaveData"));
    }

    @Test void attachmentOfADifferentClassCannotAuthorizeWorldReads() throws Exception {
        var verifier = EarlyWorldPreservation.class.getDeclaredMethod("verifyAttachment",Class.class);
        verifier.setAccessible(true);
        class DifferentReader implements EarlyWorldPreservation.Attached { }
        assertInstanceOf(IllegalStateException.class, assertThrows(InvocationTargetException.class,
                () -> verifier.invoke(null, DifferentReader.class)).getCause());
    }
}
