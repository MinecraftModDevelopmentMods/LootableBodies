package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Malformed data is never permission to erase the surrounding inventory. */
final class NbtRecoveryChecks {
    static final String RECOVERY="LootableBodiesUnresolved";
    private interface Check {void run() throws Exception;}
    private static WorldServer world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    static void run(MinecraftServer server,WorldServer target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        check("duplicate_distinct_stacks_keep_first_and_retain_second",()->duplicate(false));
        check("duplicate_identical_stacks_are_not_deduplicated",()->duplicate(true));
        check("missing_duplicate_does_not_erase_known_stack",NbtRecoveryChecks::missingDuplicate);
        for(int slot:new int[]{-1,48,127,256}) {final int s=slot;check("invalid_slot_"+s,()->invalidSlot(new NBTTagInt(s)));}
        check("missing_slot_does_not_default_to_helmet",()->invalidSlot(null));
        check("string_slot_is_not_coerced_to_zero",()->invalidSlot(new NBTTagString("7")));
        check("fractional_slot_is_not_truncated",()->invalidSlot(new NBTTagFloat(6.5F)));
        check("long_slot_is_not_narrowed",()->invalidSlot(new NBTTagLong(4294967302L)));
        check("valid_wide_slot_is_read_without_byte_wrap",NbtRecoveryChecks::validWideSlot);
        check("missing_equipment_item_raw_retained",()->unreadable("Equipment","id",new NBTTagString("absentfixture:missing")));
        check("missing_aux_item_raw_retained",()->unreadable("Aux","id",new NBTTagString("absentfixture:missing")));
        check("air_item_is_not_a_replacement",()->unreadable("Equipment","id",new NBTTagString("minecraft:air")));
        check("numeric_item_id_not_guessed",()->unreadable("Equipment","id",new NBTTagShort((short)264)));
        check("missing_count_retained_raw",()->unreadable("Equipment","Count",null));
        check("zero_count_retained_raw",()->unreadable("Equipment","Count",new NBTTagByte((byte)0)));
        check("negative_count_retained_raw",()->unreadable("Equipment","Count",new NBTTagByte((byte)-1)));
        check("wide_count_does_not_wrap_to_zero",()->unreadable("Equipment","Count",new NBTTagInt(256)));
        check("string_count_retained_raw",()->unreadable("Equipment","Count",new NBTTagString("17")));
        check("fractional_count_retained_raw",()->unreadable("Equipment","Count",new NBTTagFloat(17.5F)));
        check("valid_integer_count_preserved",NbtRecoveryChecks::integerCount);
        check("wrong_damage_type_retained_raw",()->unreadable("Equipment","Damage",new NBTTagString("17")));
        check("negative_damage_not_reset",()->unreadable("Equipment","Damage",new NBTTagShort((short)-1)));
        check("wide_damage_not_narrowed",()->unreadable("Equipment","Damage",new NBTTagInt(32768)));
        check("wrong_custom_tag_type_retained_raw",()->unreadable("Equipment","tag",new NBTTagString("opaque")));
        check("unknown_capability_payload_retained_raw",NbtRecoveryChecks::unknownCaps);
        check("unknown_stack_root_data_retained_raw",()->unreadable("Equipment","UnknownPayload",new NBTTagString("retain verbatim")));
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
        Set<Entity> before=new HashSet<>(world.loadedEntityList);
        try{test.run();results.add("PASS "+name);System.out.println("NBT_CHECK_PASS "+name);}
        catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("NBT_CHECK_FAIL "+name+": "+e);e.printStackTrace();}
        finally{for(Entity e:new ArrayList<Entity>(world.loadedEntityList))if(!before.contains(e))world.removeEntityDangerously(e);}
    }
    static ItemStack item(int index,int count){ItemStack s=RuntimeChecks.stack(index);s.setCount(count);return s;}
    static NBTTagCompound entry(int index,int count,int slot){NBTTagCompound n=item(index,count).write(new NBTTagCompound());n.setByte("Slot",(byte)slot);return n;}
    static NBTTagList list(NBTTagCompound... entries){NBTTagList l=new NBTTagList();for(NBTTagCompound n:entries)l.add(n.copy());return l;}
    static NBTTagCompound root(NBTTagCompound... entries){NBTTagCompound n=new NBTTagCompound();n.setTag("Equipment",list(entries));n.setByte("Vac",(byte)20);return n;}
    static NBTTagCompound saved(EntityLootableBody b){NBTTagCompound n=new NBTTagCompound();b.writeAdditional(n);return n;}
    static EntityLootableBody read(NBTTagCompound n){NBTTagCompound copy=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.readAdditional(n);RuntimeChecks.require(n.equals(copy),"Reader modified source NBT");return b;}
    private static void equal(ItemStack a,ItemStack b,String why){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),why);}
    private static void add(Map<String,Integer> sums,ItemStack s){if((s == null || s.isEmpty()) || s.getCount()<=0)return;NBTTagCompound n=s.write(new NBTTagCompound());n.removeTag("Count");String k=n.toString();sums.put(k,sums.getOrDefault(k,0)+s.getCount());}
    static Map<String,Integer> items(EntityLootableBody b){Map<String,Integer> sums=new TreeMap<>();for(int i=0;i<48;i++)add(sums,b.getStackInSlot(i));NBTTagList aux=saved(b).getList("Aux",10);for(int i=0;i<aux.size();i++)add(sums,ItemStack.read(aux.getCompound(i)));return sums;}
    private static void contains(EntityLootableBody b,ItemStack... expected){Map<String,Integer> sums=new TreeMap<>();for(ItemStack s:expected)add(sums,s);RuntimeChecks.require(items(b).equals(sums),"Recoverable item/count/damage/full NBT changed: expected="+sums+" actual="+items(b)+" retained="+saved(b).getTag(RECOVERY));}
    static void retained(EntityLootableBody b,INBTBase raw){NBTTagList records=saved(b).getList(RECOVERY,10);boolean found=false;for(int i=0;i<records.size();i++)if(raw.equals(records.getCompound(i).getTag("Data")))found=true;RuntimeChecks.require(found,"Unresolved raw data was erased or changed");}
    private static void stable(EntityLootableBody b){NBTTagCompound a=saved(b),copy=a.copy();EntityLootableBody again=read(a);RuntimeChecks.require(a.equals(copy),"Reader mutated written data");RuntimeChecks.require(Objects.equals(a.getTag("Equipment"),saved(again).getTag("Equipment")) && Objects.equals(a.getTag("Aux"),saved(again).getTag("Aux")) && Objects.equals(a.getTag(RECOVERY),saved(again).getTag(RECOVERY)),"Repeated save/read grows, loses or reorders inventory");}
    private static void duplicate(boolean same){NBTTagCompound first=entry(120,17,6),second=entry(same?120:121,same?17:11,6);EntityLootableBody b=read(root(first,second));equal(b.getStackInSlot(6),item(120,17),"Duplicate overwrote first valid slot");contains(b,item(120,17),item(same?120:121,same?17:11));stable(b);}
    private static void missingDuplicate(){NBTTagCompound raw=entry(121,11,6);raw.setString("id","absentfixture:missing");EntityLootableBody b=read(root(entry(120,17,6),raw));equal(b.getStackInSlot(6),item(120,17),"Missing duplicate erased valid slot");retained(b,raw);stable(b);}
    private static void invalidSlot(INBTBase slot){NBTTagCompound e=entry(120,17,6);if(slot==null)e.removeTag("Slot");else e.setTag("Slot",slot);EntityLootableBody b=read(root(entry(0,1,0),e));equal(b.getStackInSlot(0),item(0,1),"Invalid slot overwrote helmet");contains(b,item(0,1),item(120,17));RuntimeChecks.require(saved(b).getList("Aux",10).size()==1,"Invalid slot did not enter existing overflow");stable(b);}
    private static void validWideSlot(){NBTTagCompound e=entry(120,17,7);e.setInt("Slot",7);EntityLootableBody b=read(root(e));equal(b.getStackInSlot(7),item(120,17),"Valid numeric slot changed");stable(b);}
    private static void unreadable(String source,String key,INBTBase value){NBTTagCompound raw=entry(120,17,6);if(value==null)raw.removeTag(key);else raw.setTag(key,value);NBTTagCompound n=root(entry(121,11,7));if(source.equals("Equipment"))n.getList("Equipment",10).add(raw.copy());else n.setTag("Aux",list(raw));EntityLootableBody b=read(n);equal(b.getStackInSlot(7),item(121,11),"Unreadable sibling erased valid slot");contains(b,item(121,11));retained(b,raw);stable(b);}
    private static void integerCount(){NBTTagCompound e=entry(120,17,6);e.setInt("Count",17);EntityLootableBody b=read(root(e));contains(b,item(120,17));stable(b);}
    private static void unknownCaps(){NBTTagCompound caps=new NBTTagCompound();caps.setString("absentfixture:inventory","opaque capabilities");unreadable("Equipment","ForgeCaps",caps);}
    private static void wrongList(String source){NBTTagList wrong=new NBTTagList();wrong.add(new NBTTagString("opaque inventory payload"));NBTTagCompound n=root(entry(121,11,7));n.setTag(source,wrong);EntityLootableBody b=read(n);retained(b,wrong);stable(b);}
    private static void emptyAux(){NBTTagCompound n=root(entry(120,17,6));n.setTag("Aux",list(new NBTTagCompound()));EntityLootableBody b=read(n);retained(b,new NBTTagCompound());contains(b,item(120,17));b.baseTick();stable(b);}
    private static void repeated(){NBTTagCompound raw=entry(120,17,6);raw.setString("id","absentfixture:missing");NBTTagCompound n=root(raw);EntityLootableBody b=read(n);b.readAdditional(n);retained(b,raw);RuntimeChecks.require(saved(b).getList(RECOVERY,10).size()==1,"Repeated load accumulated unresolved records");stable(b);}
    private static void clear(){NBTTagCompound raw=entry(120,17,6);raw.setString("id","absentfixture:missing");NBTTagCompound n=root(entry(121,11,7),raw);n.setTag("Aux",list(entry(122,5,0)));EntityLootableBody b=read(n);NBTTagCompound target=saved(b);b.clear();b.writeAdditional(target);RuntimeChecks.require(target.getList("Equipment",10).size()==0 && !target.hasKey("Aux") && !target.hasKey(RECOVERY),"Writer left stale cleared inventory in reused compound");}
    private static void normal(){EntityLootableBody b=new EntityLootableBody(world);for(int i=0;i<48;i++)b.setInventorySlotContents(i,RuntimeChecks.stack(i));b.addItem(item(120,17));NBTTagCompound before=saved(b);EntityLootableBody copy=read(before);RuntimeChecks.require(saved(copy).equals(before),"Healthy legacy save changed");RuntimeChecks.require(!before.hasKey(RECOVERY),"Healthy save gained a recovery tag");}
    private static void oversized(){EntityLootableBody b=read(root(entry(120,127,6)));equal(b.getStackInSlot(6),item(120,64),"Oversized slot exceeds item capacity");contains(b,item(120,127));stable(b);}
    private static void invalidArmor(){EntityLootableBody b=read(root(entry(120,17,0)));equal(b.getStackInSlot(0),null,"Invalid armor equipped");contains(b,item(120,17));stable(b);}
    private static void rawOwner(){NBTTagCompound raw=entry(120,17,6);raw.setString("id","absentfixture:missing");EntityLootableBody b=read(root(raw));b.setPosition(160.5,80,160.5);b.setNoGravity(true);world.getChunk(10,10);RuntimeChecks.require(world.spawnEntity(b),"Raw owner spawn");RuntimeChecks.require(!b.isEmpty(),"Unresolved inventory counted as empty");b.onKillCommand();for(int i=0;i<15;i++)b.baseTick();RuntimeChecks.require(b.isAlive() && !b.removed,"Unresolved inventory owner removed");retained(b,raw);}
    private static void partialRelease(){NBTTagCompound raw=entry(120,17,6);raw.setString("id","absentfixture:missing");EntityLootableBody b=read(root(raw,entry(121,11,7)));b.setPosition(160.5,80,160.5);b.setNoGravity(true);world.getChunk(10,10);world.spawnEntity(b);b.onKillCommand();for(int i=0;i<15;i++)b.baseTick();RuntimeChecks.require(b.isAlive(),"Partial recovery removed raw owner");retained(b,raw);int count=0;for(Entity e:world.loadedEntityList)if(e instanceof EntityItem && !e.removed){equal(((EntityItem)e).getItem(),item(121,11),"Unexpected/duplicated partial release");count++;}RuntimeChecks.require(count==1,"Recoverable stack was not released exactly once");}
    private static void detached(){NBTTagCompound e=entry(120,17,6),n=root(e),copy=n.copy();EntityLootableBody b=read(n);b.getStackInSlot(6).getTag().setString("changed","live only");RuntimeChecks.require(n.equals(copy),"Live stack aliases raw saved input");}
    static NBTTagCompound record(String source,INBTBase data){NBTTagCompound n=new NBTTagCompound();n.setString("Source",source);n.setTag("Data",data.copy());return n;}
    private static void retry(boolean occupied){NBTTagCompound n=occupied?root(entry(121,11,6)):root();n.setTag(RECOVERY,list(record("Equipment",entry(120,17,6))));EntityLootableBody b=read(n);equal(b.getStackInSlot(6),occupied?item(121,11):item(120,17),"Retry changed original slot ownership");if(occupied)contains(b,item(121,11),item(120,17));else contains(b,item(120,17));RuntimeChecks.require(!saved(b).hasKey(RECOVERY),"Resolved record was retained and can duplicate on reload");stable(b);}
    private static void futureRecord(){NBTTagCompound record=new NBTTagCompound();record.setString("Source","future-format");record.setLong("Opaque",7712);NBTTagCompound n=root();n.setTag(RECOVERY,list(record));EntityLootableBody b=read(n);RuntimeChecks.require(saved(b).getList(RECOVERY,10).getCompound(0).equals(record),"Unknown recovery record changed");stable(b);}
    private static void wrongRecovery(){NBTTagCompound n=root();NBTTagString raw=new NBTTagString("opaque recovery data");n.setTag(RECOVERY,raw);EntityLootableBody b=read(n);retained(b,raw);stable(b);}
    private static void tail(){NBTTagCompound broken=entry(120,17,6);broken.setInt("Slot",999);EntityLootableBody b=read(root(broken,entry(121,11,47)));equal(b.getStackInSlot(47),item(121,11),"Bad first record hid valid tail");contains(b,item(120,17),item(121,11));}
    private static void auxOrder(){NBTTagCompound n=root();n.setTag("Aux",list(entry(120,17,0),entry(121,11,0)));EntityLootableBody b=read(n);NBTTagList aux=saved(b).getList("Aux",10);equal(ItemStack.read(aux.getCompound(0)),item(120,17),"Aux head changed");equal(ItemStack.read(aux.getCompound(1)),item(121,11),"Aux tail changed");stable(b);}
    private static void mirrors(){EntityLootableBody original=new EntityLootableBody(world);original.setInventorySlotContents(4,item(4,1));NBTTagCompound n=saved(original);n.setTag("Equipment",list(entry(120,17,6)));EntityLootableBody b=read(n);contains(b,item(120,17));stable(b);}
    private static void mirrorFallback(){EntityLootableBody original=new EntityLootableBody(world);original.setInventorySlotContents(4,item(4,1));NBTTagCompound n=saved(original);NBTTagString raw=new NBTTagString("opaque inventory");n.setTag("Equipment",raw);EntityLootableBody b=read(n);contains(b,item(4,1));retained(b,raw);stable(b);}
    private static void missingDamage(){NBTTagCompound e=entry(4,1,6);e.removeTag("Damage");e.getCompound("tag").removeTag("Damage");EntityLootableBody b=read(root(e));ItemStack expected=item(4,1);expected.getTag().removeTag("Damage");RuntimeChecks.require(expected.getDamage()==0,"Native missing durability must default to zero");contains(b,expected);RuntimeChecks.require(!saved(b).hasKey(RECOVERY),"Missing vanilla-zero damage was quarantined");stable(b);}
    private static void oversizedAux(){NBTTagCompound n=root();n.setTag("Aux",list(entry(120,127,0)));EntityLootableBody b=read(n);contains(b,item(120,127));NBTTagList aux=saved(b).getList("Aux",10);RuntimeChecks.require(aux.size()==2,"Oversized auxiliary stack not split");for(int i=0;i<aux.size();i++)RuntimeChecks.require(ItemStack.read(aux.getCompound(i)).getCount()<=64,"Aux exceeds capacity");stable(b);}
    private static void factory(){EntityLootableBody original=new EntityLootableBody(world);original.setUniqueId(UUID.fromString("00000000-0000-4000-8000-000000000120"));original.setPosition(160.5,80,160.5);original.setRotation(37);original.setCustomNameTag("NbtFixture");NBTTagCompound n=new NBTTagCompound();original.writeUnlessPassenger(n);NBTTagCompound broken=entry(120,17,6);broken.setInt("Slot",999);n.setTag("Equipment",list(broken,entry(121,11,47)));Entity loaded=net.minecraft.entity.EntityType.create(n,world);RuntimeChecks.require(loaded instanceof EntityLootableBody,"Malformed inventory discarded whole corpse");EntityLootableBody b=(EntityLootableBody)loaded;RuntimeChecks.require(b.getUniqueID().equals(original.getUniqueID()) && b.getPosition().equals(original.getPosition()) && b.rotationYaw==37 && b.getCustomNameTag().equals("NbtFixture"),"Entity identity/base fields changed");contains(b,item(120,17),item(121,11));}
}
