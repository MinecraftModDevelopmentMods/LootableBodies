package cyano.lootable.upgrade;

import java.io.*;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixesManager;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native item-fixer contracts; registry resolution is tested in Forge. */
class ItemNbtConversionTest {
    @BeforeEach void pipeline(){ItemMigration.register(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()));}
    private NBTTagCompound context(int version){NBTTagCompound nbt=new NBTTagCompound();nbt.setInt("DataVersion",version);return nbt;}
    private NBTTagCompound item(String id,int damage){NBTTagCompound nbt=new NBTTagCompound();nbt.setString("id",id);nbt.setByte("Count",(byte)7);nbt.setShort("Damage",(short)damage);nbt.setByte("Slot",(byte)47);NBTTagCompound custom=new NBTTagCompound();custom.setString("keepsake","saved");nbt.setTag("tag",custom);return nbt;}
    private byte[] bytes(NBTTagCompound nbt)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompressedStreamTools.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    @Test void blockVariantsFlattenWithoutLosingTheirCountSlotOrCustomTag()throws Exception {
        NBTTagCompound raw=item("minecraft:stone",1);byte[] before=bytes(raw);NBTTagCompound result=ItemMigration.convertNbt(raw,context(512));assertNotNull(result);assertEquals("minecraft:granite",result.getString("id"));assertEquals(7,result.getByte("Count"));assertEquals(47,result.getByte("Slot"));assertEquals("saved",result.getCompound("tag").getString("keepsake"));assertArrayEquals(before,bytes(raw));
    }
    @Test void durabilityAndEnchantmentsUseTheirNativeFields() {
        NBTTagCompound raw=item("minecraft:diamond_sword",123);NBTTagCompound enchantment=new NBTTagCompound();enchantment.setShort("id",(short)16);enchantment.setShort("lvl",(short)3);NBTTagList enchantments=new NBTTagList();enchantments.add(enchantment);raw.getCompound("tag").setTag("ench",enchantments);
        NBTTagCompound result=ItemMigration.convertNbt(raw,context(1343));assertNotNull(result);assertEquals(123,result.getCompound("tag").getInt("Damage"));NBTTagCompound sharpness=result.getCompound("tag").getList("Enchantments",10).getCompound(0);assertEquals("minecraft:sharpness",sharpness.getString("id"));assertEquals(3,sharpness.getShort("lvl"));
    }
    @Test void nestedInventoriesAndCapabilitiesRemainComplete()throws Exception {
        NBTTagCompound raw=item("minecraft:blue_shulker_box",0),nested=item("minecraft:stone",1);nested.setByte("Count",(byte)29);nested.setByte("Slot",(byte)3);NBTTagList contents=new NBTTagList();contents.add(nested);NBTTagCompound block=new NBTTagCompound();block.setString("id","minecraft:shulker_box");block.setTag("Items",contents);raw.getCompound("tag").setTag("BlockEntityTag",block);NBTTagCompound caps=new NBTTagCompound();caps.setByteArray("retained",new byte[]{1,4,9});raw.setTag("ForgeCaps",caps);
        NBTTagCompound result=ItemMigration.convertNbt(raw,context(922));assertNotNull(result);assertArrayEquals(bytes(caps),bytes(result.getCompound("ForgeCaps")));NBTTagCompound recovered=result.getCompound("tag").getCompound("BlockEntityTag").getList("Items",10).getCompound(0);assertEquals("minecraft:granite",recovered.getString("id"));assertEquals(29,recovered.getByte("Count"));assertEquals(3,recovered.getByte("Slot"));
    }
    @Test void currentNativeItemsAreNotFlattenedAgain()throws Exception {
        NBTTagCompound raw=item("minecraft:granite",0);raw.removeTag("Damage");assertArrayEquals(bytes(raw),bytes(ItemMigration.convertNbt(raw,context(1631))));
    }
    @Test void bedColoursStillUseTheCorrectHistoricalContext() {
        NBTTagCompound old=ItemMigration.convertNbt(item("minecraft:bed",0),context(922)),native112=ItemMigration.convertNbt(item("minecraft:bed",0),context(1343));assertNotNull(old);assertNotNull(native112);assertEquals("minecraft:red_bed",old.getString("id"));assertEquals("minecraft:white_bed",native112.getString("id"));
    }
    @Test void ambiguousConversionsRemainRawAndRetriable() {
        NBTTagCompound versions=context(922);versions.setBoolean("Ambiguous",true);versions.setIntArray("CandidateDataVersions",new int[]{922,1343});assertNull(ItemMigration.convertNbt(item("minecraft:bed",0),versions));assertNotNull(ItemMigration.convertNbt(item("minecraft:stone",1),versions));
    }
    @Test void malformedCountsAndUnknownFutureVersionsAreNotGuessed() {
        NBTTagCompound raw=item("minecraft:stone",1);raw.setString("Count","bad");assertNull(ItemMigration.convertNbt(raw,context(512)));assertNull(ItemMigration.convertNbt(item("minecraft:stone",1),context(2000)));
    }
    @Test void unrecognisedMetadataAndEnchantmentsRemainRecoverable() {
        assertNull(ItemMigration.convertNbt(item("minecraft:stone",99),context(1343)));
        assertNull(ItemMigration.convertNbt(item("missing:variant",4),context(1343)));
        NBTTagCompound raw=item("minecraft:diamond_sword",0),enchantment=new NBTTagCompound();
        enchantment.setShort("id",(short)200);enchantment.setShort("lvl",(short)5);
        NBTTagList list=new NBTTagList();list.add(enchantment);raw.getCompound("tag").setTag("ench",list);
        assertNull(ItemMigration.convertNbt(raw,context(1343)));
    }
    private NBTTagCompound original110(int slot)throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/legacy-items/original-110.nbt")) {
            NBTTagList list=CompressedStreamTools.readCompressed(in).getList("Items",10);
            for(INBTBase value:list){NBTTagCompound item=(NBTTagCompound)value;if(item.getByte("Slot")==slot)return item;}
        }throw new AssertionError("Missing sealed historical item");
    }
    @Test void actualOriginalPigEggKeepsItsTypeAndSavedEntityName()throws Exception {
        NBTTagCompound raw=original110(6);byte[] before=bytes(raw);NBTTagCompound fixed=ItemMigration.convertNbt(raw,context(512));assertNotNull(fixed);
        assertEquals("minecraft:pig_spawn_egg",fixed.getString("id"));assertEquals(raw.getByte("Count"),fixed.getByte("Count"));assertEquals("minecraft:pig",fixed.getCompound("tag").getCompound("EntityTag").getString("id"));
        assertEquals("Saved pig egg",net.minecraft.util.text.ITextComponent.Serializer.fromJson(fixed.getCompound("tag").getCompound("EntityTag").getString("CustomName")).getString());assertArrayEquals(before,bytes(raw));
    }
    @Test void actualOriginalChestKeepsItsNestedInventoryInNativeFormat()throws Exception {
        NBTTagCompound raw=original110(7),fixed=ItemMigration.convertNbt(raw,context(512));assertNotNull(fixed);
        NBTTagCompound nested=fixed.getCompound("tag").getCompound("BlockEntityTag");assertEquals("minecraft:chest",nested.getString("id"));assertEquals("minecraft:emerald",nested.getList("Items",10).getCompound(0).getString("id"));assertEquals(17,nested.getList("Items",10).getCompound(0).getByte("Count"));assertFalse(fixed.hasKey("Damage"));
        assertEquals("Original keepsake 7",net.minecraft.util.text.ITextComponent.Serializer.fromJson(fixed.getCompound("tag").getCompound("display").getString("Name")).getString());
    }
    @Test void actualOriginalBannerKeepsItsBaseColour()throws Exception {
        NBTTagCompound raw=original110(8),fixed=ItemMigration.convertNbt(raw,context(512));assertNotNull(fixed);assertEquals("minecraft:purple_banner",fixed.getString("id"));assertEquals(raw.getByte("Count"),fixed.getByte("Count"));// Vanilla 1.13 leaves its inverted, redundant Base field behind.
        assertEquals(10,fixed.getCompound("tag").getCompound("BlockEntityTag").getInt("Base"));
    }
}
