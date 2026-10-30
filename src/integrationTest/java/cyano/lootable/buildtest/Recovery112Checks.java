package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Retains the original 1.12 recovery options, including their disabled defaults. */
final class Recovery112Checks {
    static void run(MinecraftServer server) throws Exception {
        boolean buoyancy=LootableBodies.corpseBuoyancy,platform=LootableBodies.voidPlatform,specific=LootableBodies.dimensionSpecificPlatform;
        int checks=0;
        try {
            for(int dimension:new int[]{0,-1,1})for(boolean dimensionBlocks:new boolean[]{false,true}) {
                ServerLevel world=server.getLevel(FixtureApi.dimensionKey(dimension));int coordinate=264+checks*16;
                world.getChunk(coordinate>>4,coordinate>>4);BlockPos center=new BlockPos(coordinate,0,coordinate);
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)world.removeBlock(center.offset(x,0,z),false);
                world.setBlockAndUpdate(center,Blocks.STONE.defaultBlockState());
                EntityLootableBody body=body(world,coordinate,-1);
                LootableBodies.voidPlatform=false;body.baseTick();
                RuntimeChecks.require(body.getY()==-1 && world.isEmptyBlock(center.offset(1,0,0)),"Disabled void platform changed behaviour");
                LootableBodies.voidPlatform=true;LootableBodies.dimensionSpecificPlatform=dimensionBlocks;body.baseTick();
                Block expected=!dimensionBlocks || dimension==0?Blocks.COBBLESTONE:dimension==-1?Blocks.NETHERRACK:Blocks.END_STONE;
                RuntimeChecks.require(body.getY()==1.5 && body.isAlive(),"Enabled void platform did not rescue corpse");
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)
                    RuntimeChecks.require(world.getBlockState(center.offset(x,0,z)).getBlock()==(x==0 && z==0?Blocks.STONE:expected),"Void platform changed existing terrain or dimension block");
                conserved(body);checks++;
            }
            ServerLevel world=server.getLevel(FixtureApi.dimensionKey(0));world.getChunk(30,30);
            for(int x=487;x<=489;x++)for(int z=487;z<=489;z++)for(int y=79;y<=82;y++)world.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.WATER.defaultBlockState());
            EntityLootableBody body=body(world,488,80);
            RuntimeChecks.require(body.handleWaterMovement(),"Water fixture was not submerged");
            LootableBodies.corpseBuoyancy=false;body.setDeltaMovement(body.getDeltaMovement().x,0,body.getDeltaMovement().z);body.baseTick();
            RuntimeChecks.require(body.getDeltaMovement().y==0,"Disabled buoyancy accelerated the corpse");
            LootableBodies.corpseBuoyancy=true;body.setDeltaMovement(body.getDeltaMovement().x,0,body.getDeltaMovement().z);body.baseTick();
            RuntimeChecks.require(body.getDeltaMovement().y>0,"Enabled buoyancy did not lift the corpse");conserved(body);checks++;
            System.out.println("LOOTABLE_BODIES_112_RECOVERY_PASS checks="+checks);
        } finally {LootableBodies.corpseBuoyancy=buoyancy;LootableBodies.voidPlatform=platform;LootableBodies.dimensionSpecificPlatform=specific;}
    }
    private static EntityLootableBody body(ServerLevel world,int coordinate,double y) {
        EntityLootableBody body=new EntityLootableBody(world);body.setPos(coordinate+.5,y,coordinate+.5);body.setNoGravity(true);
        body.setItem(6,RuntimeChecks.stack(420));RuntimeChecks.disableVacuum(body);return body;
    }
    private static void conserved(EntityLootableBody body) {
        RuntimeChecks.require(RuntimeChecks.sameStack(body.getItem(6),RuntimeChecks.stack(420)),"Recovery option changed complete item NBT");
        CompoundTag saved=new CompoundTag();body.saveAsPassenger(saved);EntityLootableBody reloaded=new EntityLootableBody(body.level);reloaded.load(saved);
        RuntimeChecks.require(RuntimeChecks.sameStack(reloaded.getItem(6),RuntimeChecks.stack(420)),"Recovery option inventory changed on reload");
    }
}
