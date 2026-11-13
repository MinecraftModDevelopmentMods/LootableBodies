package cyano.lootable.entities;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.damagesource.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native45 damage identities must retain the historical config categories. */
class Native119DamageTest {
    private static DamageSources sources;
    private static Entity entity;
    private static Holder<DamageType> custom;
    @BeforeAll static void nativeSources() throws ReflectiveOperationException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        MappedRegistry<DamageType> registry=new MappedRegistry<>(Registries.DAMAGE_TYPE,Lifecycle.stable());
        DamageTypes.bootstrap(new BootstrapContext<DamageType>() {
            public Holder.Reference<DamageType> register(ResourceKey<DamageType> key,DamageType value,Lifecycle life) {
                return registry.register(key,value,new net.minecraft.core.RegistrationInfo(java.util.Optional.empty(),life));
            }
            public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> key) {
                throw new AssertionError("Damage type bootstrap unexpectedly needs another registry: "+key);
            }
        });
        // Deliberately shares the vanilla anvil message, but has its own mod identity.
        custom=registry.register(ResourceKey.create(Registries.DAMAGE_TYPE,Identifier.parse("example:custom")),
                new DamageType("anvil",0),net.minecraft.core.RegistrationInfo.BUILT_IN);
        registry.freeze();
        sources=new DamageSources(new RegistryAccess.ImmutableRegistryAccess(java.util.List.of(registry)));
        // No world/entity behavior is used here. Plain JUnit has no loaded
        // Forge fluid-type registry, so a real entity constructor requires the
        // packaged runtime. This token is only an identity passed by native
        // DamageSources factories, not proof of entity construction/gameplay.
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field=unsafeType.getDeclaredField("theUnsafe");field.setAccessible(true);
        entity=(Entity)unsafeType.getMethod("allocateInstance",Class.class).invoke(field.get(null),SourceEntity.class);
    }
    @Test void fallingNativeHazardsStayEnvironmentalDespiteTheirEntity() {
        for(DamageSource source:new DamageSource[]{sources.anvil(entity),sources.fallingBlock(entity)}) {
            assertSame(entity,source.getEntity());
            assertEquals(CorpseDamageRules.Kind.ENVIRONMENT,CorpseDamageRules.kind(source));
            assertTrue(CorpseDamageRules.allows(source,false,false,true,false));
            assertFalse(CorpseDamageRules.allows(source,false,true,false,true));
        }
    }
    @Test void originalEnvironmentalSourcesRetainTheirCategory() {
        for(DamageSource source:new DamageSource[]{sources.inFire(),sources.lightningBolt(),sources.onFire(),
                sources.lava(),sources.cactus(),sources.fall(),sources.flyIntoWall(),sources.magic()})
            assertEquals(CorpseDamageRules.Kind.ENVIRONMENT,CorpseDamageRules.kind(source),source.toString());
    }
    @Test void nativeIndirectDamageWithNoCausingEntityIsStillAnAttack() {
        DamageSource source=sources.thrown(entity,null);
        assertNull(source.getEntity());assertSame(entity,source.getDirectEntity());
        assertEquals(CorpseDamageRules.Kind.ATTACK,CorpseDamageRules.kind(source));
    }
    @Test void nativeDirectAttacksAndCustomEntitySourcesStayAttacks() {
        assertEquals(CorpseDamageRules.Kind.ATTACK,CorpseDamageRules.kind(sources.thorns(entity)));
        assertEquals(CorpseDamageRules.Kind.ATTACK,CorpseDamageRules.kind(new DamageSource(custom,entity)));
    }
    @Test void customNonEntityMessageRetainsHistoricalCategory() {
        assertEquals("anvil",custom.value().msgId());
        assertEquals(CorpseDamageRules.Kind.ENVIRONMENT,CorpseDamageRules.kind(new DamageSource(custom)));
    }
    @Test void historicalMessageFallbackKeepsItsOriginalCaseAndEntityPrecedence() {
        for(String name:new String[]{"anvil","CACTUS","fall","fallingBlock","flyIntoWall","inFire","lava","magic","lightningBolt","onFire"}) {
            Holder<DamageType> type=Holder.direct(new DamageType(name,0));
            assertEquals(CorpseDamageRules.Kind.ENVIRONMENT,CorpseDamageRules.kind(new DamageSource(type)),name);
            assertEquals(CorpseDamageRules.Kind.ATTACK,CorpseDamageRules.kind(new DamageSource(type,entity)),name);
        }
    }
    @Test void customVoidAndWallMessagesRetainTheirSpecialHistoricalHandling() {
        DamageSource voidSource=new DamageSource(Holder.direct(new DamageType("outOfWorld",0)),entity);
        assertTrue(CorpseDamageRules.allows(voidSource,true,false,false,false));
        assertTrue(CorpseDamageRules.isWall(new DamageSource(Holder.direct(new DamageType("inWall",0)))));
        assertTrue(CorpseDamageRules.isWall(sources.inWall()));
        assertFalse(CorpseDamageRules.isWall(new DamageSource(Holder.direct(new DamageType("INWALL",0)))));
        assertFalse(CorpseDamageRules.isWall(null));
    }
    @Test void voidBypassesEveryDamageToggleAndCompleteInvulnerability() {
        assertEquals(CorpseDamageRules.Kind.VOID,CorpseDamageRules.kind(sources.fellOutOfWorld()));
        assertTrue(CorpseDamageRules.allows(sources.fellOutOfWorld(),true,false,false,false));
    }
    @Test void allConfigurationCombinationsSelectOnlyTheirCategory() {
        for(int bits=0;bits<8;bits++) {
            boolean attack=(bits&1)!=0,environment=(bits&2)!=0,misc=(bits&4)!=0;
            assertEquals(attack,CorpseDamageRules.allows(sources.thorns(entity),false,attack,environment,misc));
            assertEquals(environment,CorpseDamageRules.allows(sources.anvil(entity),false,attack,environment,misc));
            assertEquals(misc,CorpseDamageRules.allows(sources.generic(),false,attack,environment,misc));
            assertFalse(CorpseDamageRules.allows(sources.anvil(entity),true,attack,environment,misc));
        }
    }
    @Test void otherNativeHazardsKeepTheHistoricalMiscSetting() {
        for(DamageSource source:new DamageSource[]{sources.hotFloor(),sources.drown(),sources.wither(),sources.freeze()})
            assertEquals(CorpseDamageRules.Kind.MISC,CorpseDamageRules.kind(source));
        assertEquals(CorpseDamageRules.Kind.MISC,CorpseDamageRules.kind(null));
    }
    private static final class SourceEntity extends Entity {
        SourceEntity(){super(net.minecraft.world.entity.EntityTypes.FALLING_BLOCK,null);}
        protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder){ }
        protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput tag){ }
        protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput tag){ }
        public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource damage, float amount) { throw new UnsupportedOperationException("Damage source only"); }
        public Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket(){throw new UnsupportedOperationException("No network in this unit fixture");}
    }
}
