package cyano.lootable.upgrade;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.CheckClassAdapter;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the shipped transformer against actual native18 bytecode. */
class Native118EarlyHookTest {
    private static final String SAVE="net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
    private static final String HOOK="cyano/lootable/upgrade/EarlyWorldPreservation";
    private ScriptEngine engine;
    private Object transformer()throws Exception {
        String path;
        try(InputStream input=getClass().getClassLoader().getResourceAsStream("META-INF/coremods.json")) {
            assertNotNull(input,"Early native18 protection must be attached before native header rewrite");
            com.google.gson.JsonObject config=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.has("lootablebodies_preservation"),"Production early-read attachment is missing");
            path=config.get("lootablebodies_preservation").getAsString();
        }
        engine=Native119CoremodEngineTest.actualEngine();assertNotNull(engine,"Use the actual target Forge JavaScript engine/filter");
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input);engine.eval(new InputStreamReader(input,StandardCharsets.UTF_8));
        }
        Map<?,?> map=(Map<?,?>)((Invocable)engine).invokeFunction("initializeCoreMod");
        Map<?,?> spec=(Map<?,?>)map.get("lootablebodies_preservation");
        Map<?,?> target=(Map<?,?>)spec.get("target");assertEquals("CLASS",target.get("type"));assertEquals(SAVE.replace('/','.'),target.get("name"));
        return spec;
    }
    private ClassNode original()throws Exception {
        ClassNode node=new ClassNode(Opcodes.ASM9);
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(SAVE+".class")) {
            assertNotNull(input);new ClassReader(input).accept(node,0);
        }
        return node;
    }
    private ClassNode apply(Object transformer,ClassNode node)throws Exception {
        return (ClassNode)((Invocable)engine).invokeMethod(transformer,"transformer",node);
    }
    private int calls(ClassNode node,String name) {
        int count=0;for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)
            if(instruction instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)instruction;if(call.owner.equals(HOOK)&&call.name.equals(name))count++;}
        return count;
    }
    @Test void actualNativeClassGetsExactlyOneEarlyReadAndOneHeaderWriteHook()throws Exception {
        ClassNode before=original();Object transformer=transformer();ClassNode after=apply(transformer,before);
        assertEquals(1,calls(after,"beforeRead"));assertEquals(1,calls(after,"beforeWrite"));
        MethodNode read=after.methods.stream().filter(m->m.name.equals("readAdditionalLevelSaveData")&&m.desc.equals("()V")).findFirst().orElseThrow();
        AbstractInsnNode first=read.instructions.getFirst();while(first.getOpcode()<0)first=first.getNext();
        assertEquals(Opcodes.ALOAD,first.getOpcode());assertTrue(first.getNext() instanceof MethodInsnNode);
        assertEquals("beforeRead",((MethodInsnNode)first.getNext()).name);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);after.accept(writer);assertTrue(writer.toByteArray().length>0);
        StringWriter errors=new StringWriter();CheckClassAdapter.verify(new ClassReader(writer.toByteArray()),getClass().getClassLoader(),false,new PrintWriter(errors));
        assertEquals("",errors.toString(),"Actual native bytecode must pass operand-stack verification");
        apply(transformer,after);assertEquals(1,calls(after,"beforeRead"));assertEquals(1,calls(after,"beforeWrite"));
    }
    @Test void missingNativeWriteSeamIsFatalRatherThanAnUnprotectedLaunch()throws Exception {
        Object transformer=transformer();ClassNode node=original();
        for(MethodNode method:node.methods)for(AbstractInsnNode i=method.instructions.getFirst();i!=null;){AbstractInsnNode next=i.getNext();if(i instanceof MethodInsnNode&&((MethodInsnNode)i).owner.equals("net/minecraftforge/common/ForgeHooks")&&((MethodInsnNode)i).name.equals("writeAdditionalLevelSaveData"))method.instructions.remove(i);i=next;}
        assertThrows(Exception.class,()->apply(transformer,node));
    }
    @Test void aDifferentClassCannotBeSilentlyTransformed()throws Exception {
        Object transformer=transformer();ClassNode node=original();node.name="wrong/SaveAccess";
        assertThrows(Exception.class,()->apply(transformer,node));
    }
}
