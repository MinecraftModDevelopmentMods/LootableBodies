package cyano.lootable.buildtest;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.ByteArrayTag;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Real legacy-engine contracts for owned scalar fields; no production dependency. */
final class NameLifetimeChecks {
    static final String RECOVERY="LootableBodiesInvalidFields";
    private interface Check {void run() throws Exception;}
    private static ServerLevel world;
    private static final List<String> results=new ArrayList<>();
    private static int failures;
    static void run(MinecraftServer server,ServerLevel target,File folder) throws Exception {
        world=target;results.clear();failures=0;
        Tag[] wrong={StringTag.valueOf("not a timer"),FloatTag.valueOf(1.5F),DoubleTag.valueOf(Double.NaN),DoubleTag.valueOf(Double.POSITIVE_INFINITY),new CompoundTag(),new ListTag(),new ByteArrayTag(new byte[]{1}),new IntArrayTag(new int[]{1})};
        for(String key:new String[]{"Vac","DeathTime","DecayTime"}) {
            for(int i=0;i<wrong.length;i++){final Tag value=wrong[i];check(key+"_invalid_type_"+i,()->invalid(key,value));}
            for(long value:new long[]{Long.MIN_VALUE,-1,Long.MAX_VALUE,10001})check(key+"_invalid_value_"+value,()->invalid(key,LongTag.valueOf(value)));
        }
        for(int i=0;i<wrong.length;i++){final Tag value=wrong[i] instanceof StringTag?IntTag.valueOf(27):wrong[i];check("Name_invalid_type_"+i,()->badName("Name",value));}
        check("Name_over_packet_string_limit",()->badName("Name",StringTag.valueOf(repeat('n',32768))));
        check("Name_utf8_bytes_over_packet_limit",()->badName("Name",StringTag.valueOf(repeat('\u20ac',10923))));
        check("CustomName_utf8_bytes_over_packet_limit",()->badName("CustomName",StringTag.valueOf(repeat('\u20ac',10923))));
        check("name_exact_packet_byte_limit_preserved",NameLifetimeChecks::packetBoundary);
        check("old_maximum_name_remains_recoverable_when_native_json_cannot_fit",()->{CompoundTag n=base();String original=repeat('a',32767);n.putString("Name",original);EntityLootableBody b=read(n);RuntimeChecks.require(b.getCustomNameTag().equals("Saved Owner"),"Unencodable native name entered metadata");retained(b,"Name",StringTag.valueOf(original));stable(b);});
        check("CustomName_wrong_type_retained",()->badName("CustomName",IntTag.valueOf(27)));
        check("CustomName_over_packet_string_limit",()->badName("CustomName",StringTag.valueOf(repeat('c',32768))));
        check("custom_name_fallback_and_source_immutable",NameLifetimeChecks::fallback);
        check("valid_name_authoritative_without_username_regex",NameLifetimeChecks::namePriority);
        check("save_before_first_tick_keeps_name",NameLifetimeChecks::earlySave);
        check("missing_name_clears_reused_profile_and_name",NameLifetimeChecks::missingName);
        check("reused_writer_clears_name_and_completed_vacuum",NameLifetimeChecks::reusedWriter);
        check("reused_full_writer_does_not_restore_cleared_name",NameLifetimeChecks::clearedCustomName);
        check("blank_name_keeps_legacy_unnamed_behavior",NameLifetimeChecks::blankName);
        check("missing_timers_reset_reused_instance_to_now",NameLifetimeChecks::missingTimers);
        check("custom_timestamp_not_vanilla_death_animation",NameLifetimeChecks::animation);
        for(int i=0;i<4;i++){final int type=i;check("integral_scalar_type_"+i,()->integral(type));}
        check("zero_timestamps_and_vacuum_are_valid",NameLifetimeChecks::zero);
        check("vacuum_upper_bound_and_invalid_21",NameLifetimeChecks::vacuumLimit);
        check("future_timestamp_does_not_delay_forever",NameLifetimeChecks::future);
        check("clock_rollback_rebases_both_timers",NameLifetimeChecks::rollback);
        check("negative_world_clock_cannot_overflow_age",NameLifetimeChecks::negativeClock);
        check("large_catchup_does_not_wrap_damage_or_repeat",NameLifetimeChecks::largeCatchup);
        check("long_max_elapsed_time_and_remainder",NameLifetimeChecks::maxCatchup);
        check("long_interval_catchup_does_not_overflow",NameLifetimeChecks::longInterval);
        check("strict_decay_boundary_preserved",NameLifetimeChecks::boundary);
        check("nonempty_default_pauses_corpse_decay",NameLifetimeChecks::occupied);
        check("disabled_decay_preserves_items_and_timestamps",NameLifetimeChecks::disabled);
        check("invalid_metadata_does_not_count_as_inventory",NameLifetimeChecks::metadataOnly);
        check("scalar_and_unresolved_inventory_conserved_on_reload",NameLifetimeChecks::inventory);
        check("unknown_scalar_recovery_record_preserved",NameLifetimeChecks::unknownRecovery);
        check("wrong_scalar_recovery_tag_preserved_idempotently",NameLifetimeChecks::wrongRecovery);
        check("healthy_save_has_no_scalar_recovery_tag",NameLifetimeChecks::healthy);
        try(PrintWriter out=new PrintWriter(new File(folder,"name-lifetime-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        RuntimeChecks.require(failures==0,"Name/lifetime regressions: "+failures+" / "+results.size());
        System.out.println("LOOTABLE_BODIES_NAME_LIFETIME_PASS checks="+results.size());
    }
    private static void check(String name,Check test) {
        long clock=world.getGameTime(),rate=LootableBodies.ticksPerItemDecay,life=LootableBodies.corpseDecayTime;
        boolean decay=LootableBodies.allowCorpseDecay,empty=LootableBodies.decayOnlyWhenEmpty;
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        try{((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(10000);LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;test.run();results.add("PASS "+name);System.out.println("NAME_LIFETIME_PASS "+name);}
        catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("NAME_LIFETIME_FAIL "+name+": "+e);e.printStackTrace();}
        finally{((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(clock);LootableBodies.ticksPerItemDecay=rate;LootableBodies.corpseDecayTime=life;LootableBodies.allowCorpseDecay=decay;LootableBodies.decayOnlyWhenEmpty=empty;for(Entity e:new ArrayList<Entity>(FixtureApi.entities(world)))if(!before.contains(e))world.removeEntity(e);}
    }
    static CompoundTag base(){EntityLootableBody b=new EntityLootableBody(world);b.setPos(160.5,80,160.5);b.setNoGravity(true);b.setCustomNameTag("Saved Owner");b.setItem(6,NbtRecoveryChecks.item(120,17));CompoundTag n=new CompoundTag();b.saveAsPassenger(n);n.putString("Name","Saved Owner");n.putByte("Vac",(byte)20);return n;}
    static EntityLootableBody read(CompoundTag n){CompoundTag copy=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.load(n);RuntimeChecks.require(same(n,copy),"Input NBT mutated");return b;}
    static CompoundTag saved(EntityLootableBody b){CompoundTag n=new CompoundTag();b.saveAsPassenger(n);return n;}
    static void retained(EntityLootableBody b,String key,Tag raw){ListTag l=saved(b).getList(RECOVERY,10);boolean found=false;for(int i=0;i<l.size();i++){CompoundTag r=l.getCompound(i);if(key.equals(r.getString("Source")) && same(raw,r.get("Data")))found=true;}RuntimeChecks.require(found,"Raw "+key+" not retained");}
    private static void stable(EntityLootableBody b){CompoundTag a=saved(b);EntityLootableBody c=read(a);CompoundTag z=saved(c);for(String key:new String[]{"Equipment","Aux",NbtRecoveryChecks.RECOVERY,RECOVERY,"Name","CustomName","Vac","DeathTime","DecayTime"})RuntimeChecks.require(same(a.get(key),z.get(key)),"Reload changed "+key);RuntimeChecks.require(NbtRecoveryChecks.items(b).equals(NbtRecoveryChecks.items(c)),"Reload changed inventory");}
    private static void invalid(String key,Tag raw){CompoundTag n=base();n.put(key,raw.copy());EntityLootableBody b=read(n);CompoundTag s=saved(b);RuntimeChecks.require(key.equals("Vac")?!s.contains("Vac"):s.getLong(key)==10000,"Invalid timer did not use safe load-time default");retained(b,key,raw);RuntimeChecks.require(RuntimeChecks.sameStack(b.getItem(6),NbtRecoveryChecks.item(120,17)),"Timer erased inventory");stable(b);}
    private static void badName(String key,Tag raw){CompoundTag n=base();n.put(key,raw.copy());if(key.equals("CustomName"))n.remove("Name");EntityLootableBody b=read(n);RuntimeChecks.require(b.getCustomNameTag().equals(key.equals("Name")?"Saved Owner":""),"Bad name lost fallback or entered active metadata");retained(b,key,raw);stable(b);}
    private static void fallback(){CompoundTag n=base();n.remove("Name");EntityLootableBody b=read(n);b.baseTick();RuntimeChecks.require(b.getGameProfile()!=null && b.getGameProfile().getName().equals("Saved Owner"),"Fallback profile lost");stable(b);}
    private static void namePriority(){CompoundTag n=base();n.putString("Name","A long display name / \u00a7a\u00e9");EntityLootableBody b=read(n);b.baseTick();RuntimeChecks.require(b.getGameProfile().getName().equals(n.getString("Name")),"Valid historical display name rejected");stable(b);}
    private static void packetBoundary(){for(String value:new String[]{repeat('a',32756),repeat('\u20ac',10918)+"ab"}){CompoundTag n=base();n.putString("Name",value);EntityLootableBody b=read(n);RuntimeChecks.require(b.getCustomNameTag().equals(value) && !saved(b).contains(RECOVERY),"Valid packet boundary name rejected");io.netty.buffer.ByteBuf bytes=io.netty.buffer.Unpooled.buffer();try{new net.minecraft.network.FriendlyByteBuf(bytes).writeComponent(b.getCustomName());RuntimeChecks.require(new net.minecraft.network.FriendlyByteBuf(bytes).readComponent().getString().equals(value),"Name packet changed");}finally{bytes.release();}stable(b);}}
    private static void earlySave(){EntityLootableBody b=read(base());RuntimeChecks.require(saved(b).getString("Name").equals("Saved Owner"),"Lazy profile erased Name before first tick");}
    private static void missingName(){EntityLootableBody b=read(base());b.baseTick();CompoundTag n=base();n.remove("Name");n.remove("CustomName");b.load(n);RuntimeChecks.require(b.getCustomNameTag().isEmpty() && b.getGameProfile()==null,"Repeated load kept old attribution");b.baseTick();RuntimeChecks.require(b.getGameProfile()==null && !saved(b).contains("Name"),"Old profile resurrected");}
    private static void reusedWriter(){EntityLootableBody b=read(base());b.baseTick();CompoundTag n=saved(b);n.putByte("Vac",(byte)3);b.setUserName("");b.addAdditionalSaveData(n);RuntimeChecks.require(!n.contains("Name") && !n.contains("Vac"),"Writer resurrected old scalar fields");}
    private static void clearedCustomName(){EntityLootableBody b=read(base());b.baseTick();CompoundTag n=saved(b);b.setUserName("");b.saveAsPassenger(n);RuntimeChecks.require(!n.contains("CustomName") && read(n).getCustomNameTag().isEmpty(),"Reused vanilla CustomName restored cleared attribution");}
    private static void blankName(){CompoundTag n=base();n.putString("Name","   ");EntityLootableBody b=read(n);b.baseTick();RuntimeChecks.require(b.getCustomNameTag().isEmpty() && b.getGameProfile()==null && !saved(b).contains("Name"),"Blank name semantics changed");}
    private static void missingTimers(){EntityLootableBody b=read(base());CompoundTag n=base();n.remove("DeathTime");n.remove("DecayTime");n.remove("Vac");((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(11000);b.load(n);CompoundTag s=saved(b);RuntimeChecks.require(s.getLong("DeathTime")==11000 && s.getLong("DecayTime")==11000 && !s.contains("Vac"),"Repeated load kept stale timers");}
    private static void animation(){CompoundTag n=base();n.putLong("DeathTime",9999);EntityLootableBody b=read(n);RuntimeChecks.require(b.deathTime==0,"Custom lifetime loaded into vanilla short death animation");}
    private static void integral(int type){Tag[] values={ByteTag.valueOf((byte)10),ShortTag.valueOf((short)10),IntTag.valueOf(10),LongTag.valueOf(10)};CompoundTag n=base();for(String k:new String[]{"Vac","DeathTime","DecayTime"})n.put(k,values[type].copy());EntityLootableBody b=read(n);CompoundTag s=saved(b);RuntimeChecks.require(s.getByte("Vac")==10 && s.getLong("DeathTime")==10 && s.getLong("DecayTime")==10 && !s.contains(RECOVERY),"Lossless integral scalar changed");}
    private static void zero(){CompoundTag n=base();for(String k:new String[]{"Vac","DeathTime","DecayTime"})n.putLong(k,0);EntityLootableBody b=read(n);CompoundTag s=saved(b);RuntimeChecks.require(s.getLong("DeathTime")==0 && s.getLong("DecayTime")==0 && s.contains("Vac") && s.getByte("Vac")==0 && !s.contains(RECOVERY),"Valid zero discarded");}
    private static void vacuumLimit(){CompoundTag n=base();n.putLong("Vac",20);RuntimeChecks.require(!saved(read(n)).contains(RECOVERY),"Valid limit rejected");invalid("Vac",IntTag.valueOf(21));}
    private static EntityLootableBody timed(long timestamp){CompoundTag n=base();n.putLong("DeathTime",timestamp);n.putLong("DecayTime",timestamp);return read(n);}
    private static void future(){CompoundTag n=base();n.putLong("DeathTime",Long.MAX_VALUE);n.putLong("DecayTime",Long.MAX_VALUE);EntityLootableBody b=read(n);LootableBodies.ticksPerItemDecay=10;((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(10011);b.baseTick();RuntimeChecks.require(saved(b).getLong("DecayTime")==10010,"Future timestamp stalled normal progression");}
    private static void rollback(){EntityLootableBody b=timed(9999);((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(100);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=false;LootableBodies.ticksPerItemDecay=10;b.baseTick();RuntimeChecks.require(saved(b).getLong("DeathTime")==100 && saved(b).getLong("DecayTime")==100 && termination(b)<0,"Clock rollback did not rebase safely");}
    private static void negativeClock(){EntityLootableBody b=timed(0);((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(Long.MIN_VALUE);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=false;LootableBodies.ticksPerItemDecay=10;b.baseTick();RuntimeChecks.require(saved(b).getLong("DeathTime")>=0 && saved(b).getLong("DecayTime")>=0 && termination(b)<0,"Negative world clock caused invalid lifetime");}
    private static EntityLootableBody tool(long now,long interval){((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(now);EntityLootableBody b=timed(0);b.clearContent();ItemStack tool=new ItemStack(Items.DIAMOND_PICKAXE);tool.setDamageValue(17);b.setItem(6,tool);LootableBodies.ticksPerItemDecay=interval;return b;}
    private static void catchup(long now,long interval){EntityLootableBody b=tool(now,interval);b.baseTick();ItemStack s=b.getItem(6);RuntimeChecks.require((s != null && !s.isEmpty()) && s.getCount()==1 && s.getDamageValue()==s.getMaxDamage()-1,"Catchup overflow repaired, erased or under-decayed tool");long stamp=now-now%interval;RuntimeChecks.require(saved(b).getLong("DecayTime")==stamp,"Catchup timestamp wrapped or lost remainder");b.baseTick();RuntimeChecks.require(saved(b).getLong("DecayTime")==stamp && s.getDamageValue()==s.getMaxDamage()-1,"Catchup repeated");}
    private static void largeCatchup(){catchup((1L<<31)+1,1);}
    private static void maxCatchup(){catchup(Long.MAX_VALUE,6000);}
    private static void longInterval(){EntityLootableBody b=tool(Long.MAX_VALUE,Long.MAX_VALUE/2);b.baseTick();RuntimeChecks.require(saved(b).getLong("DecayTime")==Long.MAX_VALUE-1 && b.getItem(6).getDamageValue()==19,"Wide interval arithmetic changed");}
    private static void boundary(){EntityLootableBody b=tool(10,10);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=false;LootableBodies.corpseDecayTime=10;b.baseTick();RuntimeChecks.require(b.getItem(6).getDamageValue()==17 && termination(b)<0,"Strict > boundary changed");((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(11);b.baseTick();RuntimeChecks.require(b.getItem(6).getDamageValue()==18 && termination(b)>=0,"Elapsed threshold ignored");}
    private static void occupied(){EntityLootableBody b=timed(0);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=true;LootableBodies.corpseDecayTime=40;b.baseTick();RuntimeChecks.require(saved(b).getLong("DeathTime")==10000 && termination(b)<0,"Occupied default no longer pauses expiration");}
    private static void disabled(){EntityLootableBody b=tool(Long.MAX_VALUE,-1);b.baseTick();RuntimeChecks.require(b.getItem(6).getDamageValue()==17 && saved(b).getLong("DecayTime")==0 && saved(b).getLong("DeathTime")==0,"Disabled timer changed state");}
    private static int termination(EntityLootableBody b){try{Field f=EntityLootableBody.class.getDeclaredField("terminate");f.setAccessible(true);return f.getInt(b);}catch(Exception e){throw new AssertionError(e);}}
    private static void metadataOnly(){CompoundTag n=base();n.put("Equipment",new ListTag());n.putString("DeathTime","opaque");EntityLootableBody b=read(n);RuntimeChecks.require(b.isEmpty(),"Scalar evidence counted as inventory");LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=true;LootableBodies.corpseDecayTime=40;((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(10041);b.baseTick();RuntimeChecks.require(termination(b)>=0,"Scalar evidence made empty corpse immortal");}
    private static void inventory(){CompoundTag n=base();CompoundTag missing=NbtRecoveryChecks.entry(121,11,7);missing.putString("id","absentfixture:missing");n.getList("Equipment",10).add(missing);n.putString("DeathTime","bad");n.put("Name",new CompoundTag());EntityLootableBody b=read(n);NbtRecoveryChecks.retained(b,missing);stable(b);b.load(saved(b));stable(b);RuntimeChecks.require(saved(b).getList(RECOVERY,10).size()==2 && saved(b).getList(NbtRecoveryChecks.RECOVERY,10).size()==1,"Raw scalar/item evidence accumulated or disappeared");}
    private static void unknownRecovery(){CompoundTag n=base(),record=new CompoundTag();record.putString("Future","opaque");n.put(RECOVERY,NbtRecoveryChecks.list(record));EntityLootableBody b=read(n);RuntimeChecks.require(saved(b).getList(RECOVERY,10).getCompound(0).equals(record),"Unknown metadata evidence changed");stable(b);}
    private static void wrongRecovery(){CompoundTag n=base();StringTag raw=StringTag.valueOf("opaque");n.put(RECOVERY,raw);EntityLootableBody b=read(n);retained(b,"Recovery",raw);stable(b);}
    private static void healthy(){EntityLootableBody b=read(base());b.baseTick();stable(b);RuntimeChecks.require(!saved(b).contains(RECOVERY),"Healthy save gained invalid-field data");}
    // DoubleNBT.equals uses ==, so NaN copies are unequal without any mutation.
    static boolean same(Tag a,Tag b){return TypedNbt.same(a,b);}
    private static String repeat(char c,int size){char[] a=new char[size];Arrays.fill(a,c);return new String(a);}
}
