package cyano.lootable.buildtest;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.fml.common.registry.GameRegistry;

/** Controlled dependency used only by sealed disposable upgrade worlds. */
public final class FixtureItems {
    public static Item keepsake;
    public static void register() {
        keepsake=new Item() {
            @Override public ICapabilityProvider initCapabilities(ItemStack stack,NBTTagCompound nbt) {return new Stored();}
        }.setRegistryName(new ResourceLocation("lootablebodiesbuildtest:keepsake"));
        GameRegistry.register(keepsake);
    }
    private static final class Stored implements ICapabilitySerializable<NBTTagCompound> {
        private NBTTagCompound data=new NBTTagCompound();
        Stored(){data.setString("owner","Disposable fixture");data.setLong("number",9876543210123L);data.setByteArray("opaque",new byte[]{0,1,-1,17});}
        public boolean hasCapability(Capability<?> capability,EnumFacing side){return false;}
        public <T>T getCapability(Capability<T> capability,EnumFacing side){return null;}
        public NBTTagCompound serializeNBT(){return data.copy();}
        public void deserializeNBT(NBTTagCompound nbt){data=nbt.copy();}
    }
}
