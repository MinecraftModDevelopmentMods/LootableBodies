package cyano.lootable.buildtest;

import java.util.TreeSet;
import net.minecraft.nbt.*;

/** Compound order is immaterial; NBT types, list order and every array byte matter. */
final class TypedNbt {
    static String canonical(INBTBase tag) {
        if(tag==null)return "null";
        StringBuilder text=new StringBuilder().append(tag.getId()).append(':');
        if(tag instanceof NBTTagCompound) {
            NBTTagCompound compound=(NBTTagCompound)tag;
            for(String key:new TreeSet<>(compound.keySet()))text.append(key.length()).append(':').append(key).append('=').append(canonical(compound.getTag(key))).append(';');
        } else if(tag instanceof NBTTagList) {
            NBTTagList list=(NBTTagList)tag;text.append(list.getTagType()).append('/').append(list.size()).append('[');
            for(int i=0;i<list.size();i++)text.append(canonical(list.get(i))).append(';');text.append(']');
        } else if(tag instanceof NBTTagByteArray) {
            for(byte value:((NBTTagByteArray)tag).getByteArray())text.append(Integer.toHexString((value&255)|256).substring(1));
        } else if(tag instanceof NBTTagIntArray) {
            for(int value:((NBTTagIntArray)tag).getIntArray())text.append(Integer.toHexString(value)).append(',');
        } else if(tag instanceof NBTTagFloat) text.append(Integer.toHexString(Float.floatToRawIntBits(((NBTTagFloat)tag).getFloat())));
        else if(tag instanceof NBTTagDouble) text.append(Long.toHexString(Double.doubleToRawLongBits(((NBTTagDouble)tag).getDouble())));
        else text.append(tag.toString());
        return text.toString();
    }
}
