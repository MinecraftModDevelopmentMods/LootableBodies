package cyano.lootable.buildtest;
/** Compare native3D values directly; sample old2D columns at vanilla quart centres. */
public final class HistoricalBiomes {
    private HistoricalBiomes() { }
    public static int[] nativeValues(int[] source) {
        if(source.length==1024)return source.clone();
        if(source.length!=256)throw new IllegalArgumentException("Malformed historical biome source shape");
        int[] expected=new int[1024];
        for(int y=0;y<64;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)
            expected[y*16+z*4+x]=source[(z*4+2)*16+x*4+2];
        return expected;
    }
}
