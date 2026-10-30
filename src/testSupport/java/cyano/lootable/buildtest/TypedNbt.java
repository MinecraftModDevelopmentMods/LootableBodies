package cyano.lootable.buildtest;

import java.util.TreeSet;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Compound order is immaterial; NBT types, list order and every array byte matter. */
public final class TypedNbt {
    private TypedNbt() { }
    public static boolean same(Tag left, Tag right) {
        return canonical(left).equals(canonical(right));
    }
    /** Native Attributes is a map; inventory and recovery lists stay ordered. */
    public static boolean sameEntity(CompoundTag left,CompoundTag right) {
        return same(entityCopy(left),entityCopy(right));
    }
    private static CompoundTag entityCopy(CompoundTag input) {
        CompoundTag copy=input.copy();
        if(!copy.contains("Attributes",9))return copy;
        ListTag attributes=copy.getList("Attributes",10);
        if(attributes.size()!=((ListTag)copy.get("Attributes")).size())return copy;
        java.util.List<Tag> entries=new java.util.ArrayList<>();
        for(Tag value:attributes) {
            if(!(value instanceof CompoundTag) || !((CompoundTag)value).contains("Name",8))return copy;
            entries.add(value);
        }
        entries.sort(java.util.Comparator.comparing(TypedNbt::canonical));
        ListTag sorted=new ListTag();entries.forEach(sorted::add);copy.put("Attributes",sorted);return copy;
    }
    public static String canonical(Tag tag) {
        if(tag==null)return "null";
        StringBuilder text=new StringBuilder().append(tag.getId()).append(':');
        if(tag instanceof CompoundTag) {
            CompoundTag compound=(CompoundTag)tag;
            for(String key:new TreeSet<>(compound.getAllKeys()))text.append(key.length()).append(':').append(key).append('=').append(canonical(compound.get(key))).append(';');
        } else if(tag instanceof ListTag) {
            ListTag list=(ListTag)tag;text.append(list.getElementType()).append('/').append(list.size()).append('[');
            for(int i=0;i<list.size();i++)text.append(canonical(list.get(i))).append(';');text.append(']');
        } else if(tag instanceof ByteArrayTag) {
            for(byte value:((ByteArrayTag)tag).getAsByteArray())text.append(Integer.toHexString((value&255)|256).substring(1));
        } else if(tag instanceof IntArrayTag) {
            for(int value:((IntArrayTag)tag).getAsIntArray())text.append(Integer.toHexString(value)).append(',');
        } else if(tag instanceof FloatTag) text.append(Integer.toHexString(Float.floatToRawIntBits(((FloatTag)tag).getAsFloat())));
        else if(tag instanceof DoubleTag) text.append(Long.toHexString(Double.doubleToRawLongBits(((DoubleTag)tag).getAsDouble())));
        else text.append(tag.toString());
        return text.toString();
    }
}
