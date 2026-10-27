package cyano.lootable.buildtest;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypedNbtTest {
    private CompoundNBT compound(boolean reverse) {
        CompoundNBT value = new CompoundNBT();
        // These names deliberately collide in Java's hash map.
        if (reverse) { value.putInt("BB", 2); value.putInt("Aa", 1); }
        else { value.putInt("Aa", 1); value.putInt("BB", 2); }
        return value;
    }
    @Test void compoundInsertionOrderDoesNotInventAMutation() {
        CompoundNBT left = compound(false), right = compound(true);
        assertEquals(left, right); assertTrue(TypedNbt.same(left, right));
    }
    @Test void compoundOrderInsideListsAlsoDoesNotInventAMutation() {
        ListNBT left = new ListNBT(), right = new ListNBT();
        left.add(compound(false)); right.add(compound(true));
        assertTrue(TypedNbt.same(left, right));
    }
    @Test void listOrderAndNumericTypesStillMatter() {
        ListNBT left = new ListNBT(), right = new ListNBT();
        left.add(IntNBT.valueOf(1)); left.add(IntNBT.valueOf(2));
        right.add(IntNBT.valueOf(2)); right.add(IntNBT.valueOf(1));
        assertFalse(TypedNbt.same(left, right));
        assertFalse(TypedNbt.same(IntNBT.valueOf(7), ByteNBT.valueOf((byte)7)));
    }
    @Test void arraysAndChangedNestedPayloadsStillMatter() {
        assertFalse(TypedNbt.same(new IntArrayNBT(new int[]{1,2}), new IntArrayNBT(new int[]{2,1})));
        CompoundNBT left = compound(false), right = compound(true); right.putInt("Aa", 3);
        assertFalse(TypedNbt.same(left, right));
    }
    @Test void nonFiniteValuesKeepTheirTypesAndSigns() throws Exception {
        assertTrue(TypedNbt.same(DoubleNBT.valueOf(Double.NaN), DoubleNBT.valueOf(Double.NaN)));
        assertFalse(TypedNbt.same(DoubleNBT.valueOf(Double.NaN), FloatNBT.valueOf(Float.NaN)));
        assertFalse(TypedNbt.same(DoubleNBT.valueOf(Double.POSITIVE_INFINITY), DoubleNBT.valueOf(Double.NEGATIVE_INFINITY)));
        // The native factory and reader canonicalise both zero signs. Construct
        // this fixture directly so the assertion really compares different bits.
        java.lang.reflect.Constructor<DoubleNBT> constructor = DoubleNBT.class.getDeclaredConstructor(double.class);
        constructor.setAccessible(true);
        assertFalse(TypedNbt.same(DoubleNBT.valueOf(0D), constructor.newInstance(-0D)));
    }
    @Test void comparisonDoesNotAlterEitherInput() {
        CompoundNBT left = compound(false), before = left.copy(), right = compound(true);
        TypedNbt.same(left, right); assertEquals(before, left); assertEquals(before, right);
    }
}
