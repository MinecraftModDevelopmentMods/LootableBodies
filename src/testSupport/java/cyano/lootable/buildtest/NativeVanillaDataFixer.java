package cyano.lootable.buildtest;
import com.mojang.datafixers.DataFixer;
import java.lang.reflect.Field;
/** Independent native oracle, separate from the mod's early global protection. */
public final class NativeVanillaDataFixer {
    private NativeVanillaDataFixer() { }
    private static DataFixer captured;
    public static synchronized DataFixer get() {
        if(captured!=null)return captured;
        DataFixer result=unwrap(net.minecraft.util.datafix.DataFixesManager.getDataFixer());
        if(!result.getClass().getName().equals("com.mojang.datafixers.DataFixerUpper"))throw new AssertionError("Historical oracle is not the native fixer");
        // Keep this independent reference when a synthetic counter later wraps
        // the global reader. Never accept that test wrapper as the oracle.
        captured=result;return result;
    }
    public static DataFixer unwrap(DataFixer input) {
        for(int depth=0;depth<32;depth++) {
            if(!input.getClass().getName().equals("cyano.lootable.upgrade.NativeSafeFixer"))return input;
            Field found=null;
            for(Field field:input.getClass().getDeclaredFields())if(field.getType()==DataFixer.class) {
                if(found!=null)throw new AssertionError("Ambiguous oracle delegate");found=field;
            }
            if(found==null)throw new AssertionError("Missing oracle delegate");
            try {found.setAccessible(true);input=(DataFixer)found.get(input);}
            catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
        throw new AssertionError("Recursive oracle delegate");
    }
}
