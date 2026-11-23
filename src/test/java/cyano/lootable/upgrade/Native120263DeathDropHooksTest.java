package cyano.lootable.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import static cyano.lootable.upgrade.Native119CoremodEngineTest.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AnnotationNode;

/** Pin both actual native66 admission sites; packaged death tests prove conservation. */
class Native120263DeathDropHooksTest {
    private static Object value(AnnotationNode node,String key) {
        for(int i=0;i<node.values.size();i+=2)if(node.values.get(i).equals(key))return node.values.get(i+1);
        throw new AssertionError("Missing native drop redirect value: "+key);
    }
    private void hook(String nativeOwner,String nativeDescriptor,String mixinName,String handler) throws Exception {
        var nativeMethod=method(classFile(nativeOwner),"dropAll",nativeDescriptor);
        requiredCall(nativeMethod,"net/minecraft/world/level/Level","addFreshEntity");
        var redirected=named(mixin(mixinName,nativeOwner),handler);
        List<AnnotationNode> annotations=new ArrayList<>();
        if(redirected.visibleAnnotations!=null)annotations.addAll(redirected.visibleAnnotations);
        if(redirected.invisibleAnnotations!=null)annotations.addAll(redirected.invisibleAnnotations);
        var redirect=annotations.stream().filter(a->a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
        assertEquals(List.of("dropAll"+nativeDescriptor),value(redirect,"method"));
        assertEquals(1,value(redirect,"require"));
        AnnotationNode at=(AnnotationNode)value(redirect,"at");
        assertEquals("INVOKE",value(at,"value"));
        assertEquals("Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",value(at,"target"));
        requiredCall(redirected,"cyano/lootable/events/DeathDropCapture","addOrCapture");
    }
    @Test void nativeInventoryAdmissionMustReachTheActivePlayerDropCapture() throws Exception {
        hook("net/minecraft/world/entity/player/Inventory","()V","InventoryDeathDropMixin","captureDeathInventoryDrops");
    }
    @Test void nativeEquipmentAdmissionMustCarryItsActualOwnerIntoTheCapture() throws Exception {
        hook("net/minecraft/world/entity/EntityEquipment","(Lnet/minecraft/world/entity/LivingEntity;)V","EquipmentDeathDropMixin","captureDeathEquipmentDrops");
    }
    @Test void bothHooksAreRequiredInThePackagedCommonConfiguration() throws Exception {
        try(var input=getClass().getClassLoader().getResourceAsStream("lootablebodies.mixins.json")) {
            var config=JsonParser.parseReader(new InputStreamReader(input)).getAsJsonObject();
            var names=config.getAsJsonArray("mixins").asList().stream().map(v->v.getAsString()).toList();
            assertTrue(config.get("required").getAsBoolean());
            assertTrue(names.containsAll(List.of("InventoryDeathDropMixin","EquipmentDeathDropMixin")),names.toString());
        }
    }
}
