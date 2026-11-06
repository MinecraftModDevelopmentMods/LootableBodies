package cyano.lootable.entities;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.ForgeRegistries;

/** Resolve named effects and the historical vanilla IDs used by the native fixer. */
final class SavedEffectIdentity {
    private static final String[] LEGACY_NAMES={
        "", "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health",
        "instant_damage", "jump_boost", "nausea", "regeneration", "resistance",
        "fire_resistance", "water_breathing", "invisibility", "blindness", "night_vision",
        "hunger", "weakness", "poison", "wither", "health_boost", "absorption", "saturation",
        "glowing", "levitation", "luck", "unluck", "slow_falling", "conduit_power",
        "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness"
    };

    private SavedEffectIdentity() { }

    static Holder<MobEffect> resolve(CompoundTag record,Long legacyId) {
        if(record.contains("id")) {
            if(record.contains("Id") || record.contains("forge:id"))return null;
            return named(record,"id");
        }
        if(record.contains("forge:id")) {
            if(!record.contains("forge:id",8))return null;
            if(!record.getString("forge:id").isEmpty())return named(record,"forge:id");
        }
        if(legacyId==null || legacyId<=0 || legacyId>=LEGACY_NAMES.length)return null;
        return holder(ResourceLocation.withDefaultNamespace(LEGACY_NAMES[legacyId.intValue()]));
    }

    private static Holder<MobEffect> named(CompoundTag record,String field) {
        return record.contains(field,8)?holder(ResourceLocation.tryParse(record.getString(field))):null;
    }

    private static Holder<MobEffect> holder(ResourceLocation id) {
        return id==null?null:ForgeRegistries.MOB_EFFECTS.getHolder(id).orElse(null);
    }
}
