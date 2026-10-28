package cyano.lootable.buildtest;
import net.minecraft.inventory.container.Slot;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.IntNBT;
import net.minecraft.nbt.CompoundNBT;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.events.PlayerDeathEventHandler;
import java.util.*;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.server.ServerWorld;

import net.minecraft.world.GameType;
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
        catch (Throwable error) { failures++; System.out.println("PORT_BASELINE_FAIL " + name + " " + error); error.printStackTrace(); }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void run(MinecraftServer server) throws Exception {
        failures=0;
        ServerWorld world=server.getWorld(FixtureApi.dimensionKey(0));
        check("native_received_packet_observer_is_non_destructive", () -> {
            net.minecraft.network.PacketBuffer buffer=new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer());
            try {
                EntityLootableBody corpse=new EntityLootableBody(world);
                net.minecraft.network.play.server.SSpawnMobPacket sent=new net.minecraft.network.play.server.SSpawnMobPacket(corpse);
                sent.writePacketData(buffer);
                net.minecraft.network.play.server.SSpawnMobPacket received=new net.minecraft.network.play.server.SSpawnMobPacket();received.readPacketData(buffer);
                require(RespawnPacketTrace.describe(sent).equals(RespawnPacketTrace.describe(received)),"Spawn observer differs before/after native decoding");
                require(RespawnPacketTrace.describe(received).contains("mod=lootablebodies "),"Negative control cannot identify corpse spawns");
                buffer.clear();
                net.minecraft.network.play.server.SRespawnPacket outgoing=new net.minecraft.network.play.server.SRespawnPacket(world.getDimensionType(),world.getDimensionKey(),0x13572468ABCDEF01L,GameType.SURVIVAL,GameType.CREATIVE,false,true,true);
                outgoing.writePacketData(buffer);
                net.minecraft.network.play.server.SRespawnPacket incoming=new net.minecraft.network.play.server.SRespawnPacket();incoming.readPacketData(buffer);
                require(RespawnPacketTrace.describe(outgoing).equals(RespawnPacketTrace.describe(incoming)),"Respawn observer differs before/after native decoding");
                require(RespawnPacketTrace.describe(incoming).equals("RESPAWN dimension=minecraft:overworld hashedSeed="+0x13572468ABCDEF01L),"Native 1.16 hashed seed missing from the packet observation");
            } finally {buffer.release();}
        });
        check("rejected_spawn_keeps_death_inventory", () -> {
            ServerPlayerEntity player=FakePlayerFactory.get(world,new GameProfile(UUID.randomUUID(),"PortFixture"));
            ArrayList<ItemEntity> drops=new ArrayList<>();
            drops.add(new ItemEntity(world,8,80,8,new ItemStack(Items.DIAMOND,7)));
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
            CompoundNBT nbt=new CompoundNBT(); body.writeAdditional(nbt);
            ListNBT equipment=new ListNBT();
            for(int i=0;i<2;i++) { CompoundNBT item=new ItemStack(Items.DIAMOND,i+2).write(new CompoundNBT()); item.putByte("Slot",(byte)10); equipment.add(item); }
            nbt.put("Equipment",equipment); body.readAdditional(nbt);
            CompoundNBT saved=new CompoundNBT();body.writeAdditional(saved);
            int count=0; for(String key:Arrays.asList("Equipment","Aux")) {
                ListNBT list=saved.getList(key,10);
                for(int i=0;i<list.size();i++) count+=list.getCompound(i).getByte("Count");
            }
            require(count==5,"duplicate slot overwrote a recoverable stack");
        });
        check("missing_item_keeps_raw_recovery_record", () -> {
            EntityLootableBody body=new EntityLootableBody(world); CompoundNBT nbt=new CompoundNBT();body.writeAdditional(nbt);
            CompoundNBT item=new ItemStack(Items.DIAMOND,7).write(new CompoundNBT());item.putString("id","missingfixture:keepsake");item.putByte("Slot",(byte)10);
            ListNBT list=new ListNBT();list.add(item);nbt.put("Equipment",list);body.readAdditional(nbt);
            CompoundNBT saved=new CompoundNBT();body.writeAdditional(saved);
            require(saved.getList("LootableBodiesUnresolved",10).size()==1,"missing item was erased");
            require(!body.isEmpty(),"recoverable item allowed empty-body decay");
        });
        check("invalid_slot_retains_inventory", () -> {
            EntityLootableBody body=new EntityLootableBody(world); CompoundNBT nbt=new CompoundNBT();body.writeAdditional(nbt);
            CompoundNBT item=new ItemStack(Items.EMERALD,9).write(new CompoundNBT());item.putInt("Slot",12345);
            ListNBT list=new ListNBT();list.add(item);nbt.put("Equipment",list);body.readAdditional(nbt);
            CompoundNBT saved=new CompoundNBT();body.writeAdditional(saved);
            require(!body.isEmpty(),"invalid slot discarded inventory");
        });
        check("malformed_name_and_timer_are_retained", () -> {
            EntityLootableBody body=new EntityLootableBody(world); CompoundNBT nbt=new CompoundNBT();body.writeAdditional(nbt);
            nbt.put("Name",IntNBT.valueOf(17));nbt.putDouble("DeathTime",Double.NaN);body.readAdditional(nbt);
            CompoundNBT saved=new CompoundNBT();body.writeAdditional(saved);
            require(saved.getList("LootableBodiesInvalidFields",10).size()==2,"malformed fields lost without recovery");
        });
        System.out.println("PORT_BASELINE_RESULT failures="+failures);
        require(failures==0,"Port baseline regressions: "+failures);
    }
}
