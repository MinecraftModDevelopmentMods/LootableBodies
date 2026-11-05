package cyano.lootable.buildtest;

import net.minecraft.world.item.Item;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge 1.12 registers controlled dependencies during their registry events. */
public final class TestRegistries {
    @SubscribeEvent public void items(RegisterEvent event) {
        if(event.getRegistryKey().equals(net.minecraft.core.registries.Registries.ITEM))NbtWorldChecks.registerItem();
    }
    @SubscribeEvent public void potions(RegisterEvent event) {
        if(event.getRegistryKey().equals(net.minecraft.core.registries.Registries.MOB_EFFECT))EffectHurtChecks.registerPotion();
    }
}
