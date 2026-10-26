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
    public static CompoundNBT convert(CompoundNBT raw,CompoundNBT versions) {
        if(versions==null || versions.isEmpty())return raw.copy();
        CompoundNBT fixed=convertNbt(raw,versions);
        return fixed!=null && knownNestedItems(fixed)?fixed:null;
    }
    /** NBT conversion is independent of live registry resolution. */
    static CompoundNBT convertNbt(CompoundNBT raw,CompoundNBT versions) {
        if(versions==null || versions.isEmpty()) return raw.copy();
        if(versions.getBoolean("Ambiguous")) {
            CompoundNBT agreed=null;
            for(int candidate:versions.getIntArray("CandidateDataVersions")) {
                CompoundNBT context=versions.copy();context.remove("Ambiguous");context.remove("CandidateDataVersions");context.putInt("DataVersion",candidate);
                CompoundNBT converted=convertNbt(raw,context);
                if(converted==null || (agreed!=null && !agreed.equals(converted)))return null;
                agreed=converted;
            }
            return agreed;
        }
        if(pipeline==null || !versions.contains("DataVersion",3))return null;
        int source=versions.getInt("DataVersion");
        if(source<512 || source>UpgradePreservation.MINECRAFT_VERSION || integral(raw.get("Count"))==null)return null;
        try {
            CompoundNBT item=cyano.lootable.upgrade.NativeNbt.update(pipeline,net.minecraft.util.datafix.TypeReferences.ITEM_STACK,raw.copy(),source,UpgradePreservation.MINECRAFT_VERSION);
            if(!Objects.equals(integral(raw.get("Count")),integral(item.get("Count"))) || !Objects.equals(raw.get("Slot"),item.get("Slot")) || !Objects.equals(raw.get("ForgeCaps"),item.get("ForgeCaps")))return null;
            if(!flatteningSurvives(raw,item,source))return null;
            for(String key:raw.keySet())if(!Arrays.asList("id","Count","Damage","Slot","tag").contains(key) && !Objects.equals(raw.get(key),item.get(key)))return null;
            return nestedInventoriesSurvive(raw,item)?item:null;
        }catch(RuntimeException invalid){return null;}
    }
    private static boolean flatteningSurvives(INBT before,INBT after,int source) {
        if(before instanceof CompoundNBT) {
            if(!(after instanceof CompoundNBT))return false;
            CompoundNBT a=(CompoundNBT)before,b=(CompoundNBT)after;
            if(a.contains("id") && a.contains("Count") && source<1451) {
                Long damage=a.contains("Damage")?integral(a.get("Damage")):0L;
                if(damage==null || damage<0 || damage>32767)return false;
                if(damage!=0) {
                    String mapped=net.minecraft.util.datafix.fixes.ItemStackDataFlattening.updateItem(a.getString("id"),damage.intValue());
                    String base=net.minecraft.util.datafix.fixes.ItemStackDataFlattening.updateItem(a.getString("id"),0);
                    boolean durability=Objects.equals(damage,integral(b.getCompound("tag").get("Damage")));
                    // Vanilla silently falls back to variant zero for bad metadata.
                    // Unknown mod metadata has no safe automatic interpretation.
                    if(!durability && (mapped==null || mapped.equals(base)))return false;
                }
            }
            for(String key:a.keySet()) {
                INBT old=a.get(key),next=b.get(key);
                if(key.equals("ench") || key.equals("StoredEnchantments")) {
                    if(!enchantmentsSurvive(old,b.get(key.equals("ench")?"Enchantments":key)))return false;
                } else if(next!=null && !flatteningSurvives(old,next,source))return false;
            }
        } else if(before instanceof ListNBT && after instanceof ListNBT) {
            ListNBT a=(ListNBT)before,b=(ListNBT)after;
            if(a.size()!=b.size())return false;
            for(int i=0;i<a.size();i++)if(!flatteningSurvives(a.get(i),b.get(i),source))return false;
        }
        return true;
    }
    private static boolean enchantmentsSurvive(INBT old,INBT next) {
        if(!(old instanceof ListNBT) || !(next instanceof ListNBT))return Objects.equals(old,next);
        ListNBT a=(ListNBT)old,b=(ListNBT)next;if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++) {
            if(!(a.get(i) instanceof CompoundNBT) || !(b.get(i) instanceof CompoundNBT))return false;
            CompoundNBT x=a.getCompound(i),y=b.getCompound(i);
            if(!y.contains("id",8) || y.getString("id").equals("null"))return false;
            for(String key:x.keySet())if(!key.equals("id") && !Objects.equals(x.get(key),y.get(key)))return false;
        }
        return true;
    }
    private static Long integral(INBT nbt) {
        return nbt instanceof NumberNBT && nbt.getId()>=1 && nbt.getId()<=4 ? ((NumberNBT)nbt).getLong() : null;
    }
    private static boolean knownNestedItems(INBT tag) {
        if(tag instanceof CompoundNBT) {
            CompoundNBT compound=(CompoundNBT)tag;
            if(compound.contains("id") && compound.contains("Count")) {
                if(!compound.contains("id",8)) return false;
                ResourceLocation id=new ResourceLocation(compound.getString("id"));
                if(!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id) || Item.getIdFromItem(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id))==0) return false;
            }
            for(String key:compound.keySet()) if(!key.equals("ForgeCaps") && !knownNestedItems(compound.get(key))) return false;
        } else if(tag instanceof ListNBT) {
            ListNBT list=(ListNBT)tag;for(int i=0;i<list.size();i++) if(!knownNestedItems(list.get(i))) return false;
        }
        return true;
    }
    private static boolean nestedInventoriesSurvive(INBT before,INBT after) {
        if(before instanceof CompoundNBT) {
            if(!(after instanceof CompoundNBT)) return false;
            CompoundNBT a=(CompoundNBT)before,b=(CompoundNBT)after;
            for(String key:a.keySet()) {
                INBT old=a.get(key),next=b.get(key);
                if((key.equals("Items") || key.equals("Inventory")) && old instanceof ListNBT) {
                    if(!(next instanceof ListNBT) || ((ListNBT)old).size()!=((ListNBT)next).size()) return false;
                    ListNBT x=(ListNBT)old,y=(ListNBT)next;
                    for(int i=0;i<x.size();i++) if(x.get(i) instanceof CompoundNBT) {
                        CompoundNBT p=x.getCompound(i),q=y.getCompound(i);
                        if(!Objects.equals(integral(p.get("Count")),integral(q.get("Count"))) || !Objects.equals(p.get("Slot"),q.get("Slot"))) return false;
                    }
                }
                if(next!=null && !nestedInventoriesSurvive(old,next)) return false;
                if(next==null && Arrays.asList("Items","Inventory","Equipment","Aux").contains(key) && old instanceof ListNBT && ((ListNBT)old).size()!=0) return false;
            }
        } else if(before instanceof ListNBT && after instanceof ListNBT) {
            ListNBT a=(ListNBT)before,b=(ListNBT)after;
            if(a.size()!=b.size()) return false;
            for(int i=0;i<a.size();i++) if(!nestedInventoriesSurvive(a.get(i),b.get(i))) return false;
        }
        return true;
    }
}
