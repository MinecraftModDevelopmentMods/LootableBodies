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
    private CompoundNBT entity(boolean reverse) {
        CompoundNBT body=new CompoundNBT(),health=new CompoundNBT(),speed=new CompoundNBT();
        health.putString("Name","minecraft:generic.max_health");health.putDouble("Base",20);
        speed.putString("Name","minecraft:generic.movement_speed");speed.putDouble("Base",.7);
        ListNBT attributes=new ListNBT();attributes.add(reverse?speed:health);attributes.add(reverse?health:speed);body.put("Attributes",attributes);
        ListNBT equipment=new ListNBT();equipment.add(compound(false));equipment.add(compound(true));body.put("Equipment",equipment);return body;
    }
    @Test void nativeAttributeMapOrderDoesNotInventAnEntityMutation() {
        CompoundNBT before=entity(false),other=entity(true),copy=before.copy();
        assertFalse(TypedNbt.same(before,other));assertTrue(TypedNbt.sameEntity(before,other));
        assertTrue(TypedNbt.same(before,copy));assertTrue(TypedNbt.same(other,entity(true)));
    }
    @Test void entityComparisonStillRequiresEveryTypedAttributeAndDuplicate() {
        CompoundNBT left=entity(false),right=entity(true);right.getList("Attributes",10).getCompound(0).putFloat("Base",.7F);
        assertFalse(TypedNbt.sameEntity(left,right));right=entity(true);right.getList("Attributes",10).add(right.getList("Attributes",10).getCompound(0).copy());
        assertFalse(TypedNbt.sameEntity(left,right));
    }
    @Test void inventoryAndRecoveryListOrderRemainStrictInsideEntityComparison() {
        CompoundNBT left=entity(false),right=entity(true);ListNBT items=new ListNBT();items.add(IntNBT.valueOf(1));items.add(IntNBT.valueOf(2));left.put("Equipment",items);
        ListNBT reversed=new ListNBT();reversed.add(IntNBT.valueOf(2));reversed.add(IntNBT.valueOf(1));right.put("Equipment",reversed);
        assertFalse(TypedNbt.sameEntity(left,right));left.remove("Equipment");right.remove("Equipment");left.put("LootableBodiesUnresolved",items);right.put("LootableBodiesUnresolved",reversed);
        assertFalse(TypedNbt.sameEntity(left,right));
    }
}
