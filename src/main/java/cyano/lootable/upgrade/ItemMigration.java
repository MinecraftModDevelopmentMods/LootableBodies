package cyano.lootable.upgrade;

import java.util.*;
import net.minecraft.item.Item;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.*;
import net.minecraftforge.common.util.CompoundDataFixer;

/** Runs item fixers on copies with the original source-version context. */
public final class ItemMigration {
    private enum Envelope implements IFixType { ITEM }
    private static CompoundDataFixer pipeline;
    private ItemMigration() { }

    static void register(CompoundDataFixer fixer) {
        pipeline=fixer;
        fixer.registerVanillaWalker(Envelope.ITEM,(nested,envelope,version)-> {
            if(!envelope.getBoolean("Done")) {
                envelope.setBoolean("Done",true);
                envelope.setTag("Item",nested.process(FixTypes.ITEM_INSTANCE,envelope.getCompoundTag("Item"),version));
            }
            return envelope;
        });
    }

    /** Null means that conversion is ambiguous; retain the original raw record. */
    public static NBTTagCompound convert(NBTTagCompound raw,NBTTagCompound versions) {
        if(versions==null || versions.hasNoTags()) return raw.copy();
        if(pipeline==null || !versions.hasKey("DataVersion",3)) return null;
        int source=versions.getInteger("DataVersion");
        if(source<512 || source>922 || integral(raw.getTag("Count"))==null) return null;
        NBTTagCompound item=raw.copy();
        try {
            NBTTagCompound current=new NBTTagCompound();pipeline.writeVersionData(current);
            NBTTagCompound targets=current.getCompoundTag("ForgeDataVersion");
            List<String> mods=new ArrayList<>(targets.getKeySet());Collections.sort(mods);mods.remove("minecraft");mods.add(0,"minecraft");
            // CompoundDataFixer invokes its global walkers once per outdated mod.
            // Run one version context at a time so a nested banner/container is
            // not converted twice merely because this mod also has a data fixer.
            for(String mod:mods) {
                if(mod.equals("lootablebodies") || mod.equals("lootablebodies_upgrade")) continue;
                NBTTagCompound observed=versions.getCompoundTag("ForgeDataVersion");
                int old=mod.equals("minecraft") ? source : observed.hasKey(mod,3) ? observed.getInteger(mod) : -1;
                if(old>=targets.getInteger(mod)) continue;
                NBTTagCompound envelope=current.copy();envelope.setInteger("DataVersion",922);
                envelope.getCompoundTag("ForgeDataVersion").setInteger(mod,old);
                if(mod.equals("minecraft")) envelope.setInteger("DataVersion",old);
                envelope.setTag("Item",item);
                item=pipeline.process(Envelope.ITEM,envelope).getCompoundTag("Item");
            }
            if(!Objects.equals(integral(raw.getTag("Count")),integral(item.getTag("Count")))
                    || !Objects.equals(raw.getTag("Slot"),item.getTag("Slot"))
                    || !Objects.equals(raw.getTag("ForgeCaps"),item.getTag("ForgeCaps"))) return null;
            Long damage=raw.hasKey("Damage") ? integral(raw.getTag("Damage")) : Long.valueOf(0);
            Long converted=item.hasKey("Damage") ? integral(item.getTag("Damage")) : Long.valueOf(0);
            if(!Objects.equals(damage,converted)) {
                NBTTagCompound banner=raw.getCompoundTag("tag").getCompoundTag("BlockEntityTag");
                if(!raw.getString("id").equals("minecraft:banner") || source>=804 || !banner.hasKey("Base",99)
                        || converted==null || converted!=(banner.getShort("Base")&15)) return null;
            }
            for(String key:raw.getKeySet()) if(!Arrays.asList("id","Count","Damage","Slot","tag").contains(key)
                    && !Objects.equals(raw.getTag(key),item.getTag(key))) return null;
            if(!knownNestedItems(item) || !nestedInventoriesSurvive(raw,item)) return null;
            return item;
        } catch(RuntimeException ambiguous) {return null;}
    }

    private static Long integral(NBTBase nbt) {
        return nbt instanceof NBTPrimitive && nbt.getId()>=1 && nbt.getId()<=4 ? ((NBTPrimitive)nbt).getLong() : null;
    }
    private static boolean knownNestedItems(NBTBase tag) {
        if(tag instanceof NBTTagCompound) {
            NBTTagCompound compound=(NBTTagCompound)tag;
            if(compound.hasKey("id") && compound.hasKey("Count")) {
                if(!compound.hasKey("id",8)) return false;
                ResourceLocation id=new ResourceLocation(compound.getString("id"));
                if(!Item.REGISTRY.containsKey(id) || Item.getIdFromItem(Item.REGISTRY.getObject(id))==0) return false;
            }
            for(String key:compound.getKeySet()) if(!key.equals("ForgeCaps") && !knownNestedItems(compound.getTag(key))) return false;
        } else if(tag instanceof NBTTagList) {
            NBTTagList list=(NBTTagList)tag;for(int i=0;i<list.tagCount();i++) if(!knownNestedItems(list.get(i))) return false;
        }
        return true;
    }
    private static boolean nestedInventoriesSurvive(NBTBase before,NBTBase after) {
        if(before instanceof NBTTagCompound) {
            if(!(after instanceof NBTTagCompound)) return false;
            NBTTagCompound a=(NBTTagCompound)before,b=(NBTTagCompound)after;
            for(String key:a.getKeySet()) {
                NBTBase old=a.getTag(key),next=b.getTag(key);
                if((key.equals("Items") || key.equals("Inventory")) && old instanceof NBTTagList) {
                    if(!(next instanceof NBTTagList) || ((NBTTagList)old).tagCount()!=((NBTTagList)next).tagCount()) return false;
                    NBTTagList x=(NBTTagList)old,y=(NBTTagList)next;
                    for(int i=0;i<x.tagCount();i++) if(x.get(i) instanceof NBTTagCompound) {
                        NBTTagCompound p=x.getCompoundTagAt(i),q=y.getCompoundTagAt(i);
                        if(!Objects.equals(integral(p.getTag("Count")),integral(q.getTag("Count"))) || !Objects.equals(p.getTag("Slot"),q.getTag("Slot"))) return false;
                    }
                }
                if(next!=null && !nestedInventoriesSurvive(old,next)) return false;
                if(next==null && Arrays.asList("Items","Inventory","Equipment","Aux").contains(key) && old instanceof NBTTagList && ((NBTTagList)old).tagCount()!=0) return false;
            }
        } else if(before instanceof NBTTagList && after instanceof NBTTagList) {
            NBTTagList a=(NBTTagList)before,b=(NBTTagList)after;
            if(a.tagCount()!=b.tagCount()) return false;
            for(int i=0;i<a.tagCount();i++) if(!nestedInventoriesSurvive(a.get(i),b.get(i))) return false;
        }
        return true;
    }
}
