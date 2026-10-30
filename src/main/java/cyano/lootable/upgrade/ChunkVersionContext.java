package cyano.lootable.upgrade;
import com.mojang.datafixers.DataFixer;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.structure.LegacyStructureDataHandler;
/** Installs preservation before native save handlers and chunk readers run. */
public final class ChunkVersionContext {
    private ChunkVersionContext() { }
    public static void installClient(Object client,Object saveFormat) {
        installFixer(client,null);installFixer(saveFormat,null);
    }
    public static void installServer(MinecraftServer server) {
        installFixer(server,null);
        installFixer(related(server,net.minecraft.world.level.storage.PlayerDataStorage.class),null);
    }
    public static void installWorld(ServerLevel world) {
        Object loader=world.getChunkSource().chunkMap;
        if(!(loader instanceof ChunkStorage))throw new IllegalStateException("Unsupported chunk reader; refusing unprotected world loading");
        installFixer(loader,chunk->LegacyStructureDataHandler.getLegacyStructureHandler(world.dimension(),world.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD).getDataStorage()).updateFromLegacy(chunk));
    }
    private static Object related(Object owner,Class<?> expected) {
        java.util.List<Field> matches=new java.util.ArrayList<>();
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())for(Field field:type.getDeclaredFields())
            if(!Modifier.isStatic(field.getModifiers()) && field.getType()==expected)matches.add(field);
        if(matches.size()!=1)throw new IllegalStateException("Cannot identify a unique native save reader");
        try {Field field=matches.get(0);field.setAccessible(true);return field.get(owner);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot access native save reader",failure);}
    }
    static void installFixer(Object owner,UnaryOperator<CompoundTag> structures) {
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
