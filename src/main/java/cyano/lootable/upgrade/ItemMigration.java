package cyano.lootable.upgrade;
import java.util.*;
import net.minecraft.item.Item;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;
import com.mojang.datafixers.DataFixer;
/** Runs native item fixes on copies, retaining ambiguous originals. */
public final class ItemMigration {
    private static DataFixer pipeline;
    private ItemMigration() { }
    static void register(DataFixer fixer){pipeline=fixer;}
    /** Null means that conversion is ambiguous; retain the original raw record. */
    public static NBTTagCompound convert(NBTTagCompound raw,NBTTagCompound versions) {
        if(versions==null || versions.isEmpty())return raw.copy();
        NBTTagCompound fixed=convertNbt(raw,versions);
        return fixed!=null && knownNestedItems(fixed)?fixed:null;
    }
    /** NBT conversion is independent of live registry resolution. */
    static NBTTagCompound convertNbt(NBTTagCompound raw,NBTTagCompound versions) {
        if(versions==null || versions.isEmpty()) return raw.copy();
        if(versions.getBoolean("Ambiguous")) {
            NBTTagCompound agreed=null;
            for(int candidate:versions.getIntArray("CandidateDataVersions")) {
                NBTTagCompound context=versions.copy();context.removeTag("Ambiguous");context.removeTag("CandidateDataVersions");context.setInt("DataVersion",candidate);
                NBTTagCompound converted=convertNbt(raw,context);
                if(converted==null || (agreed!=null && !agreed.equals(converted)))return null;
                agreed=converted;
            }
            return agreed;
        }
        if(pipeline==null || !versions.contains("DataVersion",3))return null;
        int source=versions.getInt("DataVersion");
        if(source<512 || source>1631 || integral(raw.getTag("Count"))==null)return null;
        try {
            NBTTagCompound item=NBTUtil.update(pipeline,net.minecraft.util.datafix.TypeReferences.ITEM_STACK,raw.copy(),source,1631);
            if(!Objects.equals(integral(raw.getTag("Count")),integral(item.getTag("Count"))) || !Objects.equals(raw.getTag("Slot"),item.getTag("Slot")) || !Objects.equals(raw.getTag("ForgeCaps"),item.getTag("ForgeCaps")))return null;
            if(!flatteningSurvives(raw,item,source))return null;
            for(String key:raw.keySet())if(!Arrays.asList("id","Count","Damage","Slot","tag").contains(key) && !Objects.equals(raw.getTag(key),item.getTag(key)))return null;
            return nestedInventoriesSurvive(raw,item)?item:null;
        }catch(RuntimeException invalid){return null;}
    }
    private static boolean flatteningSurvives(INBTBase before,INBTBase after,int source) {
        if(before instanceof NBTTagCompound) {
            if(!(after instanceof NBTTagCompound))return false;
            NBTTagCompound a=(NBTTagCompound)before,b=(NBTTagCompound)after;
            if(a.hasKey("id") && a.hasKey("Count") && source<1451) {
                Long damage=a.hasKey("Damage")?integral(a.getTag("Damage")):0L;
                if(damage==null || damage<0 || damage>32767)return false;
                if(damage!=0) {
                    String mapped=net.minecraft.util.datafix.fixes.ItemStackDataFlattening.updateItem(a.getString("id"),damage.intValue());
                    String base=net.minecraft.util.datafix.fixes.ItemStackDataFlattening.updateItem(a.getString("id"),0);
                    boolean durability=Objects.equals(damage,integral(b.getCompound("tag").getTag("Damage")));
                    // Vanilla silently falls back to variant zero for bad metadata.
                    // Unknown mod metadata has no safe automatic interpretation.
                    if(!durability && (mapped==null || mapped.equals(base)))return false;
                }
            }
            for(String key:a.keySet()) {
                INBTBase old=a.getTag(key),next=b.getTag(key);
                if(key.equals("ench") || key.equals("StoredEnchantments")) {
                    if(!enchantmentsSurvive(old,b.getTag(key.equals("ench")?"Enchantments":key)))return false;
                } else if(next!=null && !flatteningSurvives(old,next,source))return false;
            }
        } else if(before instanceof NBTTagList && after instanceof NBTTagList) {
            NBTTagList a=(NBTTagList)before,b=(NBTTagList)after;
            if(a.size()!=b.size())return false;
            for(int i=0;i<a.size();i++)if(!flatteningSurvives(a.get(i),b.get(i),source))return false;
        }
        return true;
    }
    private static boolean enchantmentsSurvive(INBTBase old,INBTBase next) {
        if(!(old instanceof NBTTagList) || !(next instanceof NBTTagList))return Objects.equals(old,next);
        NBTTagList a=(NBTTagList)old,b=(NBTTagList)next;if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++) {
            if(!(a.get(i) instanceof NBTTagCompound) || !(b.get(i) instanceof NBTTagCompound))return false;
            NBTTagCompound x=a.getCompound(i),y=b.getCompound(i);
            if(!y.contains("id",8) || y.getString("id").equals("null"))return false;
            for(String key:x.keySet())if(!key.equals("id") && !Objects.equals(x.getTag(key),y.getTag(key)))return false;
        }
        return true;
    }
    private static Long integral(INBTBase nbt) {
        return nbt instanceof NBTPrimitive && nbt.getId()>=1 && nbt.getId()<=4 ? ((NBTPrimitive)nbt).getLong() : null;
    }
    private static boolean knownNestedItems(INBTBase tag) {
        if(tag instanceof NBTTagCompound) {
            NBTTagCompound compound=(NBTTagCompound)tag;
            if(compound.hasKey("id") && compound.hasKey("Count")) {
                if(!compound.contains("id",8)) return false;
                ResourceLocation id=new ResourceLocation(compound.getString("id"));
                if(!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id) || Item.getIdFromItem(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id))==0) return false;
            }
            for(String key:compound.keySet()) if(!key.equals("ForgeCaps") && !knownNestedItems(compound.getTag(key))) return false;
        } else if(tag instanceof NBTTagList) {
            NBTTagList list=(NBTTagList)tag;for(int i=0;i<list.size();i++) if(!knownNestedItems(list.get(i))) return false;
        }
        return true;
    }
    private static boolean nestedInventoriesSurvive(INBTBase before,INBTBase after) {
        if(before instanceof NBTTagCompound) {
            if(!(after instanceof NBTTagCompound)) return false;
            NBTTagCompound a=(NBTTagCompound)before,b=(NBTTagCompound)after;
            for(String key:a.keySet()) {
                INBTBase old=a.getTag(key),next=b.getTag(key);
                if((key.equals("Items") || key.equals("Inventory")) && old instanceof NBTTagList) {
                    if(!(next instanceof NBTTagList) || ((NBTTagList)old).size()!=((NBTTagList)next).size()) return false;
                    NBTTagList x=(NBTTagList)old,y=(NBTTagList)next;
                    for(int i=0;i<x.size();i++) if(x.get(i) instanceof NBTTagCompound) {
                        NBTTagCompound p=x.getCompound(i),q=y.getCompound(i);
                        if(!Objects.equals(integral(p.getTag("Count")),integral(q.getTag("Count"))) || !Objects.equals(p.getTag("Slot"),q.getTag("Slot"))) return false;
                    }
                }
                if(next!=null && !nestedInventoriesSurvive(old,next)) return false;
                if(next==null && Arrays.asList("Items","Inventory","Equipment","Aux").contains(key) && old instanceof NBTTagList && ((NBTTagList)old).size()!=0) return false;
            }
        } else if(before instanceof NBTTagList && after instanceof NBTTagList) {
            NBTTagList a=(NBTTagList)before,b=(NBTTagList)after;
            if(a.size()!=b.size()) return false;
            for(int i=0;i<a.size();i++) if(!nestedInventoriesSurvive(a.get(i),b.get(i))) return false;
        }
        return true;
    }
}
