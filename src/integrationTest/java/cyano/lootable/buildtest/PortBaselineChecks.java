package cyano.lootable.buildtest;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;
import net.minecraft.world.inventory.Slot;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.CompoundTag;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.events.PlayerDeathEventHandler;
import java.util.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import com.mojang.authlib.GameProfile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;

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
        ServerLevel world=server.getLevel(FixtureApi.dimensionKey(0));
        check("native_received_packet_observer_is_non_destructive", () -> {
            var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),world.registryAccess());
            try {
                EntityLootableBody corpse=new EntityLootableBody(world);
                net.minecraft.network.protocol.game.ClientboundAddEntityPacket sent=new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(corpse,0,corpse.blockPosition());
                net.minecraft.network.protocol.game.ClientboundAddEntityPacket.STREAM_CODEC.encode(buffer,sent);
                net.minecraft.network.protocol.game.ClientboundAddEntityPacket received=net.minecraft.network.protocol.game.ClientboundAddEntityPacket.STREAM_CODEC.decode(buffer);
                require(RespawnPacketTrace.describe(sent).equals(RespawnPacketTrace.describe(received)),"Spawn observer differs before/after native decoding");
                require(RespawnPacketTrace.describe(received).contains("mod=lootablebodies "),"Negative control cannot identify corpse spawns");
                buffer.clear();
                net.minecraft.network.protocol.game.ClientboundRespawnPacket outgoing=new net.minecraft.network.protocol.game.ClientboundRespawnPacket(new net.minecraft.network.protocol.game.CommonPlayerSpawnInfo(world.dimensionTypeRegistration(),world.dimension(),0x13572468ABCDEF01L,GameType.SURVIVAL,GameType.CREATIVE,false,true,java.util.Optional.empty(),0,63),net.minecraft.network.protocol.game.ClientboundRespawnPacket.KEEP_ALL_DATA);
                net.minecraft.network.protocol.game.ClientboundRespawnPacket.STREAM_CODEC.encode(buffer,outgoing);
                net.minecraft.network.protocol.game.ClientboundRespawnPacket incoming=net.minecraft.network.protocol.game.ClientboundRespawnPacket.STREAM_CODEC.decode(buffer);
                require(RespawnPacketTrace.describe(outgoing).equals(RespawnPacketTrace.describe(incoming)),"Respawn observer differs before/after native decoding");
                require(RespawnPacketTrace.describe(incoming).equals("RESPAWN dimension=minecraft:overworld hashedSeed="+0x13572468ABCDEF01L),"Native 1.16 hashed seed missing from the packet observation");
            } finally {buffer.release();}
        });
        check("rejected_spawn_keeps_death_inventory", () -> {
            ServerPlayer player=new ServerPlayer(server,world,new GameProfile(UUID.randomUUID(),"PortFixture"),net.minecraft.server.level.ClientInformation.createDefault());
            ArrayList<ItemEntity> drops=new ArrayList<>();
            drops.add(new ItemEntity(world,8,80,8,new ItemStack(Items.DIAMOND,7)));
            var veto = EntityJoinLevelEvent.BUS.addListener(e -> e.getEntity() instanceof EntityLootableBody);
            try { new PlayerDeathEventHandler().entityDropEvent(new LivingDropsEvent(player,player.damageSources().generic(),drops,false)); }
            finally { EntityJoinLevelEvent.BUS.removeListener(veto); }
            require(drops.size()==1 && drops.get(0).getItem().getCount()==7,"original drops were discarded");
        });
        check("duplicate_slots_preserve_both_records", () -> {
            EntityLootableBody body=new EntityLootableBody(world);
            CompoundTag nbt=new CompoundTag(); FixtureApi.writeAdditional(body,nbt);
            ListTag equipment=new ListTag();
            for(int i=0;i<2;i++) { CompoundTag item=FixtureStackData.save(new ItemStack(Items.DIAMOND,i+2)); item.putByte("Slot",(byte)10); equipment.add(item); }
            nbt.put("Equipment",equipment); FixtureApi.readAdditional(body,nbt);
            CompoundTag saved=new CompoundTag();FixtureApi.writeAdditional(body,saved);
            int count=0; for(String key:Arrays.asList("Equipment","Aux")) {
                ListTag list=typedList(saved, key, 10);
                for(int i=0;i<list.size();i++) count+=list.getCompoundOrEmpty(i).getIntOr("count", 0);
            }
            require(count==5,"duplicate slot overwrote a recoverable stack");
        });
        check("missing_item_keeps_raw_recovery_record", () -> {
            EntityLootableBody body=new EntityLootableBody(world); CompoundTag nbt=new CompoundTag();FixtureApi.writeAdditional(body,nbt);
            CompoundTag item=FixtureStackData.save(new ItemStack(Items.DIAMOND,7));item.putString("id","missingfixture:keepsake");item.putByte("Slot",(byte)10);
            ListTag list=new ListTag();list.add(item);nbt.put("Equipment",list);FixtureApi.readAdditional(body,nbt);
            CompoundTag saved=new CompoundTag();FixtureApi.writeAdditional(body,saved);
            require(typedList(saved, "LootableBodiesUnresolved", 10).size()==1,"missing item was erased");
            require(!body.isEmpty(),"recoverable item allowed empty-body decay");
        });
        check("invalid_slot_retains_inventory", () -> {
            EntityLootableBody body=new EntityLootableBody(world); CompoundTag nbt=new CompoundTag();FixtureApi.writeAdditional(body,nbt);
            CompoundTag item=FixtureStackData.save(new ItemStack(Items.EMERALD,9));item.putInt("Slot",12345);
            ListTag list=new ListTag();list.add(item);nbt.put("Equipment",list);FixtureApi.readAdditional(body,nbt);
            CompoundTag saved=new CompoundTag();FixtureApi.writeAdditional(body,saved);
            require(!body.isEmpty(),"invalid slot discarded inventory");
        });
        check("malformed_name_and_timer_are_retained", () -> {
            EntityLootableBody body=new EntityLootableBody(world); CompoundTag nbt=new CompoundTag();FixtureApi.writeAdditional(body,nbt);
            nbt.put("Name",IntTag.valueOf(17));nbt.putDouble("DeathTime",Double.NaN);FixtureApi.readAdditional(body,nbt);
            CompoundTag saved=new CompoundTag();FixtureApi.writeAdditional(body,saved);
            require(typedList(saved, "LootableBodiesInvalidFields", 10).size()==2,"malformed fields lost without recovery");
        });
        System.out.println("PORT_BASELINE_RESULT failures="+failures);
        require(failures==0,"Port baseline regressions: "+failures);
    }
}
