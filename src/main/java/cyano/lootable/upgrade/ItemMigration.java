package cyano.lootable.upgrade;
import java.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import com.mojang.datafixers.DataFixer;
/** Runs native item fixes on copies, retaining ambiguous originals. */
public final class ItemMigration {
    private static DataFixer pipeline;
    private ItemMigration() { }
    static void register(DataFixer fixer){pipeline=fixer;}
    /** Null means that conversion is ambiguous; retain the original raw record. */
    public static CompoundTag convert(CompoundTag raw,CompoundTag versions) {
        if(versions==null || versions.isEmpty())return raw.copy();
        CompoundTag fixed=convertNbt(raw,versions);
        return fixed!=null && knownNestedItems(fixed)?fixed:null;
    }
    /** NBT conversion is independent of live registry resolution. */
    static CompoundTag convertNbt(CompoundTag raw,CompoundTag versions) {
        if(versions==null || versions.isEmpty()) return raw.copy();
        if(versions.getBoolean("Ambiguous")) {
            CompoundTag agreed=null;
            for(int candidate:versions.getIntArray("CandidateDataVersions")) {
                CompoundTag context=versions.copy();context.remove("Ambiguous");context.remove("CandidateDataVersions");context.putInt("DataVersion",candidate);
                CompoundTag converted=convertNbt(raw,context);
                if(converted==null || (agreed!=null && !agreed.equals(converted)))return null;
                agreed=converted;
            }
            return agreed;
        }
        if(pipeline==null || !versions.contains("DataVersion",3))return null;
        int source=versions.getInt("DataVersion");
        if(source<512 || source>UpgradePreservation.MINECRAFT_VERSION || integral(raw.get("Count"))==null)return null;
        try {
            CompoundTag item=cyano.lootable.upgrade.NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,raw.copy(),source,UpgradePreservation.MINECRAFT_VERSION);
            if(!Objects.equals(integral(raw.get("Count")),integral(item.get("Count"))) || !Objects.equals(raw.get("Slot"),item.get("Slot")) || !Objects.equals(raw.get("ForgeCaps"),item.get("ForgeCaps")))return null;
            if(!flatteningSurvives(raw,item,source))return null;
            for(String key:raw.getAllKeys())if(!Arrays.asList("id","Count","Damage","Slot","tag").contains(key) && !Objects.equals(raw.get(key),item.get(key)))return null;
            return nestedInventoriesSurvive(raw,item)?item:null;
        }catch(RuntimeException invalid){return null;}
    }
    private static boolean flatteningSurvives(Tag before,Tag after,int source) {
        if(before instanceof CompoundTag) {
            if(!(after instanceof CompoundTag))return false;
            CompoundTag a=(CompoundTag)before,b=(CompoundTag)after;
            if(a.contains("id") && a.contains("Count") && source<1451) {
                Long damage=a.contains("Damage")?integral(a.get("Damage")):0L;
                if(damage==null || damage<0 || damage>32767)return false;
                if(damage!=0) {
                    String mapped=net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix.updateItem(a.getString("id"),damage.intValue());
                    String base=net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix.updateItem(a.getString("id"),0);
                    boolean durability=Objects.equals(damage,integral(b.getCompound("tag").get("Damage")));
                    // Vanilla silently falls back to variant zero for bad metadata.
                    // Unknown mod metadata has no safe automatic interpretation.
                    if(!durability && (mapped==null || mapped.equals(base)))return false;
                }
            }
            for(String key:a.getAllKeys()) {
                Tag old=a.get(key),next=b.get(key);
                if(key.equals("ench") || key.equals("StoredEnchantments")) {
                    if(!enchantmentsSurvive(old,b.get(key.equals("ench")?"Enchantments":key)))return false;
                } else if(next!=null && !flatteningSurvives(old,next,source))return false;
            }
        } else if(before instanceof ListTag && after instanceof ListTag) {
            ListTag a=(ListTag)before,b=(ListTag)after;
            if(a.size()!=b.size())return false;
            for(int i=0;i<a.size();i++)if(!flatteningSurvives(a.get(i),b.get(i),source))return false;
        }
        return true;
    }
    private static boolean enchantmentsSurvive(Tag old,Tag next) {
        if(!(old instanceof ListTag) || !(next instanceof ListTag))return Objects.equals(old,next);
        ListTag a=(ListTag)old,b=(ListTag)next;if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++) {
            if(!(a.get(i) instanceof CompoundTag) || !(b.get(i) instanceof CompoundTag))return false;
            CompoundTag x=a.getCompound(i),y=b.getCompound(i);
            if(!y.contains("id",8) || y.getString("id").equals("null"))return false;
            for(String key:x.getAllKeys())if(!key.equals("id") && !Objects.equals(x.get(key),y.get(key)))return false;
        }
        return true;
    }
    private static Long integral(Tag nbt) {
        return nbt instanceof NumericTag && nbt.getId()>=1 && nbt.getId()<=4 ? ((NumericTag)nbt).getAsLong() : null;
    }
    private static boolean knownNestedItems(Tag tag) {
        if(tag instanceof CompoundTag) {
            CompoundTag compound=(CompoundTag)tag;
            if(compound.contains("id") && compound.contains("Count")) {
                if(!compound.contains("id",8)) return false;
                ResourceLocation id=new ResourceLocation(compound.getString("id"));
                if(!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id) || Item.getId(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id))==0) return false;
            }
            for(String key:compound.getAllKeys()) if(!key.equals("ForgeCaps") && !knownNestedItems(compound.get(key))) return false;
        } else if(tag instanceof ListTag) {
            ListTag list=(ListTag)tag;for(int i=0;i<list.size();i++) if(!knownNestedItems(list.get(i))) return false;
        }
        return true;
    }
    private static boolean nestedInventoriesSurvive(Tag before,Tag after) {
        if(before instanceof CompoundTag) {
            if(!(after instanceof CompoundTag)) return false;
            CompoundTag a=(CompoundTag)before,b=(CompoundTag)after;
            for(String key:a.getAllKeys()) {
                Tag old=a.get(key),next=b.get(key);
                if((key.equals("Items") || key.equals("Inventory")) && old instanceof ListTag) {
                    if(!(next instanceof ListTag) || ((ListTag)old).size()!=((ListTag)next).size()) return false;
                    ListTag x=(ListTag)old,y=(ListTag)next;
                    for(int i=0;i<x.size();i++) if(x.get(i) instanceof CompoundTag) {
                        CompoundTag p=x.getCompound(i),q=y.getCompound(i);
                        if(!Objects.equals(integral(p.get("Count")),integral(q.get("Count"))) || !Objects.equals(p.get("Slot"),q.get("Slot"))) return false;
                    }
                }
                if(next!=null && !nestedInventoriesSurvive(old,next)) return false;
                if(next==null && Arrays.asList("Items","Inventory","Equipment","Aux").contains(key) && old instanceof ListTag && ((ListTag)old).size()!=0) return false;
            }
        } else if(before instanceof ListTag && after instanceof ListTag) {
            ListTag a=(ListTag)before,b=(ListTag)after;
            if(a.size()!=b.size()) return false;
            for(int i=0;i<a.size();i++) if(!nestedInventoriesSurvive(a.get(i),b.get(i))) return false;
        }
        return true;
    }
}
