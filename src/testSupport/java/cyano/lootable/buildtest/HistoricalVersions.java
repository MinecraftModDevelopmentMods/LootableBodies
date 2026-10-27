package cyano.lootable.buildtest;

/** Source schema for the sealed historical fixture manifest. */
public final class HistoricalVersions {
    private HistoricalVersions() { }
    public static int dataVersion(String minecraft) {
        if ("1.10.2".equals(minecraft)) return 512;
        if ("1.11.2".equals(minecraft)) return 922;
        if ("1.12.2".equals(minecraft)) return 1343;
        if ("1.13.2".equals(minecraft)) return 1631;
        if ("1.14.4".equals(minecraft)) return 1976;
        throw new IllegalArgumentException("Unsupported historical Minecraft " + minecraft);
    }
}
