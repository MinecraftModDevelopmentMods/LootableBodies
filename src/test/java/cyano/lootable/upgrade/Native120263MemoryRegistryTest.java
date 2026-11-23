package cyano.lootable.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraftforge.registries.RegisterEvent;
import org.junit.jupiter.api.Test;

/** Native65 serialized this retired Boolean memory; never waive its missing binding. */
class Native120263MemoryRegistryTest {
    private MappedRegistry<MemoryModuleType<?>> registered() throws Exception {
        var registry=new MappedRegistry<MemoryModuleType<?>>(Registries.MEMORY_MODULE_TYPE,Lifecycle.stable());
        var constructor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,net.minecraftforge.registries.ForgeRegistry.class,Registry.class);
        constructor.setAccessible(true);
        LegacyRegistryMappings.retiredMemories(constructor.newInstance(Registries.MEMORY_MODULE_TYPE,null,registry));
        return registry;
    }
    @Test void retiredVanillaMemoryKeepsItsOriginalRegistryIdentity() throws Exception {
        var registry=registered();
        assertTrue(registry.containsKey(Identifier.withDefaultNamespace("is_tempted")),"Missing native65 serialized Boolean memory binding");
        assertEquals(Set.of(Identifier.withDefaultNamespace("golem_last_seen_time"),Identifier.withDefaultNamespace("interactable_doors"),Identifier.withDefaultNamespace("is_tempted")),registry.keySet());
    }
    @Test void nativeBooleanCodecRetainsTrueFalseAndOptionalExpiryExactly() throws Exception {
        var registry=registered();var key=Identifier.withDefaultNamespace("is_tempted");assertTrue(registry.containsKey(key));
        var type=registry.get(key).orElseThrow().value();assertTrue(type.canSerialize());
        for(boolean value:new boolean[]{false,true})for(long ttl:new long[]{-1,0,23,Long.MAX_VALUE}) {
            CompoundTag input=new CompoundTag();input.putBoolean("value",value);if(ttl>=0)input.putLong("ttl",ttl);
            CompoundTag before=input.copy();roundTrip(type,input);assertEquals(before,input);
        }
    }
    private <T> void roundTrip(MemoryModuleType<T> type,CompoundTag input) {
        var codec=type.getCodec().orElseThrow();var decoded=codec.parse(NbtOps.INSTANCE,input).getOrThrow();
        assertEquals(input,codec.encodeStart(NbtOps.INSTANCE,decoded).getOrThrow());
    }
    @Test void earlierUnserializedRetiredMemorySlotsRemainUnserialized() throws Exception {
        var registry=registered();
        for(String name:new String[]{"golem_last_seen_time","interactable_doors"})
            assertFalse(registry.get(Identifier.withDefaultNamespace(name)).orElseThrow().value().canSerialize());
    }
}
