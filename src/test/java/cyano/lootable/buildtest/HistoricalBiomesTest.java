package cyano.lootable.buildtest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HistoricalBiomesTest {
    @Test void native115ValuesKeepAll1024EntriesAndTheirOrder(){int[] source=new int[1024];for(int i=0;i<source.length;i++)source[i]=i;int[] actual=HistoricalBiomes.nativeValues(source);assertArrayEquals(source,actual);assertNotSame(source,actual);actual[0]=99;assertEquals(0,source[0]);}
    @Test void olderTwoDimensionalValuesUseNativeQuartCentres(){int[] source=new int[256];for(int i=0;i<source.length;i++)source[i]=i;int[] actual=HistoricalBiomes.nativeValues(source);assertEquals(1024,actual.length);for(int y=0;y<64;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)assertEquals((z*4+2)*16+x*4+2,actual[y*16+z*4+x]);}
    @Test void malformedSourceShapesCannotPretendToBeNative(){for(int length:new int[]{0,255,257,1023,1025})assertThrows(IllegalArgumentException.class,()->HistoricalBiomes.nativeValues(new int[length]));}
}
