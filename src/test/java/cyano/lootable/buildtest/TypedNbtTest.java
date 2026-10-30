package cyano.lootable.buildtest;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypedNbtTest {
    private CompoundTag compound(boolean reverse) {
        CompoundTag value = new CompoundTag();
        // These names deliberately collide in Java's hash map.
        if (reverse) { value.putInt("BB", 2); value.putInt("Aa", 1); }
        else { value.putInt("Aa", 1); value.putInt("BB", 2); }
        return value;
    }
    @Test void compoundInsertionOrderDoesNotInventAMutation() {
        CompoundTag left = compound(false), right = compound(true);
        assertEquals(left, right); assertTrue(TypedNbt.same(left, right));
    }
    @Test void compoundOrderInsideListsAlsoDoesNotInventAMutation() {
        ListTag left = new ListTag(), right = new ListTag();
        left.add(compound(false)); right.add(compound(true));
        assertTrue(TypedNbt.same(left, right));
    }
    @Test void listOrderAndNumericTypesStillMatter() {
        ListTag left = new ListTag(), right = new ListTag();
        left.add(IntTag.valueOf(1)); left.add(IntTag.valueOf(2));
        right.add(IntTag.valueOf(2)); right.add(IntTag.valueOf(1));
        assertFalse(TypedNbt.same(left, right));
        assertFalse(TypedNbt.same(IntTag.valueOf(7), ByteTag.valueOf((byte)7)));
    }
    @Test void arraysAndChangedNestedPayloadsStillMatter() {
        assertFalse(TypedNbt.same(new IntArrayTag(new int[]{1,2}), new IntArrayTag(new int[]{2,1})));
        CompoundTag left = compound(false), right = compound(true); right.putInt("Aa", 3);
        assertFalse(TypedNbt.same(left, right));
    }
    @Test void nonFiniteValuesKeepTheirTypesAndSigns() throws Exception {
        assertTrue(TypedNbt.same(DoubleTag.valueOf(Double.NaN), DoubleTag.valueOf(Double.NaN)));
        assertFalse(TypedNbt.same(DoubleTag.valueOf(Double.NaN), FloatTag.valueOf(Float.NaN)));
        assertFalse(TypedNbt.same(DoubleTag.valueOf(Double.POSITIVE_INFINITY), DoubleTag.valueOf(Double.NEGATIVE_INFINITY)));
        // The native factory and reader canonicalise both zero signs. Construct
        // this fixture directly so the assertion really compares different bits.
        java.lang.reflect.Constructor<DoubleTag> constructor = DoubleTag.class.getDeclaredConstructor(double.class);
        constructor.setAccessible(true);
        assertFalse(TypedNbt.same(DoubleTag.valueOf(0D), constructor.newInstance(-0D)));
    }
    @Test void comparisonDoesNotAlterEitherInput() {
        CompoundTag left = compound(false), before = left.copy(), right = compound(true);
        TypedNbt.same(left, right); assertEquals(before, left); assertEquals(before, right);
    }
    private CompoundTag entity(boolean reverse) {
        CompoundTag body=new CompoundTag(),health=new CompoundTag(),speed=new CompoundTag();
        health.putString("Name","minecraft:generic.max_health");health.putDouble("Base",20);
        speed.putString("Name","minecraft:generic.movement_speed");speed.putDouble("Base",.7);
        ListTag attributes=new ListTag();attributes.add(reverse?speed:health);attributes.add(reverse?health:speed);body.put("Attributes",attributes);
        ListTag equipment=new ListTag();equipment.add(compound(false));equipment.add(compound(true));body.put("Equipment",equipment);return body;
    }
    @Test void nativeAttributeMapOrderDoesNotInventAnEntityMutation() {
        CompoundTag before=entity(false),other=entity(true),copy=before.copy();
        assertFalse(TypedNbt.same(before,other));assertTrue(TypedNbt.sameEntity(before,other));
        assertTrue(TypedNbt.same(before,copy));assertTrue(TypedNbt.same(other,entity(true)));
    }
    @Test void entityComparisonStillRequiresEveryTypedAttributeAndDuplicate() {
        CompoundTag left=entity(false),right=entity(true);right.getList("Attributes",10).getCompound(0).putFloat("Base",.7F);
        assertFalse(TypedNbt.sameEntity(left,right));right=entity(true);right.getList("Attributes",10).add(right.getList("Attributes",10).getCompound(0).copy());
        assertFalse(TypedNbt.sameEntity(left,right));
    }
    @Test void inventoryAndRecoveryListOrderRemainStrictInsideEntityComparison() {
        CompoundTag left=entity(false),right=entity(true);ListTag items=new ListTag();items.add(IntTag.valueOf(1));items.add(IntTag.valueOf(2));left.put("Equipment",items);
        ListTag reversed=new ListTag();reversed.add(IntTag.valueOf(2));reversed.add(IntTag.valueOf(1));right.put("Equipment",reversed);
        assertFalse(TypedNbt.sameEntity(left,right));left.remove("Equipment");right.remove("Equipment");left.put("LootableBodiesUnresolved",items);right.put("LootableBodiesUnresolved",reversed);
        assertFalse(TypedNbt.sameEntity(left,right));
    }
}
