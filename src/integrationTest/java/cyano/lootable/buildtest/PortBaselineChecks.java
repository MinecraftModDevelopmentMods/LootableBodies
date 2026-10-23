package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.events.PlayerDeathEventHandler;
import java.util.*;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import com.mojang.authlib.GameProfile;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Small behavioral red/green gates, runnable against the unmodified 1.11 source. */
public final class PortBaselineChecks {
    private interface Check { void run() throws Exception; }
    private static int failures;
    private static void check(String name, Check action) {
        try { action.run(); System.out.println("PORT_BASELINE_PASS " + name); }
        catch (Throwable error) { failures++; System.out.println("PORT_BASELINE_FAIL " + name + " " + error); }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void run(MinecraftServer server) throws Exception {
        failures=0;
        WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));
        check("rejected_spawn_keeps_death_inventory", () -> {
            EntityPlayerMP player=FakePlayerFactory.get(world,new GameProfile(UUID.randomUUID(),"PortFixture"));
            ArrayList<EntityItem> drops=new ArrayList<>();
            drops.add(new EntityItem(world,8,80,8,new ItemStack(Items.DIAMOND,7)));
            Object veto=new Object() { @SubscribeEvent public void join(EntityJoinWorldEvent e) {
                if(e.getEntity() instanceof EntityLootableBody) e.setCanceled(true);
            }};
            MinecraftForge.EVENT_BUS.register(veto);
            try { new PlayerDeathEventHandler().entityDropEvent(new LivingDropsEvent(player,DamageSource.GENERIC,drops,0,false)); }
            finally { MinecraftForge.EVENT_BUS.unregister(veto); }
            require(drops.size()==1 && drops.get(0).getItem().getCount()==7,"original drops were discarded");
        });
        check("duplicate_slots_preserve_both_records", () -> {
            EntityLootableBody body=new EntityLootableBody(world);
            NBTTagCompound nbt=new NBTTagCompound(); body.writeAdditional(nbt);
            NBTTagList equipment=new NBTTagList();
            for(int i=0;i<2;i++) { NBTTagCompound item=new ItemStack(Items.DIAMOND,i+2).write(new NBTTagCompound()); item.setByte("Slot",(byte)10); equipment.add(item); }
            nbt.setTag("Equipment",equipment); body.readAdditional(nbt);
            NBTTagCompound saved=new NBTTagCompound();body.writeAdditional(saved);
            int count=0; for(String key:Arrays.asList("Equipment","Aux")) {
                NBTTagList list=saved.getList(key,10);
                for(int i=0;i<list.size();i++) count+=list.getCompound(i).getByte("Count");
            }
            require(count==5,"duplicate slot overwrote a recoverable stack");
        });
        check("missing_item_keeps_raw_recovery_record", () -> {
            EntityLootableBody body=new EntityLootableBody(world); NBTTagCompound nbt=new NBTTagCompound();body.writeAdditional(nbt);
            NBTTagCompound item=new ItemStack(Items.DIAMOND,7).write(new NBTTagCompound());item.setString("id","missingfixture:keepsake");item.setByte("Slot",(byte)10);
            NBTTagList list=new NBTTagList();list.add(item);nbt.setTag("Equipment",list);body.readAdditional(nbt);
            NBTTagCompound saved=new NBTTagCompound();body.writeAdditional(saved);
            require(saved.getList("LootableBodiesUnresolved",10).size()==1,"missing item was erased");
            require(!body.isEmpty(),"recoverable item allowed empty-body decay");
        });
        check("invalid_slot_retains_inventory", () -> {
            EntityLootableBody body=new EntityLootableBody(world); NBTTagCompound nbt=new NBTTagCompound();body.writeAdditional(nbt);
            NBTTagCompound item=new ItemStack(Items.EMERALD,9).write(new NBTTagCompound());item.setInt("Slot",12345);
            NBTTagList list=new NBTTagList();list.add(item);nbt.setTag("Equipment",list);body.readAdditional(nbt);
            NBTTagCompound saved=new NBTTagCompound();body.writeAdditional(saved);
            require(!body.isEmpty(),"invalid slot discarded inventory");
        });
        check("malformed_name_and_timer_are_retained", () -> {
            EntityLootableBody body=new EntityLootableBody(world); NBTTagCompound nbt=new NBTTagCompound();body.writeAdditional(nbt);
            nbt.setTag("Name",new NBTTagInt(17));nbt.setDouble("DeathTime",Double.NaN);body.readAdditional(nbt);
            NBTTagCompound saved=new NBTTagCompound();body.writeAdditional(saved);
            require(saved.getList("LootableBodiesInvalidFields",10).size()==2,"malformed fields lost without recovery");
        });
        System.out.println("PORT_BASELINE_RESULT failures="+failures);
        require(failures==0,"Port baseline regressions: "+failures);
    }
}
