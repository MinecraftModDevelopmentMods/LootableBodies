package cyano.lootable.upgrade;

import java.io.*;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixesManager;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native item-fixer contracts; registry resolution is tested in Forge. */
class ItemNbtConversionTest {
    @BeforeEach void pipeline(){ItemMigration.register(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()));}
    private CompoundNBT context(int version){CompoundNBT nbt=new CompoundNBT();nbt.putInt("DataVersion",version);return nbt;}
    private CompoundNBT item(String id,int damage){CompoundNBT nbt=new CompoundNBT();nbt.putString("id",id);nbt.putByte("Count",(byte)7);nbt.putShort("Damage",(short)damage);nbt.putByte("Slot",(byte)47);CompoundNBT custom=new CompoundNBT();custom.putString("keepsake","saved");nbt.put("tag",custom);return nbt;}
    private byte[] bytes(CompoundNBT nbt)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompressedStreamTools.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    @Test void blockVariantsFlattenWithoutLosingTheirCountSlotOrCustomTag()throws Exception {
        CompoundNBT raw=item("minecraft:stone",1);byte[] before=bytes(raw);CompoundNBT result=ItemMigration.convertNbt(raw,context(512));assertNotNull(result);assertEquals("minecraft:granite",result.getString("id"));assertEquals(7,result.getByte("Count"));assertEquals(47,result.getByte("Slot"));assertEquals("saved",result.getCompound("tag").getString("keepsake"));assertArrayEquals(before,bytes(raw));
    }
    @Test void durabilityAndEnchantmentsUseTheirNativeFields() {
        CompoundNBT raw=item("minecraft:diamond_sword",123);CompoundNBT enchantment=new CompoundNBT();enchantment.putShort("id",(short)16);enchantment.putShort("lvl",(short)3);ListNBT enchantments=new ListNBT();enchantments.add(enchantment);raw.getCompound("tag").put("ench",enchantments);
        CompoundNBT result=ItemMigration.convertNbt(raw,context(1343));assertNotNull(result);assertEquals(123,result.getCompound("tag").getInt("Damage"));CompoundNBT sharpness=result.getCompound("tag").getList("Enchantments",10).getCompound(0);assertEquals("minecraft:sharpness",sharpness.getString("id"));assertEquals(3,sharpness.getShort("lvl"));
    }
    @Test void nestedInventoriesAndCapabilitiesRemainComplete()throws Exception {
        CompoundNBT raw=item("minecraft:blue_shulker_box",0),nested=item("minecraft:stone",1);nested.putByte("Count",(byte)29);nested.putByte("Slot",(byte)3);ListNBT contents=new ListNBT();contents.add(nested);CompoundNBT block=new CompoundNBT();block.putString("id","minecraft:shulker_box");block.put("Items",contents);raw.getCompound("tag").put("BlockEntityTag",block);CompoundNBT caps=new CompoundNBT();caps.putByteArray("retained",new byte[]{1,4,9});raw.put("ForgeCaps",caps);
        CompoundNBT result=ItemMigration.convertNbt(raw,context(922));assertNotNull(result);assertArrayEquals(bytes(caps),bytes(result.getCompound("ForgeCaps")));CompoundNBT recovered=result.getCompound("tag").getCompound("BlockEntityTag").getList("Items",10).getCompound(0);assertEquals("minecraft:granite",recovered.getString("id"));assertEquals(29,recovered.getByte("Count"));assertEquals(3,recovered.getByte("Slot"));
    }
    @Test void currentNativeItemsAreNotFlattenedAgain()throws Exception {
        CompoundNBT raw=item("minecraft:granite",0);raw.remove("Damage");assertArrayEquals(bytes(raw),bytes(ItemMigration.convertNbt(raw,context(UpgradePreservation.MINECRAFT_VERSION))));
    }
    @Test void bedColoursStillUseTheCorrectHistoricalContext() {
        CompoundNBT old=ItemMigration.convertNbt(item("minecraft:bed",0),context(922)),native112=ItemMigration.convertNbt(item("minecraft:bed",0),context(1343));assertNotNull(old);assertNotNull(native112);assertEquals("minecraft:red_bed",old.getString("id"));assertEquals("minecraft:white_bed",native112.getString("id"));
    }
    @Test void ambiguousConversionsRemainRawAndRetriable() {
        CompoundNBT versions=context(922);versions.putBoolean("Ambiguous",true);versions.putIntArray("CandidateDataVersions",new int[]{922,1343});assertNull(ItemMigration.convertNbt(item("minecraft:bed",0),versions));assertNotNull(ItemMigration.convertNbt(item("minecraft:stone",1),versions));
    }
    @Test void malformedCountsAndUnknownFutureVersionsAreNotGuessed() {
        CompoundNBT raw=item("minecraft:stone",1);raw.putString("Count","bad");assertNull(ItemMigration.convertNbt(raw,context(512)));assertNull(ItemMigration.convertNbt(item("minecraft:stone",1),context(2231)));
    }
    @Test void unrecognisedMetadataAndEnchantmentsRemainRecoverable() {
        assertNull(ItemMigration.convertNbt(item("minecraft:stone",99),context(1343)));
        assertNull(ItemMigration.convertNbt(item("missing:variant",4),context(1343)));
        CompoundNBT raw=item("minecraft:diamond_sword",0),enchantment=new CompoundNBT();
        enchantment.putShort("id",(short)200);enchantment.putShort("lvl",(short)5);
        ListNBT list=new ListNBT();list.add(enchantment);raw.getCompound("tag").put("ench",list);
        assertNull(ItemMigration.convertNbt(raw,context(1343)));
    }
    private CompoundNBT original110(int slot)throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/legacy-items/original-110.nbt")) {
            ListNBT list=CompressedStreamTools.readCompressed(in).getList("Items",10);
            for(INBT value:list){CompoundNBT item=(CompoundNBT)value;if(item.getByte("Slot")==slot)return item;}
        }throw new AssertionError("Missing sealed historical item");
    }
    @Test void actualOriginalPigEggKeepsItsTypeAndSavedEntityName()throws Exception {
        CompoundNBT raw=original110(6);byte[] before=bytes(raw);CompoundNBT fixed=ItemMigration.convertNbt(raw,context(512));assertNotNull(fixed);
        assertEquals("minecraft:pig_spawn_egg",fixed.getString("id"));assertEquals(raw.getByte("Count"),fixed.getByte("Count"));assertEquals("minecraft:pig",fixed.getCompound("tag").getCompound("EntityTag").getString("id"));
        assertEquals("Saved pig egg",net.minecraft.util.text.ITextComponent.Serializer.fromJson(fixed.getCompound("tag").getCompound("EntityTag").getString("CustomName")).getString());assertArrayEquals(before,bytes(raw));
    }
    @Test void actualOriginalChestKeepsItsNestedInventoryInNativeFormat()throws Exception {
        CompoundNBT raw=original110(7),fixed=ItemMigration.convertNbt(raw,context(512));assertNotNull(fixed);
        CompoundNBT nested=fixed.getCompound("tag").getCompound("BlockEntityTag");assertEquals("minecraft:chest",nested.getString("id"));assertEquals("minecraft:emerald",nested.getList("Items",10).getCompound(0).getString("id"));assertEquals(17,nested.getList("Items",10).getCompound(0).getByte("Count"));assertFalse(fixed.contains("Damage"));
        assertEquals("Original keepsake 7",net.minecraft.util.text.ITextComponent.Serializer.fromJson(fixed.getCompound("tag").getCompound("display").getString("Name")).getString());
    }
    @Test void actualOriginalBannerKeepsItsBaseColour()throws Exception {
        CompoundNBT raw=original110(8),fixed=ItemMigration.convertNbt(raw,context(512));assertNotNull(fixed);assertEquals("minecraft:purple_banner",fixed.getString("id"));assertEquals(raw.getByte("Count"),fixed.getByte("Count"));// Vanilla 1.13 leaves its inverted, redundant Base field behind.
        assertEquals(10,fixed.getCompound("tag").getCompound("BlockEntityTag").getInt("Base"));
    }
}
