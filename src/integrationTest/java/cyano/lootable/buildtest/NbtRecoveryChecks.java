package cyano.lootable.buildtest;
import net.minecraft.inventory.container.Slot;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.nbt.ShortNBT;
import net.minecraft.nbt.LongNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.IntNBT;
import net.minecraft.nbt.FloatNBT;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ByteNBT;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;

/** Malformed data is never permission to erase the surrounding inventory. */
final class NbtRecoveryChecks {
    static final String RECOVERY="LootableBodiesUnresolved";
    private interface Check {void run() throws Exception;}
    private static ServerWorld world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    static void run(MinecraftServer server,ServerWorld target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        check("duplicate_distinct_stacks_keep_first_and_retain_second",()->duplicate(false));
        check("duplicate_identical_stacks_are_not_deduplicated",()->duplicate(true));
        check("missing_duplicate_does_not_erase_known_stack",NbtRecoveryChecks::missingDuplicate);
        for(int slot:new int[]{-1,48,127,256}) {final int s=slot;check("invalid_slot_"+s,()->invalidSlot(IntNBT.valueOf(s)));}
        check("missing_slot_does_not_default_to_helmet",()->invalidSlot(null));
        check("string_slot_is_not_coerced_to_zero",()->invalidSlot(StringNBT.valueOf("7")));
        check("fractional_slot_is_not_truncated",()->invalidSlot(FloatNBT.valueOf(6.5F)));
        check("long_slot_is_not_narrowed",()->invalidSlot(LongNBT.valueOf(4294967302L)));
        check("valid_wide_slot_is_read_without_byte_wrap",NbtRecoveryChecks::validWideSlot);
        check("missing_equipment_item_raw_retained",()->unreadable("Equipment","id",StringNBT.valueOf("absentfixture:missing")));
        check("missing_aux_item_raw_retained",()->unreadable("Aux","id",StringNBT.valueOf("absentfixture:missing")));
        check("air_item_is_not_a_replacement",()->unreadable("Equipment","id",StringNBT.valueOf("minecraft:air")));
        check("numeric_item_id_not_guessed",()->unreadable("Equipment","id",ShortNBT.valueOf((short)264)));
        check("missing_count_retained_raw",()->unreadable("Equipment","Count",null));
        check("zero_count_retained_raw",()->unreadable("Equipment","Count",ByteNBT.valueOf((byte)0)));
        check("negative_count_retained_raw",()->unreadable("Equipment","Count",ByteNBT.valueOf((byte)-1)));
        check("wide_count_does_not_wrap_to_zero",()->unreadable("Equipment","Count",IntNBT.valueOf(256)));
        check("string_count_retained_raw",()->unreadable("Equipment","Count",StringNBT.valueOf("17")));
        check("fractional_count_retained_raw",()->unreadable("Equipment","Count",FloatNBT.valueOf(17.5F)));
        check("valid_integer_count_preserved",NbtRecoveryChecks::integerCount);
        check("wrong_damage_type_retained_raw",()->unreadable("Equipment","Damage",StringNBT.valueOf("17")));
        check("negative_damage_not_reset",()->unreadable("Equipment","Damage",ShortNBT.valueOf((short)-1)));
        check("wide_damage_not_narrowed",()->unreadable("Equipment","Damage",IntNBT.valueOf(32768)));
        check("wrong_custom_tag_type_retained_raw",()->unreadable("Equipment","tag",StringNBT.valueOf("opaque")));
        check("unknown_capability_payload_retained_raw",NbtRecoveryChecks::unknownCaps);
        check("unknown_stack_root_data_retained_raw",()->unreadable("Equipment","UnknownPayload",StringNBT.valueOf("retain verbatim")));
        check("wrong_equipment_list_type_retained",()->wrongList("Equipment"));
        check("wrong_aux_list_type_retained",()->wrongList("Aux"));
        check("empty_aux_record_retained_without_null_queue",NbtRecoveryChecks::emptyAux);
        check("repeated_read_replaces_unresolved_without_growth",NbtRecoveryChecks::repeated);
        check("clear_and_reused_writer_do_not_resurrect_old_data",NbtRecoveryChecks::clear);
        check("normal_save_layout_round_trip_unchanged",NbtRecoveryChecks::normal);
        check("oversized_stack_split_without_count_loss",NbtRecoveryChecks::oversized);
        check("invalid_armor_item_recoverable_in_overflow",NbtRecoveryChecks::invalidArmor);
        check("raw_only_body_not_empty_or_removed",NbtRecoveryChecks::rawOwner);
        check("partial_release_keeps_raw_owner_without_duplicate_drops",NbtRecoveryChecks::partialRelease);
        check("read_is_detached_from_saved_input",NbtRecoveryChecks::detached);
        check("retry_returned_item_at_original_free_slot",()->retry(false));
        check("retry_returned_item_never_overwrites_occupied_slot",()->retry(true));
        check("unknown_recovery_record_preserved_verbatim",NbtRecoveryChecks::futureRecord);
        check("wrong_recovery_tag_retained_idempotently",NbtRecoveryChecks::wrongRecovery);
        check("malformed_first_record_does_not_hide_valid_tail",NbtRecoveryChecks::tail);
        check("valid_aux_queue_order_preserved",NbtRecoveryChecks::auxOrder);
        check("entity_factory_keeps_malformed_corpse_and_identity",NbtRecoveryChecks::factory);
        check("custom_equipment_authoritative_over_vanilla_mirrors",NbtRecoveryChecks::mirrors);
        check("opaque_equipment_keeps_recoverable_vanilla_mirror",NbtRecoveryChecks::mirrorFallback);
        check("missing_damage_keeps_vanilla_zero_default",NbtRecoveryChecks::missingDamage);
        check("oversized_aux_split_without_loss",NbtRecoveryChecks::oversizedAux);
        try(PrintWriter out=new PrintWriter(new File(folder,"nbt-checks.txt"),"UTF-8")) {for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        RuntimeChecks.require(failures==0,"NBT recovery regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_NBT_PASS checks="+results.size());
    }
    private static void check(String name,Check test){
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        try{test.run();results.add("PASS "+name);System.out.println("NBT_CHECK_PASS "+name);}
        catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("NBT_CHECK_FAIL "+name+": "+e);e.printStackTrace();}
        finally{for(Entity e:new ArrayList<Entity>(FixtureApi.entities(world)))if(!before.contains(e))world.removeEntity(e);}
    }
    static ItemStack item(int index,int count){ItemStack s=RuntimeChecks.stack(index);s.setCount(count);return s;}
    static CompoundNBT entry(int index,int count,int slot){CompoundNBT n=item(index,count).write(new CompoundNBT());n.putByte("Slot",(byte)slot);return n;}
    static ListNBT list(CompoundNBT... entries){ListNBT l=new ListNBT();for(CompoundNBT n:entries)l.add(n.copy());return l;}
    static CompoundNBT root(CompoundNBT... entries){CompoundNBT n=new CompoundNBT();n.put("Equipment",list(entries));n.putByte("Vac",(byte)20);return n;}
    static CompoundNBT saved(EntityLootableBody b){CompoundNBT n=new CompoundNBT();b.writeAdditional(n);return n;}
    static EntityLootableBody read(CompoundNBT n){CompoundNBT copy=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.readAdditional(n);RuntimeChecks.require(n.equals(copy),"Reader modified source NBT");return b;}
    private static void equal(ItemStack a,ItemStack b,String why){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),why);}
    private static void add(Map<String,Integer> sums,ItemStack s){if((s == null || s.isEmpty()) || s.getCount()<=0)return;CompoundNBT n=s.write(new CompoundNBT());n.remove("Count");String k=n.toString();sums.put(k,sums.getOrDefault(k,0)+s.getCount());}
    static Map<String,Integer> items(EntityLootableBody b){Map<String,Integer> sums=new TreeMap<>();for(int i=0;i<48;i++)add(sums,b.getStackInSlot(i));ListNBT aux=saved(b).getList("Aux",10);for(int i=0;i<aux.size();i++)add(sums,ItemStack.read(aux.getCompound(i)));return sums;}
    private static void contains(EntityLootableBody b,ItemStack... expected){Map<String,Integer> sums=new TreeMap<>();for(ItemStack s:expected)add(sums,s);RuntimeChecks.require(items(b).equals(sums),"Recoverable item/count/damage/full NBT changed: expected="+sums+" actual="+items(b)+" retained="+saved(b).get(RECOVERY));}
    static void retained(EntityLootableBody b,INBT raw){ListNBT records=saved(b).getList(RECOVERY,10);boolean found=false;for(int i=0;i<records.size();i++)if(raw.equals(records.getCompound(i).get("Data")))found=true;RuntimeChecks.require(found,"Unresolved raw data was erased or changed");}
    private static void stable(EntityLootableBody b){CompoundNBT a=saved(b),copy=a.copy();EntityLootableBody again=read(a);RuntimeChecks.require(a.equals(copy),"Reader mutated written data");RuntimeChecks.require(Objects.equals(a.get("Equipment"),saved(again).get("Equipment")) && Objects.equals(a.get("Aux"),saved(again).get("Aux")) && Objects.equals(a.get(RECOVERY),saved(again).get(RECOVERY)),"Repeated save/read grows, loses or reorders inventory");}
    private static void duplicate(boolean same){CompoundNBT first=entry(120,17,6),second=entry(same?120:121,same?17:11,6);EntityLootableBody b=read(root(first,second));equal(b.getStackInSlot(6),item(120,17),"Duplicate overwrote first valid slot");contains(b,item(120,17),item(same?120:121,same?17:11));stable(b);}
    private static void missingDuplicate(){CompoundNBT raw=entry(121,11,6);raw.putString("id","absentfixture:missing");EntityLootableBody b=read(root(entry(120,17,6),raw));equal(b.getStackInSlot(6),item(120,17),"Missing duplicate erased valid slot");retained(b,raw);stable(b);}
    private static void invalidSlot(INBT slot){CompoundNBT e=entry(120,17,6);if(slot==null)e.remove("Slot");else e.put("Slot",slot);EntityLootableBody b=read(root(entry(0,1,0),e));equal(b.getStackInSlot(0),item(0,1),"Invalid slot overwrote helmet");contains(b,item(0,1),item(120,17));RuntimeChecks.require(saved(b).getList("Aux",10).size()==1,"Invalid slot did not enter existing overflow");stable(b);}
    private static void validWideSlot(){CompoundNBT e=entry(120,17,7);e.putInt("Slot",7);EntityLootableBody b=read(root(e));equal(b.getStackInSlot(7),item(120,17),"Valid numeric slot changed");stable(b);}
    private static void unreadable(String source,String key,INBT value){CompoundNBT raw=entry(120,17,6);if(value==null)raw.remove(key);else raw.put(key,value);CompoundNBT n=root(entry(121,11,7));if(source.equals("Equipment"))n.getList("Equipment",10).add(raw.copy());else n.put("Aux",list(raw));EntityLootableBody b=read(n);equal(b.getStackInSlot(7),item(121,11),"Unreadable sibling erased valid slot");contains(b,item(121,11));retained(b,raw);stable(b);}
    private static void integerCount(){CompoundNBT e=entry(120,17,6);e.putInt("Count",17);EntityLootableBody b=read(root(e));contains(b,item(120,17));stable(b);}
    private static void unknownCaps(){CompoundNBT caps=new CompoundNBT();caps.putString("absentfixture:inventory","opaque capabilities");unreadable("Equipment","ForgeCaps",caps);}
    private static void wrongList(String source){ListNBT wrong=new ListNBT();wrong.add(StringNBT.valueOf("opaque inventory payload"));CompoundNBT n=root(entry(121,11,7));n.put(source,wrong);EntityLootableBody b=read(n);retained(b,wrong);stable(b);}
    private static void emptyAux(){CompoundNBT n=root(entry(120,17,6));n.put("Aux",list(new CompoundNBT()));EntityLootableBody b=read(n);retained(b,new CompoundNBT());contains(b,item(120,17));b.baseTick();stable(b);}
    private static void repeated(){CompoundNBT raw=entry(120,17,6);raw.putString("id","absentfixture:missing");CompoundNBT n=root(raw);EntityLootableBody b=read(n);b.readAdditional(n);retained(b,raw);RuntimeChecks.require(saved(b).getList(RECOVERY,10).size()==1,"Repeated load accumulated unresolved records");stable(b);}
    private static void clear(){CompoundNBT raw=entry(120,17,6);raw.putString("id","absentfixture:missing");CompoundNBT n=root(entry(121,11,7),raw);n.put("Aux",list(entry(122,5,0)));EntityLootableBody b=read(n);CompoundNBT target=saved(b);b.clear();b.writeAdditional(target);RuntimeChecks.require(target.getList("Equipment",10).size()==0 && !target.contains("Aux") && !target.contains(RECOVERY),"Writer left stale cleared inventory in reused compound");}
    private static void normal(){EntityLootableBody b=new EntityLootableBody(world);for(int i=0;i<48;i++)b.setInventorySlotContents(i,RuntimeChecks.stack(i));b.addItem(item(120,17));CompoundNBT before=saved(b);EntityLootableBody copy=read(before);RuntimeChecks.require(saved(copy).equals(before),"Healthy legacy save changed");RuntimeChecks.require(!before.contains(RECOVERY),"Healthy save gained a recovery tag");}
    private static void oversized(){EntityLootableBody b=read(root(entry(120,127,6)));equal(b.getStackInSlot(6),item(120,64),"Oversized slot exceeds item capacity");contains(b,item(120,127));stable(b);}
    private static void invalidArmor(){EntityLootableBody b=read(root(entry(120,17,0)));equal(b.getStackInSlot(0),null,"Invalid armor equipped");contains(b,item(120,17));stable(b);}
    private static void rawOwner(){CompoundNBT raw=entry(120,17,6);raw.putString("id","absentfixture:missing");EntityLootableBody b=read(root(raw));b.setPosition(160.5,80,160.5);b.setNoGravity(true);world.getChunk(10,10);RuntimeChecks.require(world.addEntity(b),"Raw owner spawn");RuntimeChecks.require(!b.isEmpty(),"Unresolved inventory counted as empty");b.onKillCommand();for(int i=0;i<15;i++)b.baseTick();RuntimeChecks.require(b.isAlive() && !b.removed,"Unresolved inventory owner removed");retained(b,raw);}
    private static void partialRelease(){CompoundNBT raw=entry(120,17,6);raw.putString("id","absentfixture:missing");EntityLootableBody b=read(root(raw,entry(121,11,7)));b.setPosition(160.5,80,160.5);b.setNoGravity(true);world.getChunk(10,10);world.addEntity(b);b.onKillCommand();for(int i=0;i<15;i++)b.baseTick();RuntimeChecks.require(b.isAlive(),"Partial recovery removed raw owner");retained(b,raw);int count=0;for(Entity e:FixtureApi.entities(world))if(e instanceof ItemEntity && !e.removed){equal(((ItemEntity)e).getItem(),item(121,11),"Unexpected/duplicated partial release");count++;}RuntimeChecks.require(count==1,"Recoverable stack was not released exactly once");}
    private static void detached(){CompoundNBT e=entry(120,17,6),n=root(e),copy=n.copy();EntityLootableBody b=read(n);b.getStackInSlot(6).getTag().putString("changed","live only");RuntimeChecks.require(n.equals(copy),"Live stack aliases raw saved input");}
    static CompoundNBT record(String source,INBT data){CompoundNBT n=new CompoundNBT();n.putString("Source",source);n.put("Data",data.copy());return n;}
    private static void retry(boolean occupied){CompoundNBT n=occupied?root(entry(121,11,6)):root();n.put(RECOVERY,list(record("Equipment",entry(120,17,6))));EntityLootableBody b=read(n);equal(b.getStackInSlot(6),occupied?item(121,11):item(120,17),"Retry changed original slot ownership");if(occupied)contains(b,item(121,11),item(120,17));else contains(b,item(120,17));RuntimeChecks.require(!saved(b).contains(RECOVERY),"Resolved record was retained and can duplicate on reload");stable(b);}
    private static void futureRecord(){CompoundNBT record=new CompoundNBT();record.putString("Source","future-format");record.putLong("Opaque",7712);CompoundNBT n=root();n.put(RECOVERY,list(record));EntityLootableBody b=read(n);RuntimeChecks.require(saved(b).getList(RECOVERY,10).getCompound(0).equals(record),"Unknown recovery record changed");stable(b);}
    private static void wrongRecovery(){CompoundNBT n=root();StringNBT raw=StringNBT.valueOf("opaque recovery data");n.put(RECOVERY,raw);EntityLootableBody b=read(n);retained(b,raw);stable(b);}
    private static void tail(){CompoundNBT broken=entry(120,17,6);broken.putInt("Slot",999);EntityLootableBody b=read(root(broken,entry(121,11,47)));equal(b.getStackInSlot(47),item(121,11),"Bad first record hid valid tail");contains(b,item(120,17),item(121,11));}
    private static void auxOrder(){CompoundNBT n=root();n.put("Aux",list(entry(120,17,0),entry(121,11,0)));EntityLootableBody b=read(n);ListNBT aux=saved(b).getList("Aux",10);equal(ItemStack.read(aux.getCompound(0)),item(120,17),"Aux head changed");equal(ItemStack.read(aux.getCompound(1)),item(121,11),"Aux tail changed");stable(b);}
    private static void mirrors(){EntityLootableBody original=new EntityLootableBody(world);original.setInventorySlotContents(4,item(4,1));CompoundNBT n=saved(original);n.put("Equipment",list(entry(120,17,6)));EntityLootableBody b=read(n);contains(b,item(120,17));stable(b);}
    private static void mirrorFallback(){EntityLootableBody original=new EntityLootableBody(world);original.setInventorySlotContents(4,item(4,1));CompoundNBT n=saved(original);StringNBT raw=StringNBT.valueOf("opaque inventory");n.put("Equipment",raw);EntityLootableBody b=read(n);contains(b,item(4,1));retained(b,raw);stable(b);}
    private static void missingDamage(){CompoundNBT e=entry(4,1,6);e.remove("Damage");e.getCompound("tag").remove("Damage");EntityLootableBody b=read(root(e));ItemStack expected=item(4,1);expected.getTag().remove("Damage");RuntimeChecks.require(expected.getDamage()==0,"Native missing durability must default to zero");contains(b,expected);RuntimeChecks.require(!saved(b).contains(RECOVERY),"Missing vanilla-zero damage was quarantined");stable(b);}
    private static void oversizedAux(){CompoundNBT n=root();n.put("Aux",list(entry(120,127,0)));EntityLootableBody b=read(n);contains(b,item(120,127));ListNBT aux=saved(b).getList("Aux",10);RuntimeChecks.require(aux.size()==2,"Oversized auxiliary stack not split");for(int i=0;i<aux.size();i++)RuntimeChecks.require(ItemStack.read(aux.getCompound(i)).getCount()<=64,"Aux exceeds capacity");stable(b);}
    private static void factory(){EntityLootableBody original=new EntityLootableBody(world);original.setUniqueId(UUID.fromString("00000000-0000-4000-8000-000000000120"));original.setPosition(160.5,80,160.5);original.setRotation(37);original.setCustomNameTag("NbtFixture");CompoundNBT n=new CompoundNBT();original.writeUnlessPassenger(n);CompoundNBT broken=entry(120,17,6);broken.putInt("Slot",999);n.put("Equipment",list(broken,entry(121,11,47)));Entity loaded=FixtureApi.loadEntity(n,world);RuntimeChecks.require(loaded instanceof EntityLootableBody,"Malformed inventory discarded whole corpse");EntityLootableBody b=(EntityLootableBody)loaded;RuntimeChecks.require(b.getUniqueID().equals(original.getUniqueID()) && b.getPosition().equals(original.getPosition()) && b.rotationYaw==37 && b.getCustomNameTag().equals("NbtFixture"),"Entity identity/base fields changed");contains(b,item(120,17),item(121,11));}
}
