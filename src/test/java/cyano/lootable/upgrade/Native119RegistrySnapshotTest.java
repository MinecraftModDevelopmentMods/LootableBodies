package cyano.lootable.upgrade;

import com.mojang.serialization.Dynamic;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.fixes.References;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Forge45 mapping actions and native PoI data fixes, without a world. */
class Native119RegistrySnapshotTest {
    private MissingMappingsEvent.Action action(ResourceKey<? extends Registry<?>> registry,String name)throws Exception {
        MissingMappingsEvent.Mapping<Object> mapping=new MissingMappingsEvent.Mapping<>(null,null,ResourceLocation.parse(name),17);
        MissingMappingsEvent event=new MissingMappingsEvent(registry,null,List.of(mapping));
        for(java.lang.reflect.Method method:LegacyRegistryMappings.class.getDeclaredMethods())
            if(method.isAnnotationPresent(SubscribeEvent.class) && Arrays.equals(method.getParameterTypes(),new Class<?>[]{MissingMappingsEvent.class}))method.invoke(null,event);
        java.lang.reflect.Field field=mapping.getClass().getDeclaredField("action");field.setAccessible(true);
        return (MissingMappingsEvent.Action)field.get(mapping);
    }
    @Test void knownVanillaBiomeNumericSnapshotsDoNotStandInForDataPackBiomes()throws Exception {
        List<ResourceKey<net.minecraft.world.level.biome.Biome>> keys=cyano.lootable.buildtest.Native119Registries.lookup()
                .lookupOrThrow(Registries.BIOME).listElements().map(net.minecraft.core.Holder.Reference::key).toList();
        assertTrue(keys.size()>50);
        for(ResourceKey<?> key:keys)assertEquals(MissingMappingsEvent.Action.IGNORE,action(Registries.BIOME,key.location().toString()),key.location().toString());
        for(String old:net.minecraft.util.datafix.fixes.CavesAndCliffsRenames.RENAMES.keySet())
            assertEquals(MissingMappingsEvent.Action.IGNORE,action(Registries.BIOME,old),old);
    }
    @Test void onlyTheTwoNativeRetiredPoiBindingsAreResolved()throws Exception {
        for(String name:List.of("minecraft:unemployed","minecraft:nitwit"))
            assertEquals(MissingMappingsEvent.Action.IGNORE,action(Registries.POINT_OF_INTEREST_TYPE,name),name);
    }
    @Test void unknownOtherModAndUnrelatedMappingsRemainUnresolved()throws Exception {
        for(String name:List.of("anothermod:plains","minecraft:unknown_saved_biome"))
            assertEquals(MissingMappingsEvent.Action.DEFAULT,action(Registries.BIOME,name),name);
        for(String name:List.of("minecraft:farmer","anothermod:nitwit","minecraft:unknown_saved_poi"))
            assertEquals(MissingMappingsEvent.Action.DEFAULT,action(Registries.POINT_OF_INTEREST_TYPE,name),name);
        assertEquals(MissingMappingsEvent.Action.DEFAULT,action(Registries.ITEM,"minecraft:nitwit"));
    }
    @Test void actualVanillaPoiFixRemovesOnlyTheRetiredRecordsWithoutMutatingInput() {
        CompoundTag root=new CompoundTag(),sections=new CompoundTag(),section=new CompoundTag();ListTag records=new ListTag();
        for(String type:List.of("minecraft:unemployed","minecraft:nitwit","minecraft:farmer")) {
            CompoundTag record=new CompoundTag();record.putString("type",type);record.putIntArray("pos",new int[]{0,80,0});record.putInt("free_tickets",1);records.add(record);
        }
        section.put("Records",records);sections.put("0",section);root.put("Sections",sections);CompoundTag before=root.copy();
        Tag fixed=net.minecraft.util.datafix.DataFixers.getDataFixer().update(References.POI_CHUNK,new Dynamic<>(NbtOps.INSTANCE,root),2975,3465).getValue();
        assertEquals(before,root,"Native conversion must operate on copies");
        ListTag result=((CompoundTag)fixed).getCompound("Sections").getCompound("0").getList("Records",10);
        assertEquals(1,result.size());assertEquals("minecraft:farmer",result.getCompound(0).getString("type"));
    }
}
