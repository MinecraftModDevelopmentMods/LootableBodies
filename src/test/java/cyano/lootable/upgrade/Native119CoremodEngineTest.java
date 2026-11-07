package cyano.lootable.upgrade;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Use the actual target CoreMods engine and its Java class-access filter. */
class Native119CoremodEngineTest {
    static void blackboardFixture()throws Exception {
        Class<?> launcher=Class.forName("cpw.mods.modlauncher.Launcher");
        if(launcher.getField("INSTANCE").get(null)==null) {
            // Plain JUnit is not a ModLauncher module layer. The target engine
            // only needs its native blackboard for one compatibility flag.
            // Seed that fixture without running launcher services or any game.
            Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);
            Object fixture=unsafeType.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),launcher);
            Field blackboard=launcher.getDeclaredField("blackboard");blackboard.setAccessible(true);
            blackboard.set(fixture,Class.forName("cpw.mods.modlauncher.api.TypesafeMap").getConstructor().newInstance());
            launcher.getField("INSTANCE").set(null,fixture);
        }
    }
    static ScriptEngine actualEngine()throws Exception {
        blackboardFixture();
        Class<?> factory=Class.forName("net.minecraftforge.coremod.NashornFactory");
        String source=factory.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(source.endsWith("coremods-5.2.6.jar"),source);System.out.println("COREMOD_ENGINE_SOURCE="+source);
        Method create=factory.getDeclaredMethod("createEngine");create.setAccessible(true);
        return (ScriptEngine)create.invoke(null);
    }
    private Object actualTransformer(String className)throws Exception {
        blackboardFixture();Class<?> type=Class.forName("net.minecraftforge.coremod.CoreModEngine");Object engine=type.getConstructor().newInstance();
        Method load=java.util.Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().equals("loadCoreMod")).findFirst().orElseThrow();load.setAccessible(true);
        Class<?> api=load.getParameterTypes()[0];java.util.List<java.io.Reader> readers=new java.util.ArrayList<>();
        Object file=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args)->{
            switch(method.getName()) {
            case "getPath": return Path.of("coremods/early-world-preservation.js");
            case "getOwnerId": return "lootablebodies";
            case "readCoreMod":
                java.io.Reader reader=new InputStreamReader(getClass().getClassLoader().getResourceAsStream("coremods/early-world-preservation.js"),StandardCharsets.UTF_8);readers.add(reader);return reader;
            case "toString": return "Local production coremod fixture";
            default: throw new AssertionError("Unexpected coremod file API: "+method);
            }
        });
        try {
            load.invoke(engine,file);List<?> transforms=(List<?>)type.getMethod("initializeCoreMods").invoke(engine);
            assertEquals(3,transforms.size(),"Actual native engine must install the three shipped transformers");
            return transforms.stream().map(item->(cpw.mods.modlauncher.api.ITransformer<?>)item)
                    .filter(item->item.targets().stream().anyMatch(target->target.getClassName().replace('/','.').equals(className)))
                    .findFirst().orElseThrow();
        } finally {for(var reader:readers)reader.close();}
    }
    private static final String READER="net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess";
    private static final String FIXER="net.minecraft.util.datafix.DataFixers";
    private static final String DEDICATED_MAIN="net.minecraft.server.Main";

    private ClassNode nativeClass(String name)throws Exception {
        ClassNode node=new ClassNode(Opcodes.ASM9);
        try(var input=getClass().getClassLoader().getResourceAsStream(name.replace('.','/')+".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node,0);
        }
        return node;
    }
    private ClassNode transform(Object transformer,ClassNode node)throws Exception {
        Method apply=transformer.getClass().getMethod("transform",Object.class,Class.forName("cpw.mods.modlauncher.api.ITransformerVotingContext"));
        return (ClassNode)apply.invoke(transformer,node,null);
    }
    private Class<?> define(ClassNode node) {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);byte[] bytes=writer.toByteArray();
        class ReaderLoader extends ClassLoader {ReaderLoader(){super(Native119CoremodEngineTest.class.getClassLoader());}Class<?> define(){return defineClass(node.name.replace('/','.'),bytes,0,bytes.length);}}
        return new ReaderLoader().define();
    }
    private void verify(Class<?> reader)throws Exception {
        Method verify=EarlyWorldPreservation.class.getDeclaredMethod("verifyAttachment",Class.class);verify.setAccessible(true);verify.invoke(null,reader);
    }
    @Test void productionScriptInitializesUnderTheActualForgeClassFilter()throws Exception {
        ScriptEngine engine=actualEngine();
        try(var input=getClass().getClassLoader().getResourceAsStream("coremods/early-world-preservation.js")) {
            assertNotNull(input);engine.eval(new InputStreamReader(input,StandardCharsets.UTF_8));
        }
        assertNotNull(((Invocable)engine).invokeFunction("initializeCoreMod"));
    }
    @Test void nativeEngineTransformsActualReaderAndItsAttachmentIsVerifiable()throws Exception {
        ClassNode transformed=transform(actualTransformer(READER),nativeClass(READER));
        verify(define(transformed));
    }
    @Test void nativeEngineWrapsTheDataFixerAccessorExactlyOnce()throws Exception {
        Object transformer=actualTransformer(FIXER);
        ClassNode result=transform(transformer,nativeClass(FIXER));
        result=transform(transformer,result);
        MethodNode accessor=result.methods.stream().filter(method->method.name.equals("getDataFixer")
                && method.desc.equals("()Lcom/mojang/datafixers/DataFixer;")).findFirst().orElseThrow();
        int calls=0;
        for(AbstractInsnNode instruction:accessor.instructions) {
            if(instruction instanceof MethodInsnNode invoke && invoke.owner.equals("cyano/lootable/upgrade/UpgradePreservation")) {
                assertEquals("wrapper",invoke.name);
                assertEquals("(Lcom/mojang/datafixers/DataFixer;)Lcom/mojang/datafixers/DataFixer;",invoke.desc);
                assertEquals(Opcodes.ARETURN,invoke.getNext().getOpcode());
                calls++;
            }
        }
        assertEquals(1,calls);
    }
    @Test void nativeDedicatedReaderGuardsBothHeaderReads()throws Exception {
        Object transformer=actualTransformer(DEDICATED_MAIN);
        ClassNode result=transform(transformer,nativeClass(DEDICATED_MAIN));
        result=transform(transformer,result);
        MethodNode main=result.methods.stream().filter(method->method.name.equals("main")
                && method.desc.equals("([Ljava/lang/String;)V")).findFirst().orElseThrow();
        java.util.ArrayList<Integer> guardedReads=new java.util.ArrayList<>();
        for(AbstractInsnNode instruction:main.instructions) {
            if(instruction instanceof MethodInsnNode invoke && invoke.owner.equals("cyano/lootable/upgrade/EarlyWorldPreservation")) {
                assertEquals("readDedicatedWorld",invoke.name);
                assertEquals(Opcodes.INVOKESTATIC,invoke.getOpcode());
                guardedReads.add(invoke.getPrevious().getOpcode());
            }
        }
        assertEquals(List.of(Opcodes.ICONST_0,Opcodes.ICONST_1),guardedReads);
        define(result);
    }
    @Test void serialSetupRefusesAnUntransformedReaderBeforeAnyWorldCanOpen()throws Exception {
        Field installed=EarlyWorldPreservation.class.getDeclaredField("installed");installed.setAccessible(true);boolean before=installed.getBoolean(null);
        try {installed.setBoolean(null,false);assertThrows(IllegalStateException.class,EarlyWorldPreservation::install);assertFalse(installed.getBoolean(null));}
        finally {installed.setBoolean(null,before);}
    }
    @Test void nativeWrapperCannotTurnAFailedTransformIntoAnAcceptedAttachment()throws Exception {
        ClassNode node=nativeClass(READER);Object transformer=actualTransformer(READER);
        for(MethodNode method:node.methods)for(AbstractInsnNode i=method.instructions.getFirst();i!=null;){AbstractInsnNode next=i.getNext();if(i instanceof MethodInsnNode&&((MethodInsnNode)i).owner.equals("net/minecraftforge/common/ForgeHooks")&&((MethodInsnNode)i).name.equals("writeAdditionalLevelSaveData")){method.instructions.insertBefore(i,new InsnNode(Opcodes.POP2));method.instructions.remove(i);}i=next;}
        ClassNode returned=transform(transformer,node);
        InvocationTargetException refused=assertThrows(InvocationTargetException.class,()->verify(define(returned)));
        assertInstanceOf(IllegalStateException.class,refused.getCause());
    }
}
