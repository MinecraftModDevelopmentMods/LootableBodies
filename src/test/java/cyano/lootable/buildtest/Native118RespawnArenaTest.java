package cyano.lootable.buildtest;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Geometric fluid ingress regression; actual native fluid/packets remain runtime proof. */
class Native118RespawnArenaTest {
    @Test void naturalLavaAboveOrBesideTheArenaCannotReachItsPlayableInterior() {
        for(BlockPos floor:List.of(new BlockPos(96,79,96),new BlockPos(-1,79,-1),new BlockPos(160,79,96))) {
            Map<BlockPos,BlockState> states=new HashMap<>();FixtureArena.prepare(floor,(pos,state)->states.put(pos.immutable(),state));
            for(BlockPos source:List.of(floor.offset(0,8,0),floor.offset(8,3,0),floor.offset(0,3,-8))) {
                Set<BlockPos> visited=new HashSet<>();Deque<BlockPos> pending=new ArrayDeque<>();pending.add(source);
                boolean reached=false;
                while(!pending.isEmpty()) {
                    BlockPos pos=pending.removeFirst();int dx=pos.getX()-floor.getX(),dy=pos.getY()-floor.getY(),dz=pos.getZ()-floor.getZ();
                    if(Math.abs(dx)>9||Math.abs(dz)>9||dy<0||dy>8||!visited.add(pos)||!states.getOrDefault(pos,Blocks.AIR.defaultBlockState()).isAir())continue;
                    if(Math.abs(dx)<=6&&Math.abs(dz)<=6&&dy>=1&&dy<=5){reached=true;break;}
                    pending.add(pos.below());pending.add(pos.north());pending.add(pos.south());pending.add(pos.west());pending.add(pos.east());
                }
                assertFalse(reached,"Ambient fluid ingress from "+source+" into disposable arena at "+floor);
            }
            for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++)for(int dy=1;dy<=5;dy++)
                assertTrue(states.get(floor.offset(dx,dy,dz)).isAir(),"Keep the original playable space clear");
        }
    }
}
