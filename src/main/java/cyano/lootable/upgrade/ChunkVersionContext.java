package cyano.lootable.upgrade;
import com.mojang.datafixers.DataFixer;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.feature.structure.LegacyStructureDataUtil;
/** Installs preservation before native save handlers and chunk readers run. */
public final class ChunkVersionContext {
    private ChunkVersionContext() { }
    public static void installServer(MinecraftServer server) {
        installFixer(server,null);
        installFixer(server.getActiveAnvilConverter(),null);
    }
    public static void installWorld(WorldServer world) {
        Object loader=world.getChunkProvider().chunkLoader;
        if(!(loader instanceof AnvilChunkLoader))throw new IllegalStateException("Unsupported chunk reader; refusing unprotected world loading");
        installFixer(world.getSaveHandler(),null);
        installFixer(loader,chunk->LegacyStructureDataUtil.func_212183_a(world.getDimension().getType(),world.getMapStorage()).func_212181_a(chunk));
    }
    static void installFixer(Object owner,UnaryOperator<NBTTagCompound> structures) {
        List<Field> fields=new ArrayList<>();
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(Field field:type.getDeclaredFields())
            if(!Modifier.isStatic(field.getModifiers()) && field.getType()==DataFixer.class)fields.add(field);
        if(fields.size()!=1)throw new IllegalStateException("Cannot identify a unique native data fixer before world loading");
        try {
            Field field=fields.get(0);field.setAccessible(true);DataFixer existing=(DataFixer)field.get(owner);
            if(existing==null)throw new IllegalStateException("Missing native data fixer");
            if(existing instanceof NativeSafeFixer && structures==null)return;
            DataFixer delegate=existing instanceof NativeSafeFixer?((NativeSafeFixer)existing).delegate:existing;
            DataFixer replacement=new NativeSafeFixer(delegate,structures);field.set(owner,replacement);
            if(field.get(owner)!=replacement)throw new IllegalStateException("Native preservation boundary was not installed");
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot install native preservation before world loading",failure);}
    }
}
