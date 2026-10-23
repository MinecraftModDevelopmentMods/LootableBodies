package cyano.lootable.buildtest;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.init.Items;
final class RuntimeChecks { static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static ItemStack stack(int index) {
        ItemStack stack = index < 4 ? new ItemStack(new net.minecraft.item.Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}[index],1,index+2) :
                index == 4 ? new ItemStack(Items.DIAMOND_SWORD,1,17) : index == 5 ? new ItemStack(Items.SHIELD,1,9) :
                new ItemStack(Items.DYE, 1 + index % 32, index % 16);
        NBTTagCompound custom = new NBTTagCompound();
        custom.setString("fixture", "slot-" + index);
        NBTTagCompound nested = new NBTTagCompound(); nested.setInteger("unchanged", index * 17);
        NBTTagList contents = new NBTTagList();
        NBTTagCompound item = new NBTTagCompound(); new ItemStack(Items.DIAMOND,3).writeToNBT(item); contents.appendTag(item);
        nested.setTag("contents", contents); custom.setTag("nested", nested);
        stack.setTagCompound(custom); stack.setStackDisplayName("Fixture " + index);
        if (index == 4) stack.addEnchantment(net.minecraft.init.Enchantments.UNBREAKING, 2);
        return stack;
    }
}
