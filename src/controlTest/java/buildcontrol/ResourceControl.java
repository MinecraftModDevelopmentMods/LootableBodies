package buildcontrol;

import java.nio.file.*;
import java.util.concurrent.*;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;

/** Actual packaged native resource loading, without Lootable Bodies or a world. */
final class ResourceControl {
    private ResourceControl() { }
    static void run(int rounds) {
        try {
            if(rounds!=50)throw new AssertionError("Each bounded resource qualification shard must contain50reloads");
            if(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").isPresent())
                throw new AssertionError("Independent resource control contains Lootable Bodies");
            if(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()!=null)
                throw new AssertionError("Resource control has already constructed a server");
            Executor prepare=Util.backgroundExecutor();
            int cpus=Runtime.getRuntime().availableProcessors();
            if(!(prepare instanceof ForkJoinPool))throw new AssertionError("Native45 preparation executor changed");
            int workers=((ForkJoinPool)prepare).getParallelism(),limit=Integer.getInteger("max.bg.threads",255);
            if(limit<1 || limit>255 || workers!=Math.max(1,Math.min(cpus-1,limit)))throw new AssertionError("Native45 worker configuration differs from its actual Util executor");
            System.out.println("NATIVE45_RESOURCE_CONTROL_BEGIN cpus="+cpus+" preparationWorkers="+workers+" rounds="+rounds);
            PackRepository packs=new PackRepository(new ServerPacksSource());
            WorldLoader.InitConfig config=new WorldLoader.InitConfig(
                    new WorldLoader.PackConfig(packs,WorldDataConfiguration.DEFAULT,false,false),Commands.CommandSelection.DEDICATED,2);
            {
                for(int round=1;round<=rounds;round++) {
                    // These are Main's real resources and native executors. No
                    // custom listeners, fake exception, HashSet or future edits.
                    try(Prepared resources=Util.blockUntilDone(apply->WorldLoader.load(config,
                            context->new WorldLoader.DataLoadOutput<>(Boolean.TRUE,context.datapackDimensions()),
                            (manager,reloaded,registries,cookie)->new Prepared(manager,reloaded),prepare,apply)
                            .orTimeout(12,TimeUnit.SECONDS))
                            .get(12,TimeUnit.SECONDS)) {
                        if(resources.reloaded().getRecipeManager().getRecipes().isEmpty())throw new AssertionError("No native recipes loaded");
                    }
                    if(round%10==0)System.out.println("NATIVE45_RESOURCE_RELOAD_COMPLETE="+round);
                }
            }
            if(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()!=null)
                throw new AssertionError("Resource control unexpectedly constructed a server");
            Files.writeString(Path.of("resource-control.pass"),"rounds="+rounds+" cpus="+cpus+" preparationWorkers="+workers+" noWorld=true noLootableBodies=true\n",StandardOpenOption.CREATE_NEW);
            System.out.println("NATIVE45_RESOURCE_CONTROL_PASS rounds="+rounds);
            System.out.flush();System.exit(0);
        } catch(Throwable failure) {
            failure.printStackTrace();System.out.flush();System.exit(2);
        }
    }
    private record Prepared(CloseableResourceManager manager,ReloadableServerResources reloaded) implements AutoCloseable {
        public void close(){manager.close();}
    }
}
