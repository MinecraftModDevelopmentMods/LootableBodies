package cyano.lootable.buildtest;
import com.mojang.datafixers.DataFixer;
import cyano.lootable.upgrade.UpgradePreservation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeVanillaDataFixerTest {
    @Test void independentHistoricalOracleDoesNotUseTheProductionWrapper(){
        DataFixer vanilla=NativeVanillaDataFixer.get();
        assertEquals("com.mojang.datafixers.DataFixerUpper",vanilla.getClass().getName());
        assertSame(vanilla,NativeVanillaDataFixer.unwrap(UpgradePreservation.wrapper(vanilla)));
        assertSame(vanilla,NativeVanillaDataFixer.unwrap(vanilla));
    }
    @Test void capturedVanillaOracleStaysIndependentWhenASyntheticCounterWrapsTheGlobalReader(){
        DataFixer nativeOracle=NativeVanillaDataFixer.get();DataFixer previous=net.minecraft.util.datafix.DataFixers.getDataFixer();
        try {
            net.minecraft.util.datafix.DataFixers.DATA_FIXER=new DataFixer(){
                public com.mojang.datafixers.schemas.Schema getSchema(int key){throw new AssertionError("Counter is not an oracle");}
                public <T>com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.serialization.Dynamic<T> input,int source,int target){throw new AssertionError("Counter is not an oracle");}
            };
            assertSame(nativeOracle,NativeVanillaDataFixer.get());
        }finally{net.minecraft.util.datafix.DataFixers.DATA_FIXER=previous;}
    }
}
