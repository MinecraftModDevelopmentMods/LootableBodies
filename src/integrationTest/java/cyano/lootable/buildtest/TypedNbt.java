package cyano.lootable.buildtest;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.IntArrayNBT;
import net.minecraft.nbt.FloatNBT;
import net.minecraft.nbt.DoubleNBT;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ByteArrayNBT;

import java.util.TreeSet;
import net.minecraft.nbt.*;

/** Compound order is immaterial; NBT types, list order and every array byte matter. */
final class TypedNbt {
    static String canonical(INBT tag) {
        if(tag==null)return "null";
        StringBuilder text=new StringBuilder().append(tag.getId()).append(':');
        if(tag instanceof CompoundNBT) {
            CompoundNBT compound=(CompoundNBT)tag;
            for(String key:new TreeSet<>(compound.keySet()))text.append(key.length()).append(':').append(key).append('=').append(canonical(compound.get(key))).append(';');
        } else if(tag instanceof ListNBT) {
            ListNBT list=(ListNBT)tag;text.append(list.getTagType()).append('/').append(list.size()).append('[');
            for(int i=0;i<list.size();i++)text.append(canonical(list.get(i))).append(';');text.append(']');
        } else if(tag instanceof ByteArrayNBT) {
            for(byte value:((ByteArrayNBT)tag).getByteArray())text.append(Integer.toHexString((value&255)|256).substring(1));
        } else if(tag instanceof IntArrayNBT) {
            for(int value:((IntArrayNBT)tag).getIntArray())text.append(Integer.toHexString(value)).append(',');
        } else if(tag instanceof FloatNBT) text.append(Integer.toHexString(Float.floatToRawIntBits(((FloatNBT)tag).getFloat())));
        else if(tag instanceof DoubleNBT) text.append(Long.toHexString(Double.doubleToRawLongBits(((DoubleNBT)tag).getDouble())));
        else text.append(tag.toString());
        return text.toString();
    }
}
