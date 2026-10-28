package cyano.lootable.buildtest;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalVersionsTest {
    @Test void earlierOriginsKeepTheirActualNativeSchemas() {
        assertEquals(512, HistoricalVersions.dataVersion("1.10.2"));
        assertEquals(922, HistoricalVersions.dataVersion("1.11.2"));
        assertEquals(1343, HistoricalVersions.dataVersion("1.12.2"));
        assertEquals(1631, HistoricalVersions.dataVersion("1.13.2"));
    }
    @Test void released114InventoryUses1976RatherThan111Context() {
        assertEquals(1976, HistoricalVersions.dataVersion("1.14.4"));
    }
    @Test void released115InventoryUses2230RatherThanAnOlderSchema() {
        assertEquals(2230,HistoricalVersions.dataVersion("1.15.2"));
    }
    @Test void missingOrUnsupportedFixtureFamiliesCannotPretendToBe111() {
        assertThrows(IllegalArgumentException.class, () -> HistoricalVersions.dataVersion(null));
        assertThrows(IllegalArgumentException.class, () -> HistoricalVersions.dataVersion(""));
        assertThrows(IllegalArgumentException.class, () -> HistoricalVersions.dataVersion("1.99.2"));
    }
}
