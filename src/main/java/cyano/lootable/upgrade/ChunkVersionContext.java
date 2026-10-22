package cyano.lootable.upgrade;

import cyano.lootable.LootableBodies;
import java.lang.reflect.Field;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.util.datafix.IFixType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Supplies preserved source context before the Anvil loader invokes Forge's fixers. */
@Mod.EventBusSubscriber(modid=LootableBodies.MODID)
public final class ChunkVersionContext {
    private ChunkVersionContext() { }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void load(WorldEvent.Load event) {
        if(!(event.getWorld() instanceof WorldServer))return;
        IChunkLoader loader=((WorldServer)event.getWorld()).getChunkProvider().chunkLoader;
        if(!(loader instanceof AnvilChunkLoader))return;
        try {
            // Forge has no setter for this pinned 1.12 Anvil boundary. World.Load
            // precedes spawn preparation and later dimension chunk loading.
            Field field=ReflectionHelper.findField(AnvilChunkLoader.class,"fixer","field_193416_e");
            DataFixer delegate=(DataFixer)field.get(loader);
            if(!(delegate instanceof ContextFixer))field.set(loader,new ContextFixer(delegate));
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Lootable Bodies could not install safe chunk version handling",failure);
        }
    }

    private static final class ContextFixer extends DataFixer {
        private final DataFixer delegate;
        ContextFixer(DataFixer delegate){super(UpgradePreservation.MINECRAFT_VERSION);this.delegate=delegate;}
        @Override public NBTTagCompound process(IFixType type,NBTTagCompound input) {
            NBTTagCompound result=delegate.process(type,type==FixTypes.CHUNK?UpgradePreservation.chunkContext(input):input);
            if(type==FixTypes.CHUNK)UpgradePreservation.clearChunkContext(result);
            return result;
        }
        @Override public NBTTagCompound process(IFixType type,NBTTagCompound input,int version) {
            return delegate.process(type,input,version);
        }
    }
}
