package cyano.lootable.buildtest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HistoricalUnlocksTest {
    @Test void oldAndAlreadyRenamedNativeSugarUnlocksAreRecognised(){assertTrue(HistoricalUnlocks.sugarPair("minecraft:sugar","minecraft:recipes/misc/sugar"));assertTrue(HistoricalUnlocks.sugarPair("minecraft:sugar_from_sugar_cane","minecraft:recipes/misc/sugar_from_sugar_cane"));}
    @Test void crossedOrUnrelatedFixtureIdentitiesAreRejected(){assertFalse(HistoricalUnlocks.sugarPair("minecraft:sugar","minecraft:recipes/misc/sugar_from_sugar_cane"));assertFalse(HistoricalUnlocks.sugarPair("other:sugar","minecraft:recipes/misc/sugar"));assertFalse(HistoricalUnlocks.sugarPair(null,null));}
}
