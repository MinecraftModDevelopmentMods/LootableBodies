package cyano.lootable.upgrade;
import com.mojang.datafixers.DataFixer;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeFixerInstallationTest {
    static class Parent {private final DataFixer fixer=DataFixers.getDataFixer();DataFixer value(){return fixer;}}
    static class Child extends Parent { }
    static class Duplicate extends Parent {private final DataFixer second=DataFixers.getDataFixer();}
    @Test void wrapsTheUniqueInheritedFixerAndDoesNotDoubleWrap() {
        Child owner=new Child();ChunkVersionContext.installFixer(owner,null);DataFixer fixed=owner.value();assertInstanceOf(NativeSafeFixer.class,fixed);ChunkVersionContext.installFixer(owner,null);assertSame(fixed,owner.value());
    }
    @Test void ambiguousOrMissingNativeFieldsStopBeforeLoading() {
        assertThrows(IllegalStateException.class,()->ChunkVersionContext.installFixer(new Duplicate(),null));
        assertThrows(IllegalStateException.class,()->ChunkVersionContext.installFixer(new Object(),null));
    }
}
