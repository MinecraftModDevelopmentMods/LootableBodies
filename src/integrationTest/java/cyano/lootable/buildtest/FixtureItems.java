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
        if(keepsake!=null)return;
        keepsake=new Item() {
            @Override public ICapabilityProvider initCapabilities(ItemStack stack,NBTTagCompound nbt) {return new Stored();}
        };
        try {Item.class.getMethod("setRegistryName",ResourceLocation.class).invoke(keepsake,new ResourceLocation("lootablebodiesbuildtest:keepsake"));}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Fixture item name",failure);}
        if(String.valueOf(net.minecraftforge.fml.relauncher.FMLInjectionData.data()[4]).startsWith("1.12")) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new RegistryItems());
            return;
        }
        try {
            java.lang.reflect.Method legacy=null;
            for(java.lang.reflect.Method method:GameRegistry.class.getMethods())
                if(method.getName().equals("register") && method.getParameterTypes().length==1 && method.getParameterTypes()[0].isInstance(keepsake)) {legacy=method;break;}
            if(legacy!=null) legacy.invoke(null,keepsake);
            else {
                Object registry=Class.forName("net.minecraftforge.fml.common.registry.ForgeRegistries").getField("ITEMS").get(null);
                java.lang.reflect.Method registration=null;
                for(java.lang.reflect.Method method:registry.getClass().getMethods())
                    if(method.getName().equals("register") && method.getParameterTypes().length==1 && method.getParameterTypes()[0].isInstance(keepsake)){registration=method;break;}
                if(registration==null)throw new IllegalStateException("Fixture item registration API unavailable");
                registration.invoke(registry,keepsake);
            }
        } catch(ReflectiveOperationException failure) {throw new IllegalStateException("Fixture registration",failure);}
    }
    public static final class RegistryItems {
        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void register(net.minecraftforge.event.RegistryEvent.Register<Item> event) {event.getRegistry().register(keepsake);}
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
