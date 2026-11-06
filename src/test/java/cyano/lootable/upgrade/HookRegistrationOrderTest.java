package cyano.lootable.upgrade;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks native registration after parallel construction has completed. */
class HookRegistrationOrderTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    @Test void serialActivationIsRequiredBeforeReadAndRemainsIdempotent() throws Exception {
        Field installed=EarlyWorldPreservation.class.getDeclaredField("installed");installed.setAccessible(true);
        boolean wasInstalled=installed.getBoolean(null);
        Path world=directory.resolve("serial");Files.createDirectories(world);
        CompoundTag root=new CompoundTag(),data=new CompoundTag(),owned=new CompoundTag();
        data.putInt("DataVersion",2730);root.put("Data",data);owned.putString("unknown","keep");root.put("lootablebodies",owned);
        net.minecraft.nbt.NbtIo.writeCompressed(root,world.resolve("level.dat"));
        byte[] original=Files.readAllBytes(world.resolve("level.dat"));
        try(LevelStorageSource.LevelStorageAccess save=LevelStorageSource.createDefault(directory).createAccess("serial")) {
            installed.setBoolean(null,false);
            Error refused=assertThrows(Error.class,()->EarlyWorldPreservation.beforeRead(save,false));
            assertTrue(refused.getCause().getMessage().contains("not installed"));
            assertArrayEquals(original,Files.readAllBytes(world.resolve("level.dat")));
            assertFalse(Files.exists(world.resolve("data/lootablebodies-upgrade")));
            Native118UnitLifecycle.activate();
            for(int round=0;round<1000;round++){EarlyWorldPreservation.install();EarlyWorldPreservation.install();}
            EarlyWorldPreservation.beforeRead(save,false);
            CompoundTag saved=new CompoundTag();EarlyWorldPreservation.beforeWrite(save,saved);
            assertEquals(owned,saved.getCompound("lootablebodies"));
        } finally {UpgradePreservation.close();installed.setBoolean(null,wasInstalled);}
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
        assertFalse(eager[0],"The early preservation gate must not be activated in parallel mod construction");
        assertTrue(queued[0],"Hook installation must be queued to serial lifecycle work before world loading");
    }
}
