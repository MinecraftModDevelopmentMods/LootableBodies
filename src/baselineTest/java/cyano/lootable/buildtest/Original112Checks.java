package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;

/** Independent assertions run against the checksum-locked original 1.12 jar. */
@Mod(modid="lootablebodiesbuildtest",name="Lootable Bodies Original Checks",version="1",dependencies="required-after:lootablebodies")
public final class Original112Checks {
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event){UpgradeChecks.registerOwnFixer();}
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        net.minecraft.server.MinecraftServer server=FMLCommonHandler.instance().getMinecraftServerInstance();
        int failed=0;
        try {
            try {UpgradeChecks.run(server);} catch(Throwable expected){failed++;expected.printStackTrace();}
            WorldServer world=server.getWorld(0);
            LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
            EntityLootableBody body=new EntityLootableBody(world);body.setPosition(.5,80,.5);body.setNoGravity(true);
            body.setInventorySlotContents(6,new ItemStack(Items.DIAMOND,44));
            NBTTagCompound saved=new NBTTagCompound();body.writeToNBT(saved);saved.setByte("Vac",(byte)20);
            NBTTagList overflow=new NBTTagList();overflow.appendTag(new ItemStack(Items.PAPER,17).writeToNBT(new NBTTagCompound()));saved.setTag("Aux",overflow);body.readFromNBT(saved);
            try {
                body.onEntityUpdate();NBTTagCompound after=new NBTTagCompound();body.writeToNBT(after);
                RuntimeChecks.require(body.getStackInSlot(6).getItem()==Items.DIAMOND && body.getStackInSlot(6).getCount()==44,"Aux refill overwrote an occupied slot");
                int paper=0;for(int slot=6;slot<48;slot++)if(body.getStackInSlot(slot).getItem()==Items.PAPER)paper+=body.getStackInSlot(slot).getCount();
                for(int i=0;i<after.getTagList("Aux",10).tagCount();i++)paper+=new ItemStack(after.getTagList("Aux",10).getCompoundTagAt(i)).getCount();
                RuntimeChecks.require(paper==17,"Overflow paper count changed");
                System.out.println("ORIGINAL_112_CONTRACT_PASS occupied_slot_and_overflow_conserved");
            } catch(Throwable error){failed++;System.out.println("ORIGINAL_112_CONTRACT_FAIL occupied_slot_and_overflow_conserved: "+error);}
            try(FileWriter writer=new FileWriter(new File(world.getSaveHandler().getWorldDirectory(),"original-112-checks.txt"))){writer.write("failed_groups="+failed+"\n");}
            System.out.println("ORIGINAL_112_RESULT failed_groups="+failed);
        } catch(Throwable error){error.printStackTrace();}
        finally {server.initiateShutdown();}
    }
}
