package cyano.lootable.buildtest;

import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Disposable packet-fixture terrain; never changes production entity physics. */
public final class FixtureArena {
    private FixtureArena() { }
    public static void prepare(BlockPos floor, BiConsumer<BlockPos,BlockState> write) {
        // Native Nether springs can refill an open cleared space between
        // respawns. Keep the original13x13x5 playable volume, with a complete
        // disposable shell; never grant players immunity or alter the mod.
        for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++)for(int dy=0;dy<=6;dy++)
            write.accept(floor.offset(dx,dy,dz),
                (dy==0||dy==6||Math.abs(dx)==7||Math.abs(dz)==7?Blocks.OBSIDIAN:Blocks.AIR).defaultBlockState());
    }
}
