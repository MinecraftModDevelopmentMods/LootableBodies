package cyano.lootable.buildtest;

import java.util.TreeSet;
import net.minecraft.nbt.*;

/** Compound order is immaterial; NBT types, list order and every array byte matter. */
public final class TypedNbt {
    private TypedNbt() { }
    public static boolean same(INBT left, INBT right) {
        return canonical(left).equals(canonical(right));
    }
    /** Native Attributes is a map; inventory and recovery lists stay ordered. */
    public static boolean sameEntity(CompoundNBT left,CompoundNBT right) {
        return same(entityCopy(left),entityCopy(right));
    }
    private static CompoundNBT entityCopy(CompoundNBT input) {
        CompoundNBT copy=input.copy();
        if(!copy.contains("Attributes",9))return copy;
        ListNBT attributes=copy.getList("Attributes",10);
        if(attributes.size()!=((ListNBT)copy.get("Attributes")).size())return copy;
        java.util.List<INBT> entries=new java.util.ArrayList<>();
        for(INBT value:attributes) {
            if(!(value instanceof CompoundNBT) || !((CompoundNBT)value).contains("Name",8))return copy;
            entries.add(value);
        }
        entries.sort(java.util.Comparator.comparing(TypedNbt::canonical));
        ListNBT sorted=new ListNBT();entries.forEach(sorted::add);copy.put("Attributes",sorted);return copy;
    }
    public static String canonical(INBT tag) {
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
