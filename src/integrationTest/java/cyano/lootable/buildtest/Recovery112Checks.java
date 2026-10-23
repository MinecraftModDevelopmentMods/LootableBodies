package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Retains the original 1.12 recovery options, including their disabled defaults. */
final class Recovery112Checks {
    static void run(MinecraftServer server) throws Exception {
        boolean buoyancy=LootableBodies.corpseBuoyancy,platform=LootableBodies.voidPlatform,specific=LootableBodies.dimensionSpecificPlatform;
        int checks=0;
        try {
            for(int dimension:new int[]{0,-1,1})for(boolean dimensionBlocks:new boolean[]{false,true}) {
                WorldServer world=server.getWorld(dimension);int coordinate=264+checks*16;
                world.getChunk(coordinate>>4,coordinate>>4);BlockPos center=new BlockPos(coordinate,0,coordinate);
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)world.setBlockToAir(center.add(x,0,z));
                world.setBlockState(center,Blocks.STONE.getDefaultState());
                EntityLootableBody body=body(world,coordinate,-1);
                LootableBodies.voidPlatform=false;body.onEntityUpdate();
                RuntimeChecks.require(body.posY==-1 && world.isAirBlock(center.add(1,0,0)),"Disabled void platform changed behaviour");
                LootableBodies.voidPlatform=true;LootableBodies.dimensionSpecificPlatform=dimensionBlocks;body.onEntityUpdate();
                Block expected=!dimensionBlocks || dimension==0?Blocks.COBBLESTONE:dimension==-1?Blocks.NETHERRACK:Blocks.END_STONE;
                RuntimeChecks.require(body.posY==1.5 && body.isEntityAlive(),"Enabled void platform did not rescue corpse");
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)
                    RuntimeChecks.require(world.getBlockState(center.add(x,0,z)).getBlock()==(x==0 && z==0?Blocks.STONE:expected),"Void platform changed existing terrain or dimension block");
                conserved(body);checks++;
            }
            WorldServer world=server.getWorld(0);world.getChunk(30,30);
            for(int x=487;x<=489;x++)for(int z=487;z<=489;z++)for(int y=79;y<=82;y++)world.setBlockState(new BlockPos(x,y,z),Blocks.WATER.getDefaultState());
            EntityLootableBody body=body(world,488,80);
            RuntimeChecks.require(body.handleWaterMovement(),"Water fixture was not submerged");
            LootableBodies.corpseBuoyancy=false;body.motionY=0;body.onEntityUpdate();
            RuntimeChecks.require(body.motionY==0,"Disabled buoyancy accelerated the corpse");
            LootableBodies.corpseBuoyancy=true;body.motionY=0;body.onEntityUpdate();
            RuntimeChecks.require(body.motionY>0,"Enabled buoyancy did not lift the corpse");conserved(body);checks++;
            System.out.println("LOOTABLE_BODIES_112_RECOVERY_PASS checks="+checks);
        } finally {LootableBodies.corpseBuoyancy=buoyancy;LootableBodies.voidPlatform=platform;LootableBodies.dimensionSpecificPlatform=specific;}
    }
    private static EntityLootableBody body(WorldServer world,int coordinate,double y) {
        EntityLootableBody body=new EntityLootableBody(world);body.setPosition(coordinate+.5,y,coordinate+.5);body.setNoGravity(true);
        body.setInventorySlotContents(6,RuntimeChecks.stack(420));RuntimeChecks.disableVacuum(body);return body;
    }
    private static void conserved(EntityLootableBody body) {
        RuntimeChecks.require(RuntimeChecks.sameStack(body.getStackInSlot(6),RuntimeChecks.stack(420)),"Recovery option changed complete item NBT");
        NBTTagCompound saved=new NBTTagCompound();body.writeToNBTOptional(saved);EntityLootableBody reloaded=new EntityLootableBody(body.world);reloaded.readFromNBT(saved);
        RuntimeChecks.require(RuntimeChecks.sameStack(reloaded.getStackInSlot(6),RuntimeChecks.stack(420)),"Recovery option inventory changed on reload");
    }
}
