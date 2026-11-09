package cyano.lootable.buildtest;

import net.minecraft.world.item.Item;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.RegisterEvent;

/** Forge 1.12 registers controlled dependencies during their registry events. */
public final class TestRegistries {
    public void items(RegisterEvent event) {
        if(event.getRegistryKey().equals(net.minecraft.core.registries.Registries.ITEM))NbtWorldChecks.registerItem();
    }
    public void potions(RegisterEvent event) {
        if(event.getRegistryKey().equals(net.minecraft.core.registries.Registries.MOB_EFFECT))EffectHurtChecks.registerPotion();
    }
}
