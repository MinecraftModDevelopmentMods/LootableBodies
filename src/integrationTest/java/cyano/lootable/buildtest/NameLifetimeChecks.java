package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Real legacy-engine contracts for owned scalar fields; no production dependency. */
final class NameLifetimeChecks {
    static final String RECOVERY="LootableBodiesInvalidFields";
    private interface Check {void run() throws Exception;}
    private static WorldServer world;
    private static final List<String> results=new ArrayList<>();
    private static int failures;
    static void run(MinecraftServer server,WorldServer target,File folder) throws Exception {
        world=target;results.clear();failures=0;
        NBTBase[] wrong={new NBTTagString("not a timer"),new NBTTagFloat(1.5F),new NBTTagDouble(Double.NaN),new NBTTagDouble(Double.POSITIVE_INFINITY),new NBTTagCompound(),new NBTTagList(),new NBTTagByteArray(new byte[]{1}),new NBTTagIntArray(new int[]{1})};
        for(String key:new String[]{"Vac","DeathTime","DecayTime"}) {
            for(int i=0;i<wrong.length;i++){final NBTBase value=wrong[i];check(key+"_invalid_type_"+i,()->invalid(key,value));}
            for(long value:new long[]{Long.MIN_VALUE,-1,Long.MAX_VALUE,10001})check(key+"_invalid_value_"+value,()->invalid(key,new NBTTagLong(value)));
        }
        for(int i=0;i<wrong.length;i++){final NBTBase value=wrong[i] instanceof NBTTagString?new NBTTagInt(27):wrong[i];check("Name_invalid_type_"+i,()->badName("Name",value));}
        check("Name_over_packet_string_limit",()->badName("Name",new NBTTagString(repeat('n',32768))));
        check("Name_utf8_bytes_over_packet_limit",()->badName("Name",new NBTTagString(repeat('\u20ac',10923))));
        check("CustomName_utf8_bytes_over_packet_limit",()->badName("CustomName",new NBTTagString(repeat('\u20ac',10923))));
        check("name_exact_packet_byte_limit_preserved",NameLifetimeChecks::packetBoundary);
        check("CustomName_wrong_type_retained",()->badName("CustomName",new NBTTagInt(27)));
        check("CustomName_over_packet_string_limit",()->badName("CustomName",new NBTTagString(repeat('c',32768))));
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
        long clock=world.getTotalWorldTime(),rate=LootableBodies.ticksPerItemDecay,life=LootableBodies.corpseDecayTime;
        boolean decay=LootableBodies.allowCorpseDecay,empty=LootableBodies.decayOnlyWhenEmpty;
        Set<Entity> before=new HashSet<>(world.loadedEntityList);
        try{world.getWorldInfo().setWorldTotalTime(10000);LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;test.run();results.add("PASS "+name);System.out.println("NAME_LIFETIME_PASS "+name);}
        catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("NAME_LIFETIME_FAIL "+name+": "+e);e.printStackTrace();}
        finally{world.getWorldInfo().setWorldTotalTime(clock);LootableBodies.ticksPerItemDecay=rate;LootableBodies.corpseDecayTime=life;LootableBodies.allowCorpseDecay=decay;LootableBodies.decayOnlyWhenEmpty=empty;for(Entity e:new ArrayList<Entity>(world.loadedEntityList))if(!before.contains(e))world.removeEntityDangerously(e);}
    }
    static NBTTagCompound base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setCustomNameTag("Saved Owner");b.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));NBTTagCompound n=new NBTTagCompound();b.writeToNBTOptional(n);n.setString("Name","Saved Owner");n.setByte("Vac",(byte)20);return n;}
    static EntityLootableBody read(NBTTagCompound n){NBTTagCompound copy=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.readFromNBT(n);RuntimeChecks.require(same(n,copy),"Input NBT mutated");return b;}
    static NBTTagCompound saved(EntityLootableBody b){NBTTagCompound n=new NBTTagCompound();b.writeToNBTOptional(n);return n;}
    static void retained(EntityLootableBody b,String key,NBTBase raw){NBTTagList l=saved(b).getTagList(RECOVERY,10);boolean found=false;for(int i=0;i<l.tagCount();i++){NBTTagCompound r=l.getCompoundTagAt(i);if(key.equals(r.getString("Source")) && same(raw,r.getTag("Data")))found=true;}RuntimeChecks.require(found,"Raw "+key+" not retained");}
    private static void stable(EntityLootableBody b){NBTTagCompound a=saved(b);EntityLootableBody c=read(a);NBTTagCompound z=saved(c);for(String key:new String[]{"Equipment","Aux",NbtRecoveryChecks.RECOVERY,RECOVERY,"Name","CustomName","Vac","DeathTime","DecayTime"})RuntimeChecks.require(same(a.getTag(key),z.getTag(key)),"Reload changed "+key);RuntimeChecks.require(NbtRecoveryChecks.items(b).equals(NbtRecoveryChecks.items(c)),"Reload changed inventory");}
    private static void invalid(String key,NBTBase raw){NBTTagCompound n=base();n.setTag(key,raw.copy());EntityLootableBody b=read(n);NBTTagCompound s=saved(b);RuntimeChecks.require(key.equals("Vac")?!s.hasKey("Vac"):s.getLong(key)==10000,"Invalid timer did not use safe load-time default");retained(b,key,raw);RuntimeChecks.require(RuntimeChecks.sameStack(b.getStackInSlot(6),NbtRecoveryChecks.item(120,17)),"Timer erased inventory");stable(b);}
    private static void badName(String key,NBTBase raw){NBTTagCompound n=base();n.setTag(key,raw.copy());if(key.equals("CustomName"))n.removeTag("Name");EntityLootableBody b=read(n);RuntimeChecks.require(b.getCustomNameTag().equals(key.equals("Name")?"Saved Owner":""),"Bad name lost fallback or entered active metadata");retained(b,key,raw);stable(b);}
    private static void fallback(){NBTTagCompound n=base();n.removeTag("Name");EntityLootableBody b=read(n);b.onEntityUpdate();RuntimeChecks.require(b.getGameProfile()!=null && b.getGameProfile().getName().equals("Saved Owner"),"Fallback profile lost");stable(b);}
    private static void namePriority(){NBTTagCompound n=base();n.setString("Name","A long display name / \u00a7a\u00e9");EntityLootableBody b=read(n);b.onEntityUpdate();RuntimeChecks.require(b.getGameProfile().getName().equals(n.getString("Name")),"Valid historical display name rejected");stable(b);}
    private static void packetBoundary(){for(String value:new String[]{repeat('a',32767),repeat('\u20ac',10922)+"a"}){NBTTagCompound n=base();n.setString("Name",value);EntityLootableBody b=read(n);RuntimeChecks.require(b.getCustomNameTag().equals(value) && !saved(b).hasKey(RECOVERY),"Valid packet boundary name rejected");io.netty.buffer.ByteBuf bytes=io.netty.buffer.Unpooled.buffer();try{new net.minecraft.network.PacketBuffer(bytes).writeString(b.getCustomNameTag());RuntimeChecks.require(new net.minecraft.network.PacketBuffer(bytes).readString(32767).equals(value),"Name packet changed");}finally{bytes.release();}stable(b);}}
    private static void earlySave(){EntityLootableBody b=read(base());RuntimeChecks.require(saved(b).getString("Name").equals("Saved Owner"),"Lazy profile erased Name before first tick");}
    private static void missingName(){EntityLootableBody b=read(base());b.onEntityUpdate();NBTTagCompound n=base();n.removeTag("Name");n.removeTag("CustomName");b.readFromNBT(n);RuntimeChecks.require(b.getCustomNameTag().isEmpty() && b.getGameProfile()==null,"Repeated load kept old attribution");b.onEntityUpdate();RuntimeChecks.require(b.getGameProfile()==null && !saved(b).hasKey("Name"),"Old profile resurrected");}
    private static void reusedWriter(){EntityLootableBody b=read(base());b.onEntityUpdate();NBTTagCompound n=saved(b);n.setByte("Vac",(byte)3);b.setUserName("");b.writeEntityToNBT(n);RuntimeChecks.require(!n.hasKey("Name") && !n.hasKey("Vac"),"Writer resurrected old scalar fields");}
    private static void clearedCustomName(){EntityLootableBody b=read(base());b.onEntityUpdate();NBTTagCompound n=saved(b);b.setUserName("");b.writeToNBTOptional(n);RuntimeChecks.require(!n.hasKey("CustomName") && read(n).getCustomNameTag().isEmpty(),"Reused vanilla CustomName restored cleared attribution");}
    private static void blankName(){NBTTagCompound n=base();n.setString("Name","   ");EntityLootableBody b=read(n);b.onEntityUpdate();RuntimeChecks.require(b.getCustomNameTag().isEmpty() && b.getGameProfile()==null && !saved(b).hasKey("Name"),"Blank name semantics changed");}
    private static void missingTimers(){EntityLootableBody b=read(base());NBTTagCompound n=base();n.removeTag("DeathTime");n.removeTag("DecayTime");n.removeTag("Vac");world.getWorldInfo().setWorldTotalTime(11000);b.readFromNBT(n);NBTTagCompound s=saved(b);RuntimeChecks.require(s.getLong("DeathTime")==11000 && s.getLong("DecayTime")==11000 && !s.hasKey("Vac"),"Repeated load kept stale timers");}
    private static void animation(){NBTTagCompound n=base();n.setLong("DeathTime",9999);EntityLootableBody b=read(n);RuntimeChecks.require(b.deathTime==0,"Custom lifetime loaded into vanilla short death animation");}
    private static void integral(int type){NBTBase[] values={new NBTTagByte((byte)10),new NBTTagShort((short)10),new NBTTagInt(10),new NBTTagLong(10)};NBTTagCompound n=base();for(String k:new String[]{"Vac","DeathTime","DecayTime"})n.setTag(k,values[type].copy());EntityLootableBody b=read(n);NBTTagCompound s=saved(b);RuntimeChecks.require(s.getByte("Vac")==10 && s.getLong("DeathTime")==10 && s.getLong("DecayTime")==10 && !s.hasKey(RECOVERY),"Lossless integral scalar changed");}
    private static void zero(){NBTTagCompound n=base();for(String k:new String[]{"Vac","DeathTime","DecayTime"})n.setLong(k,0);EntityLootableBody b=read(n);NBTTagCompound s=saved(b);RuntimeChecks.require(s.getLong("DeathTime")==0 && s.getLong("DecayTime")==0 && s.hasKey("Vac") && s.getByte("Vac")==0 && !s.hasKey(RECOVERY),"Valid zero discarded");}
    private static void vacuumLimit(){NBTTagCompound n=base();n.setLong("Vac",20);RuntimeChecks.require(!saved(read(n)).hasKey(RECOVERY),"Valid limit rejected");invalid("Vac",new NBTTagInt(21));}
    private static EntityLootableBody timed(long timestamp){NBTTagCompound n=base();n.setLong("DeathTime",timestamp);n.setLong("DecayTime",timestamp);return read(n);}
    private static void future(){NBTTagCompound n=base();n.setLong("DeathTime",Long.MAX_VALUE);n.setLong("DecayTime",Long.MAX_VALUE);EntityLootableBody b=read(n);LootableBodies.ticksPerItemDecay=10;world.getWorldInfo().setWorldTotalTime(10011);b.onEntityUpdate();RuntimeChecks.require(saved(b).getLong("DecayTime")==10010,"Future timestamp stalled normal progression");}
    private static void rollback(){EntityLootableBody b=timed(9999);world.getWorldInfo().setWorldTotalTime(100);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=false;LootableBodies.ticksPerItemDecay=10;b.onEntityUpdate();RuntimeChecks.require(saved(b).getLong("DeathTime")==100 && saved(b).getLong("DecayTime")==100 && termination(b)<0,"Clock rollback did not rebase safely");}
    private static void negativeClock(){EntityLootableBody b=timed(0);world.getWorldInfo().setWorldTotalTime(Long.MIN_VALUE);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=false;LootableBodies.ticksPerItemDecay=10;b.onEntityUpdate();RuntimeChecks.require(saved(b).getLong("DeathTime")>=0 && saved(b).getLong("DecayTime")>=0 && termination(b)<0,"Negative world clock caused invalid lifetime");}
    private static EntityLootableBody tool(long now,long interval){world.getWorldInfo().setWorldTotalTime(now);EntityLootableBody b=timed(0);b.clear();ItemStack tool=new ItemStack(Items.DIAMOND_PICKAXE);tool.setItemDamage(17);b.setInventorySlotContents(6,tool);LootableBodies.ticksPerItemDecay=interval;return b;}
    private static void catchup(long now,long interval){EntityLootableBody b=tool(now,interval);b.onEntityUpdate();ItemStack s=b.getStackInSlot(6);RuntimeChecks.require((s != null && !s.isEmpty()) && s.getCount()==1 && s.getItemDamage()==s.getMaxDamage()-1,"Catchup overflow repaired, erased or under-decayed tool");long stamp=now-now%interval;RuntimeChecks.require(saved(b).getLong("DecayTime")==stamp,"Catchup timestamp wrapped or lost remainder");b.onEntityUpdate();RuntimeChecks.require(saved(b).getLong("DecayTime")==stamp && s.getItemDamage()==s.getMaxDamage()-1,"Catchup repeated");}
    private static void largeCatchup(){catchup((1L<<31)+1,1);}
    private static void maxCatchup(){catchup(Long.MAX_VALUE,6000);}
    private static void longInterval(){EntityLootableBody b=tool(Long.MAX_VALUE,Long.MAX_VALUE/2);b.onEntityUpdate();RuntimeChecks.require(saved(b).getLong("DecayTime")==Long.MAX_VALUE-1 && b.getStackInSlot(6).getItemDamage()==19,"Wide interval arithmetic changed");}
    private static void boundary(){EntityLootableBody b=tool(10,10);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=false;LootableBodies.corpseDecayTime=10;b.onEntityUpdate();RuntimeChecks.require(b.getStackInSlot(6).getItemDamage()==17 && termination(b)<0,"Strict > boundary changed");world.getWorldInfo().setWorldTotalTime(11);b.onEntityUpdate();RuntimeChecks.require(b.getStackInSlot(6).getItemDamage()==18 && termination(b)>=0,"Elapsed threshold ignored");}
    private static void occupied(){EntityLootableBody b=timed(0);LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=true;LootableBodies.corpseDecayTime=40;b.onEntityUpdate();RuntimeChecks.require(saved(b).getLong("DeathTime")==10000 && termination(b)<0,"Occupied default no longer pauses expiration");}
    private static void disabled(){EntityLootableBody b=tool(Long.MAX_VALUE,-1);b.onEntityUpdate();RuntimeChecks.require(b.getStackInSlot(6).getItemDamage()==17 && saved(b).getLong("DecayTime")==0 && saved(b).getLong("DeathTime")==0,"Disabled timer changed state");}
    private static int termination(EntityLootableBody b){try{Field f=EntityLootableBody.class.getDeclaredField("terminate");f.setAccessible(true);return f.getInt(b);}catch(Exception e){throw new AssertionError(e);}}
    private static void metadataOnly(){NBTTagCompound n=base();n.setTag("Equipment",new NBTTagList());n.setString("DeathTime","opaque");EntityLootableBody b=read(n);RuntimeChecks.require(b.isEmpty(),"Scalar evidence counted as inventory");LootableBodies.allowCorpseDecay=true;LootableBodies.decayOnlyWhenEmpty=true;LootableBodies.corpseDecayTime=40;world.getWorldInfo().setWorldTotalTime(10041);b.onEntityUpdate();RuntimeChecks.require(termination(b)>=0,"Scalar evidence made empty corpse immortal");}
    private static void inventory(){NBTTagCompound n=base();NBTTagCompound missing=NbtRecoveryChecks.entry(121,11,7);missing.setString("id","absentfixture:missing");n.getTagList("Equipment",10).appendTag(missing);n.setString("DeathTime","bad");n.setTag("Name",new NBTTagCompound());EntityLootableBody b=read(n);NbtRecoveryChecks.retained(b,missing);stable(b);b.readFromNBT(saved(b));stable(b);RuntimeChecks.require(saved(b).getTagList(RECOVERY,10).tagCount()==2 && saved(b).getTagList(NbtRecoveryChecks.RECOVERY,10).tagCount()==1,"Raw scalar/item evidence accumulated or disappeared");}
    private static void unknownRecovery(){NBTTagCompound n=base(),record=new NBTTagCompound();record.setString("Future","opaque");n.setTag(RECOVERY,NbtRecoveryChecks.list(record));EntityLootableBody b=read(n);RuntimeChecks.require(saved(b).getTagList(RECOVERY,10).getCompoundTagAt(0).equals(record),"Unknown metadata evidence changed");stable(b);}
    private static void wrongRecovery(){NBTTagCompound n=base();NBTTagString raw=new NBTTagString("opaque");n.setTag(RECOVERY,raw);EntityLootableBody b=read(n);retained(b,"Recovery",raw);stable(b);}
    private static void healthy(){EntityLootableBody b=read(base());b.onEntityUpdate();stable(b);RuntimeChecks.require(!saved(b).hasKey(RECOVERY),"Healthy save gained invalid-field data");}
    // NBTTagDouble.equals uses ==, so NaN copies are unequal without any mutation.
    static boolean same(NBTBase a,NBTBase b){if(a==null || b==null)return a==b;return Arrays.equals(encoded(a),encoded(b));}
    private static byte[] encoded(NBTBase value){try{NBTTagCompound n=new NBTTagCompound();n.setTag("Value",value);ByteArrayOutputStream out=new ByteArrayOutputStream();CompressedStreamTools.write(n,new DataOutputStream(out));return out.toByteArray();}catch(IOException e){throw new AssertionError(e);}}
    private static String repeat(char c,int size){char[] a=new char[size];Arrays.fill(a,c);return new String(a);}
}
