package cyano.lootable.upgrade;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fmllegacy.WorldPersistenceHooks;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks native registration after parallel construction has completed. */
class HookRegistrationOrderTest {
    private static final class Hook implements WorldPersistenceHooks.WorldPersistenceHook {
        private final String id;
        Hook(String id){this.id=id;}
        public String getModId(){return id;}
        public void readData(LevelStorageSource.LevelStorageAccess save,WorldData info,CompoundTag tag){}
        public CompoundTag getDataForWriting(LevelStorageSource.LevelStorageAccess save,WorldData info){return new CompoundTag();}
    }
    @Test void serialRegistrationKeepsEveryNativeHookAndRemainsIdempotent() throws Exception {
        Field registry=WorldPersistenceHooks.class.getDeclaredField("worldPersistenceHooks");registry.setAccessible(true);
        Field installed=EarlyWorldPreservation.class.getDeclaredField("installed");installed.setAccessible(true);
        Object original=registry.get(null);boolean wasInstalled=installed.getBoolean(null);
        try {
            for(int round=0;round<1000;round++) {
                List<WorldPersistenceHooks.WorldPersistenceHook> hooks=new ArrayList<>();registry.set(null,hooks);installed.setBoolean(null,false);
                // Forge's constructor has finished before deferred lifecycle work.
                WorldPersistenceHooks.addHook(new Hook("forge"));
                WorldPersistenceHooks.addHook(new Hook("fml"));
                EarlyWorldPreservation.install();EarlyWorldPreservation.install();
                assertEquals(3,hooks.size());assertFalse(hooks.contains(null));
                Set<String> ids=new HashSet<>();for(WorldPersistenceHooks.WorldPersistenceHook hook:hooks)ids.add(hook.getModId());
                assertEquals(new HashSet<>(Arrays.asList("forge","fml","lootablebodies")),ids);
                CompoundTag saved=new CompoundTag();WorldPersistenceHooks.handleWorldDataSave(null,null,saved);
                assertEquals(ids,saved.getAllKeys());
            }
        } finally {registry.set(null,original);installed.setBoolean(null,wasInstalled);}
    }
    @Test void theProductionConstructorMustDeferHookRegistrationToSerialLifecycleWork() throws Exception {
        final boolean[] eager={false},queued={false};
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("cyano/lootable/LootableBodies.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM7) {
                public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM7) {
                        boolean installReference,enqueue;
                        public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean face) {
                            if(owner.equals("cyano/lootable/upgrade/EarlyWorldPreservation") && method.equals("install") && name.equals("<init>"))eager[0]=true;
                            if(method.equals("enqueueWork") && owner.startsWith("net/minecraftforge/fml/event/lifecycle/"))enqueue=true;
                        }
                        public void visitInvokeDynamicInsn(String method,String descriptor,Handle bootstrap,Object... arguments) {
                            for(Object argument:arguments)if(argument instanceof Handle) {
                                Handle handle=(Handle)argument;
                                if(handle.getOwner().equals("cyano/lootable/upgrade/EarlyWorldPreservation") && handle.getName().equals("install"))installReference=true;
                            }
                        }
                        public void visitEnd(){if(installReference && enqueue)queued[0]=true;}
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertFalse(eager[0],"The parallel mod constructor mutates Forge's unsynchronised ArrayList alongside ForgeMod");
        assertTrue(queued[0],"Hook installation must be queued to serial lifecycle work before world loading");
    }
}
