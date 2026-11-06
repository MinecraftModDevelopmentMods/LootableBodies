package cyano.lootable.entities;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/** Stable corpse damage settings across native damage-registry changes. */
final class CorpseDamageRules {
    enum Kind { VOID, ATTACK, ENVIRONMENT, MISC }
    private CorpseDamageRules(){ }
    static Kind kind(DamageSource source) {
        if(source==null)return Kind.MISC;
        if(source.is(DamageTypes.FELL_OUT_OF_WORLD) || "outOfWorld".equals(source.getMsgId()))return Kind.VOID;
        // These two native sources gained a causing entity in1.19.4. They
        // retain the environmental setting used by their former static sources.
        if(source.is(DamageTypes.FALLING_ANVIL) || source.is(DamageTypes.FALLING_BLOCK))return Kind.ENVIRONMENT;
        if(source.getEntity()!=null || source.getDirectEntity()!=null)return Kind.ATTACK;
        if(source.is(DamageTypes.CACTUS) || source.is(DamageTypes.FALL) || source.is(DamageTypes.FLY_INTO_WALL)
                || source.is(DamageTypes.IN_FIRE) || source.is(DamageTypes.LAVA) || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.LIGHTNING_BOLT) || source.is(DamageTypes.ON_FIRE))return Kind.ENVIRONMENT;
        // Older integrations could supply their own non-entity source using
        // one of these message IDs. Keep that original config behavior after
        // the entity-source check, including its case-insensitive comparison.
        for(String name:new String[]{"anvil","cactus","fall","fallingBlock","flyIntoWall",
                "inFire","lava","magic","lightningBolt","onFire"})
            if(name.equalsIgnoreCase(source.getMsgId()))return Kind.ENVIRONMENT;
        return Kind.MISC;
    }
    static boolean isWall(DamageSource source) {
        return source!=null && (source.is(DamageTypes.IN_WALL) || "inWall".equals(source.getMsgId()));
    }
    static boolean allows(DamageSource source,boolean invulnerable,boolean attacks,boolean environment,boolean misc) {
        Kind kind=kind(source);
        if(kind==Kind.VOID)return true;
        if(invulnerable)return false;
        return kind==Kind.ATTACK?attacks:kind==Kind.ENVIRONMENT?environment:misc;
    }
}
