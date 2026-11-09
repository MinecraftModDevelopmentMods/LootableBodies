package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;
import static cyano.lootable.buildtest.TestNativeNbt.writeUuid;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
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

    @Test void targetUsesTheActual4671NativeSchema() {
        assertEquals(4671,SharedConstants.getCurrentVersion().dataVersion().version());
        assertEquals(4671,UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(13,UpgradePreservation.DATA_VERSION);
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
        assertEquals(47,converted.getByteOr("Slot", (byte) 0));
        assertEquals(1,converted.getIntOr("count", 0));
        assertFalse(converted.contains("Count"));
        CompoundTag components=converted.getCompoundOrEmpty("components");
        assertEquals(27,components.getIntOr("minecraft:damage", 0));
        assertEquals(3,components.getCompoundOrEmpty("minecraft:enchantments").getIntOr("minecraft:sharpness", 0));
        assertArrayEquals(tag.getLongArray("keepsake").orElseGet(() -> new long[0]),components.getCompoundOrEmpty("minecraft:custom_data").getLongArray("keepsake").orElseGet(() -> new long[0]));
        HolderLookup.Provider registry=net.minecraft.data.registries.VanillaRegistries.createLookup();
        ItemStack stack=ItemStack.CODEC.parse(registry.createSerializationContext(NbtOps.INSTANCE), converted).getOrThrow();
        assertSame(Items.DIAMOND_SWORD,stack.getItem());
        assertEquals(27,stack.getDamageValue());
        CompoundTag encoded=cyano.lootable.buildtest.TestNativeNbt.saveStack(stack,registry).getCompoundOrEmpty("components");
        assertEquals("Old Blade",stack.getHoverName().getString());
        assertTrue(ItemMigration.sameSavedComponents(components,encoded));
        CompoundTag changed=components.copy();
        CompoundTag explicitStyle = new CompoundTag();
        explicitStyle.putString("text","Old Blade");
        explicitStyle.putBoolean("italic",false);
        changed.put("minecraft:custom_name",explicitStyle);
        assertFalse(ItemMigration.sameSavedComponents(changed,encoded));
        changed=components.copy();
        changed.getCompoundOrEmpty("minecraft:custom_data").putString("extra","must retain");
        assertFalse(ItemMigration.sameSavedComponents(changed,encoded));
        assertEquals(before,original);
    }

    @Test void original110SpawnEggKeepsNativeEntityDataAndCustomName() {
        CompoundTag egg = legacy("spawn_egg",3,40);
        egg.putShort("Damage",(short)0);
        CompoundTag pig = new CompoundTag();
        pig.putString("id","Pig");
        pig.putString("CustomName","Saved pig");
        CompoundTag tag = new CompoundTag();
        tag.put("EntityTag",pig);
        tag.putString("keepsake","old egg");
        egg.put("tag",tag);
        CompoundTag original = egg.copy();

        CompoundTag legacy = ItemMigration.convertNbt(egg,context(512));
        assertNotNull(legacy);
        CompoundTag at121 = NativeNbt.update(NativeVanillaDataFixer.get(),
                net.minecraft.util.datafix.fixes.References.ITEM_STACK,legacy.copy(),3817,3955);
        CompoundTag at12111 = NativeNbt.update(NativeVanillaDataFixer.get(),
                net.minecraft.util.datafix.fixes.References.ITEM_STACK,at121.copy(),3955,4671);
        CompoundTag converted = ItemMigration.convert(egg,context(512));
        assertNotNull(converted, () -> at121+" -> "+at12111);
        assertEquals("minecraft:pig_spawn_egg",converted.getStringOr("id",""));
        assertEquals(3,converted.getIntOr("count",0));
        assertEquals(40,converted.getByteOr("Slot",(byte)-1));
        var registry=cyano.lootable.buildtest.Native119Registries.lookup();
        var stack=ItemStack.CODEC.parse(registry.createSerializationContext(NbtOps.INSTANCE),converted).getOrThrow();
        var encoded=cyano.lootable.buildtest.TestNativeNbt.saveStack(stack,registry);
        assertTrue(ItemMigration.sameSavedComponents(converted.getCompoundOrEmpty("components"),
                encoded.getCompoundOrEmpty("components")));
        assertEquals(original,egg);
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
        assertEquals("minecraft:purple_banner",migrated.getStringOr("id", ""));
        assertEquals(2,migrated.getIntOr("count", 0));
        assertEquals(8,migrated.getByteOr("Slot", (byte) 0));
        CompoundTag components=migrated.getCompoundOrEmpty("components");
        assertEquals("purple",components.getStringOr("minecraft:base_color", ""));
        assertEquals(1234567890123L,components.getCompoundOrEmpty("minecraft:custom_data").getLongOr("typed_long", 0L));
        assertArrayEquals(new byte[]{0,-1,17},components.getCompoundOrEmpty("minecraft:custom_data").getByteArray("typed_bytes").orElseGet(() -> new byte[0]));
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
        ListTag slots=typedList(converted.getCompoundOrEmpty("components"), "minecraft:container", 10);
        assertEquals(1,slots.size());
        assertEquals(3,slots.getCompoundOrEmpty(0).getIntOr("slot", 0));
        assertEquals("minecraft:emerald",slots.getCompoundOrEmpty(0).getCompoundOrEmpty("item").getStringOr("id", ""));
        assertEquals(29,slots.getCompoundOrEmpty(0).getCompoundOrEmpty("item").getIntOr("count", 0));
        assertEquals(3,children.getCompoundOrEmpty(0).getByteOr("Slot", (byte) 0));
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
        var from=net.minecraft.resources.Identifier.withDefaultNamespace("grass");
        var block=new CompoundTag();
        block.putString("Name",from.toString());
        var fixed=NativeNbt.update(DataFixers.getDataFixer(),net.minecraft.util.datafix.fixes.References.BLOCK_STATE,
                block,3465,3955);
        assertEquals(fixed.getStringOr("Name", ""),LegacyRegistryMappings.blockReplacement(from).toString());
        for(String name:new String[]{"grass","scute"}) {
            var key=net.minecraft.resources.Identifier.withDefaultNamespace(name);
            var migrated=ItemMigration.convert(legacy(name,2,47),context(3465));
            assertNotNull(migrated);
            assertEquals(migrated.getStringOr("id", ""),LegacyRegistryMappings.itemReplacement(key).toString());
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
        var sweeping=net.minecraft.resources.Identifier.withDefaultNamespace("sweeping");
        var replacement=LegacyRegistryMappings.enchantmentReplacement(sweeping);
        assertEquals(2,migrated.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:enchantments").getIntOr(replacement.toString(), 0));
        assertNull(LegacyRegistryMappings.itemReplacement(net.minecraft.resources.Identifier.tryParse("other:scute")));
    }

    @Test void emptyPotionRemainsEmptyWithoutAnArtificialRegistryAlias() {
        var key=net.minecraft.resources.Identifier.withDefaultNamespace("empty");
        assertTrue(LegacyRegistryMappings.retiredPotion(key));
        assertFalse(BuiltInRegistries.POTION.containsKey(key));
        CompoundTag original=legacy("potion",1,6), tag=new CompoundTag();
        tag.putString("Potion",key.toString());
        original.put("tag",tag);
        var migrated=ItemMigration.convert(original,context(3465));
        assertNotNull(migrated);
        assertFalse(migrated.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:potion_contents").contains("potion"));
        assertEquals("minecraft:empty",original.getCompoundOrEmpty("tag").getStringOr("Potion", ""));
    }

    @Test void validCurrentComponentItemIsCopiedWithoutLegacyConversion() {
        HolderLookup.Provider registry=net.minecraft.data.registries.VanillaRegistries.createLookup();
        ItemStack stack=new ItemStack(Items.DIAMOND,31);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Saved keepsake"));
        CompoundTag current=cyano.lootable.buildtest.TestNativeNbt.saveStack(stack,registry);
        current.putByte("Slot",(byte)47);
        CompoundTag converted=ItemMigration.convert(current,context(4671));
        assertEquals(current,converted);
        assertNotSame(current,converted);
    }

    @Test void released1206ModifiersKeepTheirValuesAndSlotThroughNativeConversion() {
        CompoundTag old=releasedModifierStack();
        CompoundTag original=old.copy();
        CompoundTag migrated=ItemMigration.convert(old,context(3839));
        assertNotNull(migrated);
        assertEquals(47,migrated.getByteOr("Slot", (byte) 0));
        CompoundTag migratedComponents = migrated.getCompoundOrEmpty("components");
        CompoundTag modifier = migratedComponents.getListOrEmpty("minecraft:attribute_modifiers").getCompoundOrEmpty(0);
        assertEquals("minecraft:attack_damage", modifier.getStringOr("type", ""));
        CompoundTag tooltip = migratedComponents.getCompoundOrEmpty("minecraft:tooltip_display");
        assertFalse(tooltip.getBooleanOr("hide_tooltip", true));
        assertEquals("minecraft:attribute_modifiers", tooltip.getListOrEmpty("hidden_components").getStringOr(0, ""));
        assertEquals("minecraft:00000000-0000-0065-0000-0000000000ca",modifier.getStringOr("id", ""));
        assertEquals(2.75,modifier.getDoubleOr("amount", 0D));
        assertEquals("add_value",modifier.getStringOr("operation", ""));
        assertEquals("mainhand",modifier.getStringOr("slot", ""));
        assertFalse(modifier.contains("uuid"));
        assertEquals(original,old);
        HolderLookup.Provider registry=net.minecraft.data.registries.VanillaRegistries.createLookup();
        ItemStack actual = ItemStack.CODEC.parse(registry.createSerializationContext(NbtOps.INSTANCE), migrated).getOrThrow();
        assertSame(Items.DIAMOND_SWORD, actual.getItem());
        CompoundTag encoded = cyano.lootable.buildtest.TestNativeNbt.saveStack(actual,registry).getCompoundOrEmpty("components");
        assertTrue(ItemMigration.sameSavedComponents(migratedComponents, encoded), () -> migratedComponents+" -> "+encoded);
    }

    @Test void legacyEntityModifiersUseTheNativeUuidAndNameMapping() {
        assertEquals("minecraft:effect.speed",ItemMigration.legacyModifierId(
                java.util.UUID.fromString("91aeaa56-376b-4498-935b-2f7f68070635"),"Old speed").toString());
        assertEquals("minecraft:00000000-0000-0065-0000-0000000000ca",
                ItemMigration.legacyModifierId(new java.util.UUID(101,202),"Old modifier").toString());
    }

    @Test void released1206NestedStackKeepsContainerIndexAndRejectsCollidingModifierIds() {
        CompoundTag sword=releasedModifierStack();
        sword.remove("Slot");
        CompoundTag box=new CompoundTag(),components=new CompoundTag(),entry=new CompoundTag();
        box.putString("id","minecraft:blue_shulker_box");
        box.putInt("count",1);
        entry.putInt("slot",19);
        entry.put("item",sword);
        ListTag contents=new ListTag();
        contents.add(entry);
        components.put("minecraft:container",contents);
        box.put("components",components);
        CompoundTag upgraded=ItemMigration.convert(box,context(3839));
        assertNotNull(upgraded);
        CompoundTag migrated=typedList(upgraded.getCompoundOrEmpty("components"), "minecraft:container", 10).getCompoundOrEmpty(0);
        assertEquals(19,migrated.getIntOr("slot", 0));
        assertEquals("minecraft:diamond_sword",migrated.getCompoundOrEmpty("item").getStringOr("id", ""));
        ListTag modifiers=typedList(sword.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:attribute_modifiers"), "modifiers", 10);
        modifiers.add(modifiers.getCompoundOrEmpty(0).copy());
        CompoundTag original=box.copy();
        assertNull(ItemMigration.convert(box,context(3839)));
        assertEquals(original,box);
    }

    private static CompoundTag releasedModifierStack() {
        CompoundTag item=new CompoundTag(),components=new CompoundTag(),attributes=new CompoundTag(),modifier=new CompoundTag();
        item.putString("id","minecraft:diamond_sword");
        item.putInt("count",1);
        item.putByte("Slot",(byte)47);
        writeUuid(modifier, "uuid", new java.util.UUID(101,202));
        modifier.putString("name","Old modifier");
        modifier.putString("type","minecraft:generic.attack_damage");
        modifier.putDouble("amount",2.75);
        modifier.putString("operation","add_value");
        modifier.putString("slot","mainhand");
        ListTag list=new ListTag();
        list.add(modifier);
        attributes.put("modifiers",list);
        attributes.putBoolean("show_in_tooltip",false);
        components.put("minecraft:attribute_modifiers",attributes);
        item.put("components",components);
        return item;
    }
}
