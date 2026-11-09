package cyano.lootable.entities;

import net.minecraft.nbt.*;
import net.minecraft.world.effect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native118EffectIdentityTest {
    private CompoundTag legacyEffect() {
        CompoundTag old=new CompoundTag();
        old.putByte("Id",(byte)16);
        old.putString("forge:id","minecraft:night_vision");
        old.putInt("Duration",200);
        return old;
    }

    @Test void nativeForgeSaveUsesARecognizedStableNamedIdentity() {
        CompoundTag raw=(CompoundTag)MobEffectInstance.CODEC.encodeStart(
                cyano.lootable.buildtest.Native119Registries.lookup().createSerializationContext(NbtOps.INSTANCE),
                new MobEffectInstance(MobEffects.NIGHT_VISION,200,1,false,true,false)).getOrThrow();
        CompoundTag before=raw.copy();
        assertEquals("minecraft:night_vision",raw.getStringOr("id", ""));
        assertEquals(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,null));
        assertEquals(MobEffects.NIGHT_VISION,MobEffectInstance.CODEC.parse(
                cyano.lootable.buildtest.Native119Registries.lookup().createSerializationContext(NbtOps.INSTANCE), raw).getOrThrow().getEffect());
        assertEquals(before,raw);
    }

    @Test void validLegacyNameTakesPrecedenceOverARemappedNumber() {
        CompoundTag raw=legacyEffect();
        raw.putByte("Id",(byte)1);
        CompoundTag before=raw.copy();
        assertEquals(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,1L));
        assertEquals(before,raw);
    }

    @Test void knownNameCanRecoverInvalidLegacyNumbersWithoutMutatingTheRawRecord() {
        CompoundTag raw=legacyEffect();
        raw.putString("Id","damaged legacy fallback");
        CompoundTag before=raw.copy();
        assertEquals(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,null));
        assertEquals(before,raw);
    }

    @Test void malformedAndUnknownNamedIdentitiesRefuseRatherThanActivateAnUnrelatedFallback() {
        for(Tag bad:new Tag[]{IntTag.valueOf(16),new ListTag(),StringTag.valueOf("bad name?!"),StringTag.valueOf("missing:unavailable_effect")}) {
            CompoundTag raw=legacyEffect();
            raw.put("forge:id",bad);
            CompoundTag before=raw.copy();
            assertNull(SavedEffectIdentity.resolve(raw,16L));
            assertEquals(before,raw);
        }
    }

    @Test void legacyOnlyAndNativeEmptyNameFallbacksRemainBounded() {
        CompoundTag raw=legacyEffect();
        raw.remove("forge:id");
        assertEquals(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,16L));
        for(Long bad:new Long[]{null,-1L,34L,256L,4294967312L})assertNull(SavedEffectIdentity.resolve(raw,bad));
        raw.putString("forge:id","");
        assertEquals(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,16L));
    }
}
