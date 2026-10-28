package cyano.lootable.buildtest;
/** The two actual sugar unlock identities written by the historical fixtures. */
public final class HistoricalUnlocks {
    private HistoricalUnlocks() { }
    public static boolean sugarPair(String recipe,String advancement) {
        return ("minecraft:sugar".equals(recipe) && "minecraft:recipes/misc/sugar".equals(advancement))
            || ("minecraft:sugar_from_sugar_cane".equals(recipe) && "minecraft:recipes/misc/sugar_from_sugar_cane".equals(advancement));
    }
}
