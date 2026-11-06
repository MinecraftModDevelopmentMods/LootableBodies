package cyano.lootable.upgrade;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Native1206ComponentsTest {
    @BeforeAll static void bootstrap() {
        cyano.lootable.buildtest.Native117TestEnvironment.initialize();
        ItemMigration.register(UpgradePreservation.wrapper(DataFixers.getDataFixer()));
    }

    private static CompoundTag context(int version) {
        CompoundTag context=new CompoundTag();
        context.putInt("DataVersion",version);
        return context;
    }

    private static CompoundTag legacy(String id,int count,int slot) {
        CompoundTag stack=new CompoundTag();
        stack.putString("id","minecraft:"+id);
        stack.putByte("Count",(byte)count);
        stack.putByte("Slot",(byte)slot);
        return stack;
    }

    @Test void targetUsesTheActual3839NativeSchema() {
        assertEquals(3839,SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        assertEquals(3839,UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(11,UpgradePreservation.DATA_VERSION);
    }

    @Test void released120ItemShapePreservesDamageEnchantmentsAndTypedCustomData() {
        CompoundTag original=legacy("diamond_sword",1,47);
        CompoundTag tag=new CompoundTag();
        tag.putInt("Damage",27);
        CompoundTag display=new CompoundTag();
        display.putString("Name","{\"text\":\"Old Blade\"}");
        tag.put("display",display);
        tag.putLongArray("keepsake",new long[]{Long.MIN_VALUE,190,Long.MAX_VALUE});
        CompoundTag entry=new CompoundTag();
        entry.putString("id","minecraft:sharpness");
        entry.putShort("lvl",(short)3);
        ListTag enchantments=new ListTag();
        enchantments.add(entry);
        tag.put("Enchantments",enchantments);
        original.put("tag",tag);
        CompoundTag before=original.copy();
        CompoundTag converted=ItemMigration.convert(original,context(3465));
        assertNotNull(converted);
        assertEquals(47,converted.getByte("Slot"));
        assertEquals(1,converted.getInt("count"));
        assertFalse(converted.contains("Count"));
        CompoundTag components=converted.getCompound("components");
        assertEquals(27,components.getInt("minecraft:damage"));
        assertEquals(3,components.getCompound("minecraft:enchantments").getCompound("levels").getInt("minecraft:sharpness"));
        assertArrayEquals(tag.getLongArray("keepsake"),components.getCompound("minecraft:custom_data").getLongArray("keepsake"));
        RegistryAccess registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ItemStack stack=ItemStack.parse(registry,converted).orElseThrow();
        assertSame(Items.DIAMOND_SWORD,stack.getItem());
        assertEquals(27,stack.getDamageValue());
        CompoundTag encoded=((CompoundTag)stack.save(registry)).getCompound("components");
        assertEquals("\"Old Blade\"",encoded.getString("minecraft:custom_name"));
        assertTrue(ItemMigration.sameSavedComponents(components,encoded));
        CompoundTag changed=components.copy();
        changed.putString("minecraft:custom_name","{\"text\":\"Old Blade\",\"italic\":false}");
        assertFalse(ItemMigration.sameSavedComponents(changed,encoded));
        changed=components.copy();
        changed.getCompound("minecraft:custom_data").putString("extra","must retain");
        assertFalse(ItemMigration.sameSavedComponents(changed,encoded));
        assertEquals(before,original);
    }

    @Test void original110BannerKeepsItsFlattenedColourAndComponentData() {
        CompoundTag original=legacy("banner",2,8);
        original.putShort("Damage",(short)0);
        CompoundTag tag=new CompoundTag(),block=new CompoundTag(),display=new CompoundTag();
        block.putInt("Base",5);
        tag.put("BlockEntityTag",block);
        display.putString("Name","Original keepsake 8");
        tag.put("display",display);
        tag.putLong("typed_long",1234567890123L);
        tag.putByteArray("typed_bytes",new byte[]{0,-1,17});
        original.put("tag",tag);
        CompoundTag before=original.copy();
        CompoundTag flattened=ItemMigration.convertNbt(original,context(512));
        assertNotNull(flattened);
        CompoundTag migrated=ItemMigration.convert(original,context(512));
        assertNotNull(migrated,flattened::toString);
        assertEquals("minecraft:purple_banner",migrated.getString("id"));
        assertEquals(2,migrated.getInt("count"));
        assertEquals(8,migrated.getByte("Slot"));
        CompoundTag components=migrated.getCompound("components");
        assertEquals("purple",components.getString("minecraft:base_color"));
        assertEquals(1234567890123L,components.getCompound("minecraft:custom_data").getLong("typed_long"));
        assertArrayEquals(new byte[]{0,-1,17},components.getCompound("minecraft:custom_data").getByteArray("typed_bytes"));
        assertEquals(before,original);
    }

    @Test void unknownBannerBaseIsRetainedInsteadOfApplyingAnArbitraryColour() {
        CompoundTag original=legacy("purple_banner",2,8);
        CompoundTag tag=new CompoundTag(),block=new CompoundTag();
        block.putInt("Base",16);
        tag.put("BlockEntityTag",block);
        original.put("tag",tag);
        CompoundTag before=original.copy();
        assertNull(ItemMigration.convert(original,context(3465)));
        assertEquals(before,original);
    }

    @Test void nestedContainerKeepsOriginalIndexItemAndCount() {
        CompoundTag original=legacy("blue_shulker_box",1,8);
        CompoundTag block=new CompoundTag();
        block.putString("id","minecraft:shulker_box");
        ListTag children=new ListTag();
        children.add(legacy("emerald",29,3));
        block.put("Items",children);
        CompoundTag tag=new CompoundTag();
        tag.put("BlockEntityTag",block);
        original.put("tag",tag);
        CompoundTag converted=ItemMigration.convert(original,context(3465));
        assertNotNull(converted);
        ListTag slots=converted.getCompound("components").getList("minecraft:container",10);
        assertEquals(1,slots.size());
        assertEquals(3,slots.getCompound(0).getInt("slot"));
        assertEquals("minecraft:emerald",slots.getCompound(0).getCompound("item").getString("id"));
        assertEquals(29,slots.getCompound(0).getCompound("item").getInt("count"));
        assertEquals(3,children.getCompound(0).getByte("Slot"));
    }

    @Test void legacyCapabilityPayloadAndDuplicateNestedSlotsAreLeftForRecovery() {
        CompoundTag withCaps=legacy("diamond",7,47);
        CompoundTag caps=new CompoundTag();
        caps.putIntArray("fixture",new int[]{1,2,3});
        withCaps.put("ForgeCaps",caps);
        CompoundTag original=withCaps.copy();
        assertNull(ItemMigration.convert(withCaps,context(3465)));
        assertEquals(original,withCaps);

        CompoundTag box=legacy("blue_shulker_box",1,8);
        CompoundTag block=new CompoundTag();
        block.putString("id","minecraft:shulker_box");
        ListTag children=new ListTag();
        children.add(legacy("diamond",17,3));
        children.add(legacy("emerald",8,3));
        block.put("Items",children);
        CompoundTag tag=new CompoundTag();
        tag.put("BlockEntityTag",block);
        box.put("tag",tag);
        assertNull(ItemMigration.convert(box,context(3465)));
    }

    @Test void registryAliasesAgreeWithNative1206Fixers() {
        var from=net.minecraft.resources.ResourceLocation.withDefaultNamespace("grass");
        var block=new CompoundTag();
        block.putString("Name",from.toString());
        var fixed=NativeNbt.update(DataFixers.getDataFixer(),net.minecraft.util.datafix.fixes.References.BLOCK_STATE,
                block,3465,3839);
        assertEquals(fixed.getString("Name"),LegacyRegistryMappings.blockReplacement(from).toString());
        for(String name:new String[]{"grass","scute"}) {
            var key=net.minecraft.resources.ResourceLocation.withDefaultNamespace(name);
            var migrated=ItemMigration.convert(legacy(name,2,47),context(3465));
            assertNotNull(migrated);
            assertEquals(migrated.getString("id"),LegacyRegistryMappings.itemReplacement(key).toString());
        }
        var item=legacy("diamond_sword",1,6);
        CompoundTag tag=new CompoundTag(), enchant=new CompoundTag();
        enchant.putString("id","minecraft:sweeping");
        enchant.putShort("lvl",(short)2);
        ListTag enchants=new ListTag();
        enchants.add(enchant);
        tag.put("Enchantments",enchants);
        item.put("tag",tag);
        var migrated=ItemMigration.convert(item,context(3465));
        assertNotNull(migrated);
        var sweeping=net.minecraft.resources.ResourceLocation.withDefaultNamespace("sweeping");
        var replacement=LegacyRegistryMappings.enchantmentReplacement(sweeping);
        assertEquals(2,migrated.getCompound("components").getCompound("minecraft:enchantments").getCompound("levels").getInt(replacement.toString()));
        assertNull(LegacyRegistryMappings.itemReplacement(net.minecraft.resources.ResourceLocation.tryParse("other:scute")));
    }

    @Test void emptyPotionRemainsEmptyWithoutAnArtificialRegistryAlias() {
        var key=net.minecraft.resources.ResourceLocation.withDefaultNamespace("empty");
        assertTrue(LegacyRegistryMappings.retiredPotion(key));
        assertFalse(BuiltInRegistries.POTION.containsKey(key));
        CompoundTag original=legacy("potion",1,6), tag=new CompoundTag();
        tag.putString("Potion",key.toString());
        original.put("tag",tag);
        var migrated=ItemMigration.convert(original,context(3465));
        assertNotNull(migrated);
        assertFalse(migrated.getCompound("components").getCompound("minecraft:potion_contents").contains("potion"));
        assertEquals("minecraft:empty",original.getCompound("tag").getString("Potion"));
    }

    @Test void validCurrentComponentItemIsCopiedWithoutLegacyConversion() {
        RegistryAccess registry=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ItemStack stack=new ItemStack(Items.DIAMOND,31);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Saved keepsake"));
        CompoundTag current=(CompoundTag)stack.save(registry);
        current.putByte("Slot",(byte)47);
        CompoundTag converted=ItemMigration.convert(current,context(3839));
        assertEquals(current,converted);
        assertNotSame(current,converted);
    }
}
