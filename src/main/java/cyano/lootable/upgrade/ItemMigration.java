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
    // The native componentization fix is in schema 3818.5. Validate the
    // legacy item format through the last complete schema before that change.
    private static final int LAST_LEGACY_ITEM_VERSION=3817;
    private static final int RELEASED_COMPONENT_VERSION=3839;

    /** Null means the caller retains the original raw record for recovery. */
    public static CompoundTag convert(CompoundTag raw,CompoundTag versions) {
        if(versions==null || versions.isEmpty())return raw.copy();
        if(versions.getBoolean("Ambiguous")) {
            CompoundTag agreed=null;
            for(int candidate:versions.getIntArray("CandidateDataVersions")) {
                CompoundTag context=new CompoundTag();
                context.putInt("DataVersion",candidate);
                CompoundTag fixed=convert(raw,context);
                if(fixed==null || (agreed!=null && !agreed.equals(fixed)))return null;
                agreed=fixed;
            }
            return agreed;
        }
        if(!versions.contains("DataVersion",3))return null;
        int source=versions.getInt("DataVersion");
        if(source==UpgradePreservation.MINECRAFT_VERSION)return raw.copy();
        if(source==RELEASED_COMPONENT_VERSION)return convertReleasedComponents(raw);
        if(source<512 || source>LAST_LEGACY_ITEM_VERSION || pipeline==null)return null;
        CompoundTag legacy=convertNbt(raw,versions);
        if(legacy==null || !supportedLegacyStack(legacy))return null;
        try {
            CompoundTag input=legacy.copy();
            input.remove("Damage");
            CompoundTag fixed=NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    input,LAST_LEGACY_ITEM_VERSION,UpgradePreservation.MINECRAFT_VERSION);
            return componentsSurvive(legacy,fixed,true)?fixed:null;
        } catch(RuntimeException invalid) {
            return null;
        }
    }

    private static CompoundTag convertReleasedComponents(CompoundTag raw) {
        if(pipeline==null || !Set.of("id","count","components","Slot").containsAll(raw.getAllKeys())
                || !raw.contains("id",8) || ResourceLocation.tryParse(raw.getString("id"))==null
                || (raw.contains("components") && !raw.contains("components",10)))return null;
        Long count=raw.contains("count")?integral(raw.get("count")):Long.valueOf(1);
        if(count==null || count<=0 || count>127)return null;
        try {
            CompoundTag fixed=NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    raw.copy(),RELEASED_COMPONENT_VERSION,UpgradePreservation.MINECRAFT_VERSION);
            return componentStackSurvives(raw,fixed)?fixed:null;
        } catch(RuntimeException invalid) {
            return null;
        }
    }

    /** Resolve a legacy modifier name and UUID through the native 3945 mapping. */
    public static ResourceLocation legacyModifierId(UUID uuid,String name) {
        CompoundTag modifier=new CompoundTag();
        modifier.putUUID("uuid",uuid);
        modifier.putString("name",name);
        ListTag modifiers=new ListTag();
        modifiers.add(modifier);
        CompoundTag attributes=new CompoundTag(), components=new CompoundTag(), carrier=new CompoundTag();
        attributes.put("modifiers",modifiers);
        components.put("minecraft:attribute_modifiers",attributes);
        carrier.putString("id","minecraft:stick");
        carrier.putInt("count",1);
        carrier.put("components",components);
        CompoundTag converted=convertReleasedComponents(carrier);
        return converted==null?null:ResourceLocation.tryParse(converted.getCompound("components")
                .getCompound("minecraft:attribute_modifiers").getList("modifiers",10).getCompound(0).getString("id"));
    }

    private static boolean componentStackSurvives(CompoundTag before,CompoundTag after) {
        if(before.equals(after))return true;
        if(!equalExcept(before,after,"components")
                || !before.contains("components",10) || !after.contains("components",10))return false;
        CompoundTag old=before.getCompound("components"), fixed=after.getCompound("components");
        if(!old.getAllKeys().equals(fixed.getAllKeys()))return false;
        for(String key:old.getAllKeys()) {
            Tag previous=old.get(key), current=fixed.get(key);
            if(previous.equals(current))continue;
            boolean preserved=switch(key) {
                case "minecraft:attribute_modifiers" -> previous instanceof CompoundTag a && current instanceof CompoundTag b
                        && modifiersSurvive(a,b);
                case "minecraft:container" -> componentItemsSurvive(previous,current,true);
                case "minecraft:bundle_contents","minecraft:charged_projectiles" -> componentItemsSurvive(previous,current,false);
                case "minecraft:food" -> previous instanceof CompoundTag a && current instanceof CompoundTag b
                        && equalExcept(a,b,"using_converts_to") && a.contains("using_converts_to",10)
                        && b.contains("using_converts_to",10)
                        && componentStackSurvives(a.getCompound("using_converts_to"),b.getCompound("using_converts_to"));
                default -> false;
            };
            if(!preserved)return false;
        }
        return true;
    }

    private static boolean equalExcept(CompoundTag before,CompoundTag after,String... fields) {
        CompoundTag old=before.copy(), fixed=after.copy();
        for(String field:fields) {
            old.remove(field);
            fixed.remove(field);
        }
        return old.equals(fixed);
    }

    private static boolean componentItemsSurvive(Tag previous,Tag current,boolean indexed) {
        if(!(previous instanceof ListTag before) || !(current instanceof ListTag after) || before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++) {
            if(!(before.get(i) instanceof CompoundTag a) || !(after.get(i) instanceof CompoundTag b))return false;
            if(indexed) {
                if(!equalExcept(a,b,"item") || !a.contains("item",10) || !b.contains("item",10)
                        || !componentStackSurvives(a.getCompound("item"),b.getCompound("item")))return false;
            } else if(!componentStackSurvives(a,b))return false;
        }
        return true;
    }

    private static boolean modifiersSurvive(CompoundTag before,CompoundTag after) {
        if(!equalExcept(before,after,"modifiers")
                || !(before.get("modifiers") instanceof ListTag old)
                || !(after.get("modifiers") instanceof ListTag fixed)
                || old.size()!=fixed.size())return false;
        Set<ResourceLocation> identities=new HashSet<>();
        for(int i=0;i<old.size();i++) {
            if(!(old.get(i) instanceof CompoundTag a) || !(fixed.get(i) instanceof CompoundTag b))return false;
            ResourceLocation id=b.contains("id",8)?ResourceLocation.tryParse(b.getString("id")):null;
            // The native 3945 fix can combine modifiers with colliding names or
            // IDs. Only restore a one-to-one conversion with unchanged values.
            if(a.contains("id") || !a.hasUUID("uuid") || !a.contains("name",8) || id==null || !identities.add(id)
                    || b.contains("uuid") || b.contains("name") || !equalExcept(a,b,"uuid","name","id"))return false;
        }
        return true;
    }

    /** Validate legacy flattening without running the later component schema. */
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
        if(source<512 || source>LAST_LEGACY_ITEM_VERSION || integral(raw.get("Count"))==null)return null;
        try {
            CompoundTag item=cyano.lootable.upgrade.NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,raw.copy(),source,LAST_LEGACY_ITEM_VERSION);
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
    private static boolean supportedLegacyStack(CompoundTag item) {
        if(!Set.of("id","Count","Damage","Slot","tag").containsAll(item.getAllKeys())
                || !item.contains("id",8) || (item.contains("tag") && !item.contains("tag",10)))return false;
        Long count=integral(item.get("Count"));
        if(count==null || count<=0 || count>127)return false;
        if(item.contains("Damage")) {
            Long old=integral(item.get("Damage"));
            CompoundTag tag=item.getCompound("tag");
            Long damage=tag.contains("Damage")?integral(tag.get("Damage")):Long.valueOf(0);
            if(!Objects.equals(old,damage))return false;
        }
        return true;
    }

    private static boolean componentsSurvive(CompoundTag legacy,CompoundTag fixed,boolean outerSlot) {
        if(!supportedLegacyStack(legacy)
                || !Objects.equals(integral(legacy.get("Count")),integral(fixed.get("count")))
                || !Objects.equals(ResourceLocation.tryParse(legacy.getString("id")),ResourceLocation.tryParse(fixed.getString("id")))
                || (outerSlot && !Objects.equals(legacy.get("Slot"),fixed.get("Slot"))))return false;
        CompoundTag tag=legacy.getCompound("tag"), components=fixed.getCompound("components");
        CompoundTag custom=components.getCompound("minecraft:custom_data");
        for(String key:tag.getAllKeys()) {
            Tag value=tag.get(key);
            if(value.equals(custom.get(key)))continue;
            boolean kept=switch(key) {
                case "Damage" -> sameDamage(value,components.get("minecraft:damage"));
                case "Enchantments" -> enchantmentLevelsSurvive(value,components.get("minecraft:enchantments"),components);
                case "StoredEnchantments" -> enchantmentLevelsSurvive(value,components.get("minecraft:stored_enchantments"),components);
                case "display" -> displaySurvives(value,components,custom.getCompound("display"));
                case "BlockEntityTag" -> blockEntitySurvives(value,components);
                case "EntityTag" -> value.equals(components.get("minecraft:entity_data"));
                case "Potion" -> potionSurvives(value,components.getCompound("minecraft:potion_contents"));
                case "RepairCost" -> sameDamage(value,components.get("minecraft:repair_cost"));
                case "CustomModelData" -> value.equals(components.get("minecraft:custom_model_data"));
                default -> false;
            };
            if(!kept)return false;
        }
        return true;
    }

    public static boolean sameSavedComponents(CompoundTag expected,CompoundTag encoded) {
        if(expected.equals(encoded))return true;
        if(!expected.getAllKeys().equals(encoded.getAllKeys()))return false;
        for(String key:expected.getAllKeys()) {
            Tag before=expected.get(key), after=encoded.get(key);
            if(before.equals(after))continue;
            boolean same=switch(key) {
                case "minecraft:custom_name","minecraft:item_name" -> sameText(before,after);
                case "minecraft:lore" -> sameLore(before,after);
                default -> false;
            };
            if(!same)return false;
        }
        return true;
    }

    private static boolean sameLore(Tag before,Tag after) {
        if(!(before instanceof ListTag a) || !(after instanceof ListTag b) || a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!sameText(a.get(i),b.get(i)))return false;
        return true;
    }

    private static boolean sameText(Tag before,Tag after) {
        if(before.equals(after))return true;
        if(!(before instanceof net.minecraft.nbt.StringTag a) || !(after instanceof net.minecraft.nbt.StringTag b))return false;
        try {
            return textShape(a.getAsString()).equals(textShape(b.getAsString()));
        } catch(com.google.gson.JsonParseException malformed) {
            return false;
        }
    }

    private static com.google.gson.JsonElement textShape(String json) {
        // The native codec writes a plain {"text":"..."} as a JSON string.
        // Compare JSON structurally, retaining all styling and unknown fields.
        var value=com.google.gson.JsonParser.parseString(json);
        if(value instanceof com.google.gson.JsonObject object && object.size()==1
                && object.get("text") instanceof com.google.gson.JsonPrimitive literal && literal.isString())return literal;
        return value;
    }

    private static boolean potionSurvives(Tag raw,CompoundTag contents) {
        if(!(raw instanceof net.minecraft.nbt.StringTag name))return false;
        if(name.getAsString().equals("minecraft:empty"))return !contents.contains("potion");
        return raw.equals(contents.get("potion"));
    }

    private static boolean sameDamage(Tag input,Tag output) {
        Long value=integral(input);
        return value!=null && value>=0 && value<=32767
                && (output==null?value==0:Objects.equals(value,integral(output)));
    }

    private static boolean enchantmentLevelsSurvive(Tag raw,Tag fixed,CompoundTag components) {
        if(!(raw instanceof ListTag list))return false;
        if(list.isEmpty())return components.getBoolean("minecraft:enchantment_glint_override");
        if(!(fixed instanceof CompoundTag compound))return false;
        CompoundTag levels=compound.getCompound("levels");
        if(levels.size()!=list.size())return false;
        Set<String> seen=new HashSet<>();
        for(Tag element:list) {
            if(!(element instanceof CompoundTag enchantment)
                    || !Set.of("id","lvl").containsAll(enchantment.getAllKeys())
                    || !enchantment.contains("id",8))return false;
            String name=enchantment.getString("id");
            Long level=integral(enchantment.get("lvl"));
            if(!seen.add(name) || level==null || level<0 || level>255
                    || !Objects.equals(level,integral(levels.get(name))))return false;
        }
        return true;
    }

    private static boolean displaySurvives(Tag raw,CompoundTag components,CompoundTag custom) {
        if(!(raw instanceof CompoundTag display))return false;
        for(String key:display.getAllKeys()) {
            Tag value=display.get(key);
            if(value.equals(custom.get(key)))continue;
            String component=switch(key) {
                case "Name" -> "minecraft:custom_name";
                case "Lore" -> "minecraft:lore";
                default -> null;
            };
            if(component==null || !value.equals(components.get(component)))return false;
        }
        return true;
    }

    private static boolean blockEntitySurvives(Tag raw,CompoundTag components) {
        if(!(raw instanceof CompoundTag block))return false;
        CompoundTag remainder=components.getCompound("minecraft:block_entity_data");
        ListTag container=components.getList("minecraft:container",10);
        for(String key:block.getAllKeys()) {
            Tag value=block.get(key);
            if(value.equals(remainder.get(key)))continue;
            if(key.equals("Base")) {
                Long colour=integral(value);
                if(colour==null || colour<0 || colour>15
                        || !components.getString("minecraft:base_color").equals(net.minecraft.world.item.DyeColor.byId(colour.intValue()).getName()))
                    return false;
                continue;
            }
            if(key.equals("Items") && value instanceof ListTag items) {
                if(items.size()!=container.size())return false;
                Set<Long> slots=new HashSet<>();
                for(int i=0;i<items.size();i++) {
                    CompoundTag legacy=items.getCompound(i), fixed=container.getCompound(i);
                    Long slot=integral(legacy.get("Slot"));
                    if(slot==null || slot<0 || slot>255 || !slots.add(slot)
                            || !Objects.equals(slot,integral(fixed.get("slot")))
                            || !componentsSurvive(legacy,fixed.getCompound("item"),false))return false;
                }
            } else if(!key.equals("id") || !remainder.isEmpty()
                    || (!block.getString("id").startsWith("minecraft:") && !block.getString("id").equals("Chest")))return false;
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
