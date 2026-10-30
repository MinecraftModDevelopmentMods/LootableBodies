package buildcontrol;

import java.nio.file.*;
import java.util.concurrent.*;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.DataPackConfig;

/** Actual packaged native resource loading, without Lootable Bodies or a world. */
final class ResourceControl {
    private ResourceControl() { }
    static void run(int rounds) {
        try {
            if(rounds!=50)throw new AssertionError("Each bounded resource qualification shard must contain50reloads");
            if(net.minecraftforge.fml.ModList.get().getModContainerById("lootablebodies").isPresent())
                throw new AssertionError("Independent resource control contains Lootable Bodies");
            if(net.minecraftforge.fmllegacy.server.ServerLifecycleHooks.getCurrentServer()!=null)
                throw new AssertionError("Resource control has already constructed a server");
            Executor prepare=Util.backgroundExecutor();
            if(!(prepare instanceof ForkJoinPool) || ((ForkJoinPool)prepare).getParallelism()!=1)
                throw new AssertionError("Historical native resource control requires one actual preparation worker");
            int cpus=Runtime.getRuntime().availableProcessors();
            System.out.println("NATIVE37_RESOURCE_CONTROL_BEGIN cpus="+cpus+" preparationWorkers=1 rounds="+rounds);
            RegistryAccess.RegistryHolder registries=RegistryAccess.builtin();
            try(PackRepository packs=new PackRepository(PackType.SERVER_DATA,new ServerPacksSource())) {
                MinecraftServer.configurePackRepository(packs,DataPackConfig.DEFAULT,false);
                for(int round=1;round<=rounds;round++) {
                    // These are Main's real resources and native executors. No
                    // custom listeners, fake exception, HashSet or future edits.
                    try(ServerResources resources=ServerResources.loadResources(packs.openAllSelected(),registries,
                            Commands.CommandSelection.DEDICATED,2,prepare,Runnable::run).get(12,TimeUnit.SECONDS)) {
                        if(resources.getRecipeManager().getRecipes().isEmpty())throw new AssertionError("No native recipes loaded");
                    }
                    if(round%10==0)System.out.println("NATIVE37_RESOURCE_RELOAD_COMPLETE="+round);
                }
            }
            if(net.minecraftforge.fmllegacy.server.ServerLifecycleHooks.getCurrentServer()!=null)
                throw new AssertionError("Resource control unexpectedly constructed a server");
            Files.writeString(Path.of("resource-control.pass"),"rounds="+rounds+" cpus="+cpus+" preparationWorkers=1 noWorld=true noLootableBodies=true\n",StandardOpenOption.CREATE_NEW);
            System.out.println("NATIVE37_RESOURCE_CONTROL_PASS rounds="+rounds);
            System.out.flush();System.exit(0);
        } catch(Throwable failure) {
            failure.printStackTrace();System.out.flush();System.exit(2);
        }
    }
}
