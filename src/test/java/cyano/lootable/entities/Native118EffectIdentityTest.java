package cyano.lootable.entities;

import net.minecraft.nbt.*;
import net.minecraft.world.effect.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native118EffectIdentityTest {
    private CompoundTag nativeEffect(){return new MobEffectInstance(MobEffects.NIGHT_VISION,200,1,false,true,false).save(new CompoundTag());}
    @Test void nativeForgeSaveUsesARecognizedStableNamedIdentity() {
        CompoundTag raw=nativeEffect(),before=raw.copy();assertEquals("minecraft:night_vision",raw.getString("forge:id"));
        assertSame(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,16L));assertFalse(SavedEffectIdentity.needsLegacyRecovery(raw,16L));
        CompoundTag accepted=new CompoundTag();SavedEffectIdentity.write(accepted,raw,MobEffects.NIGHT_VISION);
        assertEquals(raw.get("Id"),accepted.get("Id"));assertEquals(raw.get("forge:id"),accepted.get("forge:id"));assertEquals(before,raw);
    }
    @Test void validatedNameFollowsActualNativePrecedenceWhenLegacyNumbersWereRemapped() {
        CompoundTag raw=nativeEffect();raw.putByte("Id",(byte)MobEffect.getId(MobEffects.MOVEMENT_SPEED));
        assertSame(MobEffects.NIGHT_VISION,MobEffectInstance.load(raw).getEffect());
        assertSame(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,1L));assertFalse(SavedEffectIdentity.needsLegacyRecovery(raw,1L));
    }
    @Test void knownNameCanRecoverInvalidLegacyNumbersWithoutDiscardingTheRawRecord() {
        CompoundTag raw=nativeEffect();raw.putString("Id","damaged legacy fallback");
        assertSame(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,null));assertTrue(SavedEffectIdentity.needsLegacyRecovery(raw,null));
    }
    @Test void malformedAndUnknownNamedIdentitiesRefuseRatherThanActivateAnUnrelatedFallback() {
        for(Tag bad:new Tag[]{IntTag.valueOf(16),new ListTag(),StringTag.valueOf("bad name?!"),StringTag.valueOf("missing:unavailable_effect")}) {
            CompoundTag raw=nativeEffect();raw.put("forge:id",bad);CompoundTag before=raw.copy();
            assertNull(SavedEffectIdentity.resolve(raw,16L));assertEquals(before,raw);
        }
    }
    @Test void legacyOnlyAndNativeEmptyNameFallbacksRemainBounded() {
        CompoundTag raw=nativeEffect();raw.remove("forge:id");assertSame(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,16L));
        for(Long bad:new Long[]{null,-1L,256L,4294967312L})assertNull(SavedEffectIdentity.resolve(raw,bad));
        raw.putString("forge:id","");assertSame(MobEffects.NIGHT_VISION,SavedEffectIdentity.resolve(raw,16L));assertTrue(SavedEffectIdentity.needsLegacyRecovery(raw,16L));
    }
}
