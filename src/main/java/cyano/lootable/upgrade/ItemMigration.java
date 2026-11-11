package cyano.lootable.upgrade;

import static cyano.lootable.upgrade.NativeNbt.hasType;
import static cyano.lootable.upgrade.NativeNbt.typedList;
import static cyano.lootable.upgrade.NativeNbt.hasUuid;
import static cyano.lootable.upgrade.NativeNbt.writeUuid;
import java.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
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
    private static final int RELEASED_121_VERSION=3955;
    private static final int RELEASED_12111_VERSION=4671;

    /** Null means the caller retains the original raw record for recovery. */
    public static CompoundTag convert(CompoundTag raw,CompoundTag versions) {
        if(versions==null || versions.isEmpty())return raw.copy();
        if(versions.getBooleanOr("Ambiguous", false)) {
            CompoundTag agreed=null;
            for(int candidate:versions.getIntArray("CandidateDataVersions").orElseGet(() -> new int[0])) {
                CompoundTag context=new CompoundTag();
                context.putInt("DataVersion",candidate);
                CompoundTag fixed=convert(raw,context);
                if(fixed==null || (agreed!=null && !agreed.equals(fixed)))return null;
                agreed=fixed;
            }
            return agreed;
        }
        if(!hasType(versions, "DataVersion", 3))return null;
        int source=versions.getIntOr("DataVersion", 0);
        if(source==UpgradePreservation.MINECRAFT_VERSION)return raw.copy();
        if(source==RELEASED_COMPONENT_VERSION) {
            CompoundTag intermediate = convertReleasedComponents(raw);
            return intermediate == null ? null : convertModernComponents(intermediate,RELEASED_121_VERSION);
        }
        if(source==RELEASED_121_VERSION || source==RELEASED_12111_VERSION)return convertModernComponents(raw,source);
        if(source<512 || source>LAST_LEGACY_ITEM_VERSION || pipeline==null)return null;
        CompoundTag legacy=convertNbt(raw,versions);
        if(legacy==null || !supportedLegacyStack(legacy))return null;
        try {
            CompoundTag input=legacy.copy();
            input.remove("Damage");
            CompoundTag fixed=NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    input,LAST_LEGACY_ITEM_VERSION,RELEASED_121_VERSION);
            return componentsSurvive(legacy,fixed,true)?convertModernComponents(fixed,RELEASED_121_VERSION):null;
        } catch(RuntimeException invalid) {
            return null;
        }
    }

    private static CompoundTag convertReleasedComponents(CompoundTag raw) {
        if(pipeline==null || !Set.of("id","count","components","Slot").containsAll(raw.keySet())
                || !hasType(raw, "id", 8) || Identifier.tryParse(raw.getStringOr("id", ""))==null
                || (raw.contains("components") && !hasType(raw, "components", 10)))return null;
        Long count=raw.contains("count")?integral(raw.get("count")):Long.valueOf(1);
        if(count==null || count<=0 || count>127)return null;
        try {
            CompoundTag fixed=NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    raw.copy(),RELEASED_COMPONENT_VERSION,RELEASED_121_VERSION);
            return componentStackSurvives(raw,fixed)?fixed:null;
        } catch(RuntimeException invalid) {
            return null;
        }
    }

    private static CompoundTag convertModernComponents(CompoundTag raw, int source) {
        if (pipeline == null || !Set.of("id", "count", "components", "Slot").containsAll(raw.keySet())
                || !hasType(raw, "id", 8) || Identifier.tryParse(raw.getStringOr("id", "")) == null
                || (raw.contains("components") && !hasType(raw, "components", 10))) return null;
        Long count = raw.contains("count") ? integral(raw.get("count")) : Long.valueOf(1);
        if (count == null || count <= 0 || count > 127) return null;
        try {
            CompoundTag released = raw;
            if (source == RELEASED_121_VERSION) {
                released = NativeNbt.update(pipeline, net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                        raw.copy(), source, RELEASED_12111_VERSION);
                if (!components121Survive(raw, released)) return null;
            }
            CompoundTag fixed = NativeNbt.update(pipeline, net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                    released.copy(), RELEASED_12111_VERSION, UpgradePreservation.MINECRAFT_VERSION);
            // The 26.1.2 native fixer has no item changes after 1.21.11.
            return released.equals(fixed) ? fixed : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean components121Survive(CompoundTag raw, CompoundTag fixed) {
        if (raw.equals(fixed)) return true;
        CompoundTag expected = raw.copy();
        if (raw.getStringOr("id", "").equals("minecraft:chain")) expected.putString("id", "minecraft:iron_chain");
        if (expected.equals(fixed)) return true;
        if (!equalExcept(expected, fixed, "components")
                || !hasType(expected, "components", 10) || !hasType(fixed, "components", 10)) return false;
        CompoundTag old = expected.getCompoundOrEmpty("components"), updated = fixed.getCompoundOrEmpty("components");
        Set<String> expectedKeys = new HashSet<>(old.keySet());
        Set<String> hidden = new HashSet<>();
        for (String key : List.of("minecraft:attribute_modifiers", "minecraft:enchantments", "minecraft:stored_enchantments")) {
            if (old.get(key) instanceof CompoundTag envelope && envelope.contains("show_in_tooltip")) {
                if (!hasType(envelope, "show_in_tooltip", 1)) return false;
                byte flag = envelope.getByteOr("show_in_tooltip", (byte)-1);
                if (flag != 0 && flag != 1) return false;
                if (flag == 0) hidden.add(key);
            }
        }
        if (!hidden.isEmpty()) {
            if (!expectedKeys.add("minecraft:tooltip_display") || !preservesHiddenComponents(updated, hidden)) return false;
        }
        if (!expectedKeys.equals(updated.keySet())) return false;
        for (String key : old.keySet()) {
            Tag before = old.get(key), after = updated.get(key);
            if (before.equals(after)) continue;
            boolean preserved = switch (key) {
                case "minecraft:custom_name", "minecraft:item_name" -> componentTextSurvives(before, after);
                case "minecraft:lore" -> loreSurvives(before, after);
                case "minecraft:enchantments", "minecraft:stored_enchantments" ->
                    before instanceof CompoundTag levels && unwrappedTooltipField(levels, "levels") instanceof CompoundTag expectedLevels
                            && expectedLevels.equals(after);
                case "minecraft:attribute_modifiers" -> attributeComponentsSurvive(before, after);
                case "minecraft:entity_data" -> entityDataNameSurvives(before, after);
                case "minecraft:container" -> componentItemsSurvive(before, after, true, true);
                case "minecraft:bundle_contents", "minecraft:charged_projectiles" ->
                    componentItemsSurvive(before, after, false, true);
                default -> false;
            };
            if (!preserved) return false;
        }
        return true;
    }

    private static Tag unwrappedTooltipField(CompoundTag envelope, String key) {
        if (!Set.of(key, "show_in_tooltip").containsAll(envelope.keySet())) return null;
        return envelope.get(key);
    }

    private static boolean preservesHiddenComponents(CompoundTag updated, Set<String> hidden) {
        CompoundTag display = updated.getCompoundOrEmpty("minecraft:tooltip_display");
        if (!display.keySet().equals(Set.of("hide_tooltip", "hidden_components"))
                || !hasType(display, "hide_tooltip", 1) || display.getByteOr("hide_tooltip", (byte)-1) != 0) return false;
        ListTag values = typedList(display, "hidden_components", 8);
        if (values.size() != hidden.size()) return false;
        Set<String> actual = new HashSet<>();
        for (Tag value : values) actual.add(((net.minecraft.nbt.StringTag)value).value());
        return actual.equals(hidden);
    }

    private static boolean attributeComponentsSurvive(Tag before, Tag after) {
        if (!(before instanceof CompoundTag envelope)
                || !(unwrappedTooltipField(envelope, "modifiers") instanceof ListTag modifiers)
                || !(after instanceof ListTag updated) || modifiers.size() != updated.size()) return false;
        for (int i = 0; i < modifiers.size(); i++) {
            if (!(modifiers.get(i) instanceof CompoundTag original) || !(updated.get(i) instanceof CompoundTag fixed)) return false;
            if (original.equals(fixed)) continue;
            Identifier id = Identifier.tryParse(original.getStringOr("type", ""));
            Identifier canonical = LegacyRegistryMappings.canonicalAttributeId(id);
            CompoundTag expected = original.copy();
            if (canonical != null && !canonical.equals(id)) expected.putString("type", canonical.toString());
            if (!expected.equals(fixed)) return false;
        }
        return true;
    }

    private static boolean entityDataNameSurvives(Tag before, Tag after) {
        return before instanceof CompoundTag old && after instanceof CompoundTag updated
                && equalExcept(old, updated, "CustomName")
                && updated.contains("CustomName")
                && componentTextSurvives(old.get("CustomName"), updated.get("CustomName"));
    }

    private static boolean componentTextSurvives(Tag before, Tag after) {
        if (!(before instanceof net.minecraft.nbt.StringTag old)) return false;
        var json = com.google.gson.JsonParser.parseString(old.value());
        var expected = com.mojang.serialization.JsonOps.INSTANCE.convertTo(net.minecraft.nbt.NbtOps.INSTANCE, json);
        return expected.equals(after);
    }

    private static boolean loreSurvives(Tag before, Tag after) {
        if (!(before instanceof ListTag old) || !(after instanceof ListTag updated) || old.size() != updated.size()) return false;
        for (int i = 0; i < old.size(); i++) {
            if (!old.get(i).equals(updated.get(i)) && !componentTextSurvives(old.get(i), updated.get(i))) return false;
        }
        return true;
    }

    /** Resolve a legacy modifier name and UUID through the native 3945 mapping. */
    public static Identifier legacyModifierId(UUID uuid,String name) {
        CompoundTag modifier=new CompoundTag();
        writeUuid(modifier, "uuid", uuid);
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
        return converted==null?null:Identifier.tryParse(converted.getCompoundOrEmpty("components")
                .getCompoundOrEmpty("minecraft:attribute_modifiers").getListOrEmpty("modifiers").getCompoundOrEmpty(0).getStringOr("id", ""));
    }

    private static boolean componentStackSurvives(CompoundTag before,CompoundTag after) {
        if(before.equals(after))return true;
        if(!equalExcept(before,after,"components")
                || !hasType(before, "components", 10) || !hasType(after, "components", 10))return false;
        CompoundTag old=before.getCompoundOrEmpty("components"), fixed=after.getCompoundOrEmpty("components");
        if(!old.keySet().equals(fixed.keySet()))return false;
        for(String key:old.keySet()) {
            Tag previous=old.get(key), current=fixed.get(key);
            if(previous.equals(current))continue;
            boolean preserved=switch(key) {
                case "minecraft:attribute_modifiers" -> previous instanceof CompoundTag a && current instanceof CompoundTag b
                        && modifiersSurvive(a,b);
                case "minecraft:container" -> componentItemsSurvive(previous,current,true);
                case "minecraft:bundle_contents","minecraft:charged_projectiles" -> componentItemsSurvive(previous,current,false);
                case "minecraft:food" -> previous instanceof CompoundTag a && current instanceof CompoundTag b
                        && equalExcept(a,b,"using_converts_to") && hasType(a, "using_converts_to", 10)
                        && hasType(b, "using_converts_to", 10)
                        && componentStackSurvives(a.getCompoundOrEmpty("using_converts_to"),b.getCompoundOrEmpty("using_converts_to"));
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
        return componentItemsSurvive(previous, current, indexed, false);
    }

    private static boolean componentItemsSurvive(Tag previous,Tag current,boolean indexed,boolean modern) {
        if(!(previous instanceof ListTag before) || !(current instanceof ListTag after) || before.size()!=after.size())return false;
        for(int i=0;i<before.size();i++) {
            if(!(before.get(i) instanceof CompoundTag a) || !(after.get(i) instanceof CompoundTag b))return false;
            if(indexed) {
                if(!equalExcept(a,b,"item") || !hasType(a, "item", 10) || !hasType(b, "item", 10)
                        || !nestedComponentStackSurvives(a.getCompoundOrEmpty("item"),b.getCompoundOrEmpty("item"),modern))return false;
            } else if(!nestedComponentStackSurvives(a,b,modern))return false;
        }
        return true;
    }

    private static boolean nestedComponentStackSurvives(CompoundTag before, CompoundTag after, boolean modern) {
        return modern ? components121Survive(before, after) : componentStackSurvives(before, after);
    }

    private static boolean modifiersSurvive(CompoundTag before,CompoundTag after) {
        if(!equalExcept(before,after,"modifiers")
                || !(before.get("modifiers") instanceof ListTag old)
                || !(after.get("modifiers") instanceof ListTag fixed)
                || old.size()!=fixed.size())return false;
        Set<Identifier> identities=new HashSet<>();
        for(int i=0;i<old.size();i++) {
            if(!(old.get(i) instanceof CompoundTag a) || !(fixed.get(i) instanceof CompoundTag b))return false;
            Identifier id=hasType(b, "id", 8)?Identifier.tryParse(b.getStringOr("id", "")):null;
            // The native 3945 fix can combine modifiers with colliding names or
            // IDs. Only restore a one-to-one conversion with unchanged values.
            if(a.contains("id") || !hasUuid(a, "uuid") || !hasType(a, "name", 8) || id==null || !identities.add(id)
                    || b.contains("uuid") || b.contains("name") || !equalExcept(a,b,"uuid","name","id"))return false;
        }
        return true;
    }

    /** Validate legacy flattening without running the later component schema. */
    static CompoundTag convertNbt(CompoundTag raw,CompoundTag versions) {
        if(versions==null || versions.isEmpty()) return raw.copy();
        if(versions.getBooleanOr("Ambiguous", false)) {
            CompoundTag agreed=null;
            for(int candidate:versions.getIntArray("CandidateDataVersions").orElseGet(() -> new int[0])) {
                CompoundTag context=versions.copy();context.remove("Ambiguous");context.remove("CandidateDataVersions");context.putInt("DataVersion",candidate);
                CompoundTag converted=convertNbt(raw,context);
                if(converted==null || (agreed!=null && !agreed.equals(converted)))return null;
                agreed=converted;
            }
            return agreed;
        }
        if(pipeline==null || !hasType(versions, "DataVersion", 3))return null;
        int source=versions.getIntOr("DataVersion", 0);
        if(source<512 || source>LAST_LEGACY_ITEM_VERSION || integral(raw.get("Count"))==null)return null;
        try {
            CompoundTag item=cyano.lootable.upgrade.NativeNbt.update(pipeline,net.minecraft.util.datafix.fixes.References.ITEM_STACK,raw.copy(),source,LAST_LEGACY_ITEM_VERSION);
            if(!Objects.equals(integral(raw.get("Count")),integral(item.get("Count"))) || !Objects.equals(raw.get("Slot"),item.get("Slot")) || !Objects.equals(raw.get("ForgeCaps"),item.get("ForgeCaps")))return null;
            if(!flatteningSurvives(raw,item,source))return null;
            for(String key:raw.keySet())if(!Arrays.asList("id","Count","Damage","Slot","tag").contains(key) && !Objects.equals(raw.get(key),item.get(key)))return null;
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
                    String mapped=net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix.updateItem(a.getStringOr("id", ""),damage.intValue());
                    String base=net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix.updateItem(a.getStringOr("id", ""),0);
                    boolean durability=Objects.equals(damage,integral(b.getCompoundOrEmpty("tag").get("Damage")));
                    // Vanilla silently falls back to variant zero for bad metadata.
                    // Unknown mod metadata has no safe automatic interpretation.
                    if(!durability && (mapped==null || mapped.equals(base)))return false;
                }
            }
            for(String key:a.keySet()) {
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
            CompoundTag x=a.getCompoundOrEmpty(i),y=b.getCompoundOrEmpty(i);
            if(!hasType(y, "id", 8) || y.getStringOr("id", "").equals("null"))return false;
            for(String key:x.keySet())if(!key.equals("id") && !Objects.equals(x.get(key),y.get(key)))return false;
        }
        return true;
    }
    private static Long integral(Tag nbt) {
        return nbt instanceof NumericTag && nbt.getId()>=1 && nbt.getId()<=4 ? ((NumericTag)nbt).longValue() : null;
    }
    private static boolean supportedLegacyStack(CompoundTag item) {
        if(!Set.of("id","Count","Damage","Slot","tag").containsAll(item.keySet())
                || !hasType(item, "id", 8) || (item.contains("tag") && !hasType(item, "tag", 10)))return false;
        Long count=integral(item.get("Count"));
        if(count==null || count<=0 || count>127)return false;
        if(item.contains("Damage")) {
            Long old=integral(item.get("Damage"));
            CompoundTag tag=item.getCompoundOrEmpty("tag");
            Long damage=tag.contains("Damage")?integral(tag.get("Damage")):Long.valueOf(0);
            if(!Objects.equals(old,damage))return false;
        }
        return true;
    }

    private static boolean componentsSurvive(CompoundTag legacy,CompoundTag fixed,boolean outerSlot) {
        if(!supportedLegacyStack(legacy)
                || !Objects.equals(integral(legacy.get("Count")),integral(fixed.get("count")))
                || !Objects.equals(Identifier.tryParse(legacy.getStringOr("id", "")),Identifier.tryParse(fixed.getStringOr("id", "")))
                || (outerSlot && !Objects.equals(legacy.get("Slot"),fixed.get("Slot"))))return false;
        CompoundTag tag=legacy.getCompoundOrEmpty("tag"), components=fixed.getCompoundOrEmpty("components");
        CompoundTag custom=components.getCompoundOrEmpty("minecraft:custom_data");
        for(String key:tag.keySet()) {
            Tag value=tag.get(key);
            if(value.equals(custom.get(key)))continue;
            boolean kept=switch(key) {
                case "Damage" -> sameDamage(value,components.get("minecraft:damage"));
                case "Enchantments" -> enchantmentLevelsSurvive(value,components.get("minecraft:enchantments"),components);
                case "StoredEnchantments" -> enchantmentLevelsSurvive(value,components.get("minecraft:stored_enchantments"),components);
                case "display" -> displaySurvives(value,components,custom.getCompoundOrEmpty("display"));
                case "BlockEntityTag" -> blockEntitySurvives(value,components);
                case "EntityTag" -> value.equals(components.get("minecraft:entity_data"));
                case "Potion" -> potionSurvives(value,components.getCompoundOrEmpty("minecraft:potion_contents"));
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
        if(!expected.keySet().equals(encoded.keySet()))return false;
        for(String key:expected.keySet()) {
            Tag before=expected.get(key), after=encoded.get(key);
            if(before.equals(after))continue;
            boolean same=switch(key) {
                case "minecraft:custom_name","minecraft:item_name" -> sameText(before,after);
                case "minecraft:lore" -> sameLore(before,after);
                case "minecraft:tooltip_display" -> sameTooltipDisplay(before, after);
                default -> false;
            };
            if(!same)return false;
        }
        return true;
    }

    private static boolean sameTooltipDisplay(Tag before, Tag after) {
        if (!(before instanceof CompoundTag saved) || !(after instanceof CompoundTag encoded)) return false;
        CompoundTag canonical = saved.copy();
        if (hasType(saved, "hide_tooltip", 1) && saved.getByteOr("hide_tooltip", (byte)-1) == 0) {
            canonical.remove("hide_tooltip");
        }
        return canonical.equals(encoded);
    }

    private static boolean sameLore(Tag before,Tag after) {
        if(!(before instanceof ListTag a) || !(after instanceof ListTag b) || a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!sameText(a.get(i),b.get(i)))return false;
        return true;
    }

    private static boolean sameText(Tag before, Tag after) {
        return nativeLiteralText(before).equals(nativeLiteralText(after));
    }

    private static Tag nativeLiteralText(Tag value) {
        // The native component codec shortens a style-free literal to a string.
        if (value instanceof CompoundTag text && text.keySet().equals(Set.of("text"))
                && hasType(text, "text", 8)) return text.get("text");
        return value;
    }

    private static boolean potionSurvives(Tag raw,CompoundTag contents) {
        if(!(raw instanceof net.minecraft.nbt.StringTag name))return false;
        if(name.value().equals("minecraft:empty"))return !contents.contains("potion");
        return raw.equals(contents.get("potion"));
    }

    private static boolean sameDamage(Tag input,Tag output) {
        Long value=integral(input);
        return value!=null && value>=0 && value<=32767
                && (output==null?value==0:Objects.equals(value,integral(output)));
    }

    private static boolean enchantmentLevelsSurvive(Tag raw,Tag fixed,CompoundTag components) {
        if(!(raw instanceof ListTag list))return false;
        if(list.isEmpty())return components.getBooleanOr("minecraft:enchantment_glint_override", false);
        if(!(fixed instanceof CompoundTag compound))return false;
        CompoundTag levels=compound.getCompoundOrEmpty("levels");
        if(levels.size()!=list.size())return false;
        Set<String> seen=new HashSet<>();
        for(Tag element:list) {
            if(!(element instanceof CompoundTag enchantment)
                    || !Set.of("id","lvl").containsAll(enchantment.keySet())
                    || !hasType(enchantment, "id", 8))return false;
            String name=enchantment.getStringOr("id", "");
            Long level=integral(enchantment.get("lvl"));
            if(!seen.add(name) || level==null || level<0 || level>255
                    || !Objects.equals(level,integral(levels.get(name))))return false;
        }
        return true;
    }

    private static boolean displaySurvives(Tag raw,CompoundTag components,CompoundTag custom) {
        if(!(raw instanceof CompoundTag display))return false;
        for(String key:display.keySet()) {
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
        CompoundTag remainder=components.getCompoundOrEmpty("minecraft:block_entity_data");
        ListTag container=typedList(components, "minecraft:container", 10);
        for(String key:block.keySet()) {
            Tag value=block.get(key);
            if(value.equals(remainder.get(key)))continue;
            if(key.equals("Base")) {
                Long colour=integral(value);
                if(colour==null || colour<0 || colour>15
                        || !components.getStringOr("minecraft:base_color", "").equals(net.minecraft.world.item.DyeColor.byId(colour.intValue()).getName()))
                    return false;
                continue;
            }
            if(key.equals("Items") && value instanceof ListTag items) {
                if(items.size()!=container.size())return false;
                Set<Long> slots=new HashSet<>();
                for(int i=0;i<items.size();i++) {
                    CompoundTag legacy=items.getCompoundOrEmpty(i), fixed=container.getCompoundOrEmpty(i);
                    Long slot=integral(legacy.get("Slot"));
                    if(slot==null || slot<0 || slot>255 || !slots.add(slot)
                            || !Objects.equals(slot,integral(fixed.get("slot")))
                            || !componentsSurvive(legacy,fixed.getCompoundOrEmpty("item"),false))return false;
                }
            } else if(!key.equals("id") || !remainder.isEmpty()
                    || (!block.getStringOr("id", "").startsWith("minecraft:") && !block.getStringOr("id", "").equals("Chest")))return false;
        }
        return true;
    }

    private static boolean nestedInventoriesSurvive(Tag before,Tag after) {
        if(before instanceof CompoundTag) {
            if(!(after instanceof CompoundTag)) return false;
            CompoundTag a=(CompoundTag)before,b=(CompoundTag)after;
            for(String key:a.keySet()) {
                Tag old=a.get(key),next=b.get(key);
                if((key.equals("Items") || key.equals("Inventory")) && old instanceof ListTag) {
                    if(!(next instanceof ListTag) || ((ListTag)old).size()!=((ListTag)next).size()) return false;
                    ListTag x=(ListTag)old,y=(ListTag)next;
                    for(int i=0;i<x.size();i++) if(x.get(i) instanceof CompoundTag) {
                        CompoundTag p=x.getCompoundOrEmpty(i),q=y.getCompoundOrEmpty(i);
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
