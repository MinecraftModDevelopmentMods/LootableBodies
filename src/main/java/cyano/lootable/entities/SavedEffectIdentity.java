package cyano.lootable.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.ForgeRegistries;

/** Forge40's named identity takes precedence over its legacy byte fallback. */
final class SavedEffectIdentity {
    private SavedEffectIdentity() { }
    static MobEffect resolve(CompoundTag record,Long legacyId) {
        if(record.contains("forge:id")) {
            if(!record.contains("forge:id",8))return null;
            String name=record.getString("forge:id");
            if(!name.isEmpty()) {
                ResourceLocation key=ResourceLocation.tryParse(name);
                return key==null?null:ForgeRegistries.MOB_EFFECTS.getValue(key);
            }
        }
        return legacyId!=null && legacyId>=0 && legacyId<=255?MobEffect.byId(legacyId.intValue()):null;
    }
    static boolean needsLegacyRecovery(CompoundTag record,Long legacyId) {
        return legacyId==null || legacyId<0 || legacyId>255 ||
                (record.contains("forge:id") && record.getString("forge:id").isEmpty());
    }
    static void write(CompoundTag accepted,CompoundTag record,MobEffect effect) {
        accepted.putInt("Id",MobEffect.getId(effect));
        if(record.contains("forge:id",8) && !record.getString("forge:id").isEmpty())
            accepted.putString("forge:id",ForgeRegistries.MOB_EFFECTS.getKey(effect).toString());
    }
}
