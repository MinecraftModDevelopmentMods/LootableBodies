package cyano.lootable.buildtest;
import net.minecraft.entity.ai.attributes.Attribute;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.nbt.ShortNBT;
import net.minecraft.nbt.LongNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.IntNBT;
import net.minecraft.nbt.IntArrayNBT;
import net.minecraft.nbt.FloatNBT;
import net.minecraft.nbt.DoubleNBT;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ByteNBT;
import net.minecraft.nbt.ByteArrayNBT;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;

/** Load real entities, compare complete item data and exercise the legacy attribute engine. */
final class HealthAttributeChecks {
    private interface Check {void run() throws Exception;}
    private static ServerWorld world;
    private static final List<String> results=new ArrayList<>();
    private static int failures;
    private static final UUID MOD=UUID.fromString("00000000-0000-4000-8000-000000000141");
    static void run(MinecraftServer server,ServerWorld target,File folder) throws Exception {
        world=target;results.clear();failures=0;
        INBT[] wrong={StringNBT.valueOf("opaque"),new CompoundNBT(),new ListNBT(),new ByteArrayNBT(new byte[]{1}),new IntArrayNBT(new int[]{1})};
        for(String key:new String[]{"Health","AbsorptionAmount"}) {
            for(int i=0;i<wrong.length;i++){final INBT raw=wrong[i];check(key+"_wrong_type_"+i,()->badScalar(key,raw));}
            for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1,Double.MAX_VALUE,1e-300})check(key+"_invalid_value_"+value,()->badScalar(key,DoubleNBT.valueOf(value)));
        }
        check("health_above_effective_max_retained",()->badScalar("Health",FloatNBT.valueOf(21)));
        for(int type=0;type<6;type++){final int t=type;check("valid_numeric_scalar_type_"+type,()->numeric(t));}
        check("valid_zero_health_keeps_death_semantics",HealthAttributeChecks::zero);
        check("missing_health_defaults_to_loaded_max",HealthAttributeChecks::missingHealth);
        check("configured_health_and_follow_range_defaults",HealthAttributeChecks::configured);
        check("configured_health_above_engine_cap_keeps_existing_clamp",HealthAttributeChecks::configuredCap);
        check("previous_configured_health_survives_later_config_change",HealthAttributeChecks::configuredHistory);
        for(int i=0;i<wrong.length;i++){final INBT raw=wrong[i] instanceof ListNBT?ByteNBT.valueOf((byte)1):wrong[i];check("Attributes_wrong_type_"+i,()->badList(raw));}
        check("empty_attribute_list_keeps_constructor_defaults",()->{CompoundNBT n=base();n.put("Attributes",new ListNBT());EntityLootableBody b=read(n);value(b.getMaxHealth(),20,"Empty list changed max");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid empty list quarantined");stable(b);});
        check("Attributes_wrong_element_type",()->{ListNBT raw=new ListNBT();raw.add(StringNBT.valueOf("opaque"));badList(raw);});
        check("attribute_missing_name",()->badAttribute("Name",null));
        check("attribute_wrong_name_type",()->badAttribute("Name",IntNBT.valueOf(1)));
        check("attribute_unknown_name_retained",()->badAttribute("Name",StringNBT.valueOf("absentfixture:attribute")));
        check("attribute_missing_base",()->badAttribute("Base",null));
        for(int i=0;i<wrong.length;i++){final INBT raw=wrong[i];check("Base_wrong_type_"+i,()->badAttribute("Base",raw));}
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1,0,Double.MAX_VALUE})check("Base_invalid_value_"+value,()->badAttribute("Base",DoubleNBT.valueOf(value)));
        check("invalid_base_does_not_hide_later_valid_attribute",HealthAttributeChecks::validTail);
        check("duplicate_attribute_keeps_first_valid_record",HealthAttributeChecks::duplicateAttribute);
        check("unknown_attribute_payload_preserved_with_valid_fields",HealthAttributeChecks::unknownAttributeData);
        check("Modifiers_wrong_list_type_keeps_base",()->badModifierList(StringNBT.valueOf("opaque")));
        check("Modifiers_wrong_element_type_keeps_base",()->{ListNBT l=new ListNBT();l.add(IntNBT.valueOf(3));badModifierList(l);});
        for(String field:new String[]{"Name","Amount","Operation","UUIDMost","UUIDLeast"}) {
            check("modifier_missing_"+field,()->badModifier(field,null));
            check("modifier_wrong_type_"+field,()->badModifier(field,field.equals("Name")?IntNBT.valueOf(1):StringNBT.valueOf("opaque")));
        }
        check("modifier_blank_name",()->badModifier("Name",StringNBT.valueOf("")));
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})check("modifier_nonfinite_amount_"+value,()->badModifier("Amount",DoubleNBT.valueOf(value)));
        for(INBT value:new INBT[]{IntNBT.valueOf(-1),IntNBT.valueOf(3),LongNBT.valueOf(4294967296L),FloatNBT.valueOf(1F)})check("modifier_invalid_operation_"+value,()->badModifier("Operation",value));
        check("modifier_fractional_UUID_not_coerced",()->badModifier("UUIDMost",DoubleNBT.valueOf(1.5)));
        check("duplicate_modifier_UUID_keeps_first",HealthAttributeChecks::duplicateModifier);
        check("bad_modifier_keeps_valid_base_and_tail",HealthAttributeChecks::modifierTail);
        check("unknown_modifier_payload_retained",HealthAttributeChecks::unknownModifierData);
        check("finite_modifier_overflow_not_activated",HealthAttributeChecks::overflow);
        check("valid_signed_modifiers_all_three_operations",HealthAttributeChecks::validModifiers);
        check("valid_zero_UUID_and_integral_modifier_numbers",HealthAttributeChecks::zeroUuid);
        check("valid_base_numeric_types_and_fractional_health",HealthAttributeChecks::validBases);
        check("repeated_load_restores_constructor_defaults",HealthAttributeChecks::repeated);
        check("repeated_load_clears_saved_modifiers",HealthAttributeChecks::repeatedModifiers);
        check("repaired_health_does_not_keep_previous_removal_countdown",HealthAttributeChecks::pendingRemoval);
        check("invalid_health_attribute_and_unresolved_inventory_reload",HealthAttributeChecks::inventory);
        check("invalid_metadata_alone_does_not_count_as_inventory",HealthAttributeChecks::empty);
        check("healthy_save_layout_unchanged",HealthAttributeChecks::healthy);
        try(PrintWriter out=new PrintWriter(new File(folder,"health-attribute-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        RuntimeChecks.require(failures==0,"Health/attribute regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_HEALTH_ATTRIBUTE_PASS checks="+results.size());
    }
    private static void check(String name,Check test){
        float hp=LootableBodies.corpseHP;Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        try{test.run();results.add("PASS "+name);System.out.println("HEALTH_ATTRIBUTE_PASS "+name);}
        catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("HEALTH_ATTRIBUTE_FAIL "+name+": "+e);e.printStackTrace();}
        finally{LootableBodies.corpseHP=hp;for(Entity e:new ArrayList<Entity>(FixtureApi.entities(world)))if(!before.contains(e))world.removeEntity(e);}
    }
    static CompoundNBT base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setUserName("HealthFixture");b.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(b);return NameLifetimeChecks.saved(b);}
    static EntityLootableBody read(CompoundNBT n){CompoundNBT before=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.read(n);RuntimeChecks.require(NameLifetimeChecks.same(before,n),"Input NBT mutated");return b;}
    static CompoundNBT attribute(String name,double base){CompoundNBT n=new CompoundNBT();n.putString("Name",name);n.putDouble("Base",base);return n;}
    static CompoundNBT modifier(double amount,int operation){CompoundNBT n=new CompoundNBT();n.putString("Name","Fixture modifier");n.putDouble("Amount",amount);n.putInt("Operation",operation);n.putUniqueId("UUID",MOD);return n;}
    static CompoundNBT with(CompoundNBT a){CompoundNBT n=base();n.put("Attributes",NbtRecoveryChecks.list(a));return n;}
    private static void value(double a,double b,String why){RuntimeChecks.require(Double.isFinite(a) && Math.abs(a-b)<1e-6,why+" expected="+b+" actual="+a);}
    private static void inventoryKept(EntityLootableBody b){RuntimeChecks.require(RuntimeChecks.sameStack(b.getStackInSlot(6),NbtRecoveryChecks.item(120,17)),"Complete recoverable inventory changed");}
    private static void stable(EntityLootableBody b){CompoundNBT a=NameLifetimeChecks.saved(b),z=NameLifetimeChecks.saved(read(a));for(String key:new String[]{"Health","AbsorptionAmount","Attributes","Equipment","Aux",NameLifetimeChecks.RECOVERY,NbtRecoveryChecks.RECOVERY})RuntimeChecks.require(NameLifetimeChecks.same(a.get(key),z.get(key)),"Round trip changed "+key);inventoryKept(b);}
    private static void retained(EntityLootableBody b,String key,INBT raw){NameLifetimeChecks.retained(b,key,raw);}
    private static void badScalar(String key,INBT raw){CompoundNBT n=base();n.put(key,raw.copy());EntityLootableBody b=read(n);value(key.equals("Health")?b.getHealth():b.getAbsorptionAmount(),key.equals("Health")?20:0,"Bad scalar did not use safe default");RuntimeChecks.require(b.isAlive(),"Malformed scalar made corpse inaccessible");retained(b,key,raw);stable(b);}
    private static void numeric(int type){INBT[] values={ByteNBT.valueOf((byte)1),ShortNBT.valueOf((short)1),IntNBT.valueOf(1),LongNBT.valueOf(1),FloatNBT.valueOf(1),DoubleNBT.valueOf(1)};CompoundNBT n=base();n.put("Health",values[type]);n.put("AbsorptionAmount",values[type].copy());EntityLootableBody b=read(n);value(b.getHealth(),1,"Valid Health changed");value(b.getAbsorptionAmount(),1,"Valid absorption changed");stable(b);}
    private static void zero(){CompoundNBT n=base();n.putFloat("Health",0);EntityLootableBody b=read(n);value(b.getHealth(),0,"Legitimate zero revived");RuntimeChecks.require(!b.isAlive(),"Zero health no longer starts death handling");inventoryKept(b);}
    private static void missingHealth(){CompoundNBT n=with(attribute("generic.maxHealth",30));n.remove("Health");EntityLootableBody b=read(n);value(b.getHealth(),30,"Missing Health did not use loaded max");stable(b);}
    private static void configured(){LootableBodies.corpseHP=37;CompoundNBT n=base();n.remove("Attributes");n.remove("Health");EntityLootableBody b=read(n);value(b.getMaxHealth(),37,"Config health changed");value(b.getAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getBaseValue(),16,"EntityLiving follow-range default changed");stable(b);}
    private static void configuredCap(){LootableBodies.corpseHP=Short.MAX_VALUE;EntityLootableBody original=new EntityLootableBody(world);CompoundNBT n=NameLifetimeChecks.saved(original);EntityLootableBody b=read(n);value(b.getMaxHealth(),original.getMaxHealth(),"Existing configured health clamp changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy configured base quarantined");}
    private static void configuredHistory(){LootableBodies.corpseHP=Short.MAX_VALUE;EntityLootableBody old=new EntityLootableBody(world);old.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));CompoundNBT n=NameLifetimeChecks.saved(old);LootableBodies.corpseHP=20;EntityLootableBody b=read(n);value(b.getMaxHealth(),old.getMaxHealth(),"Valid old configured base was replaced by today's default");value(b.getHealth(),old.getHealth(),"Valid old configured health changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy historical config quarantined");stable(b);}
    private static void badList(INBT raw){CompoundNBT n=base();n.put("Attributes",raw.copy());EntityLootableBody b=read(n);value(b.getMaxHealth(),20,"Bad list changed max health");retained(b,"Attributes",raw);stable(b);}
    private static void badAttribute(String field,INBT raw){CompoundNBT a=attribute("generic.maxHealth",30);if(raw==null)a.remove(field);else a.put(field,raw.copy());EntityLootableBody b=read(with(a));value(b.getMaxHealth(),20,"Bad attribute changed default");retained(b,"Attribute",a);stable(b);}
    private static void validTail(){CompoundNBT a=attribute("generic.maxHealth",Double.NaN),n=base();n.put("Attributes",NbtRecoveryChecks.list(a,attribute("generic.maxHealth",30),attribute("generic.followRange",24)));EntityLootableBody b=read(n);value(b.getMaxHealth(),30,"Invalid first entry hid valid max health");value(b.getAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getBaseValue(),24,"Valid tail lost");retained(b,"Attribute",a);stable(b);}
    private static void duplicateAttribute(){CompoundNBT duplicate=attribute("generic.maxHealth",40),n=base();n.put("Attributes",NbtRecoveryChecks.list(attribute("generic.maxHealth",30),duplicate));EntityLootableBody b=read(n);value(b.getMaxHealth(),30,"Duplicate overwrote first max health");retained(b,"Attribute",duplicate);stable(b);}
    private static void unknownAttributeData(){CompoundNBT a=attribute("generic.maxHealth",30);a.putString("Opaque","retain");EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Known fields lost with unknown sibling");retained(b,"Attribute",a);stable(b);}
    private static void badModifierList(INBT raw){CompoundNBT a=attribute("generic.maxHealth",30);a.put("Modifiers",raw.copy());EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Invalid modifiers lost valid base");retained(b,"Attribute",a);stable(b);}
    private static void badModifier(String field,INBT raw){CompoundNBT m=modifier(5,0);if(raw==null)m.remove(field);else m.put(field,raw.copy());CompoundNBT a=attribute("generic.maxHealth",30);a.put("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Malformed modifier activated");retained(b,"Attribute",a);stable(b);}
    private static void duplicateModifier(){CompoundNBT a=attribute("generic.maxHealth",20);a.put("Modifiers",NbtRecoveryChecks.list(modifier(2,0),modifier(7,0)));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),22,"Duplicate modifier replaced first");retained(b,"Attribute",a);stable(b);}
    private static void modifierTail(){CompoundNBT bad=modifier(Double.NaN,0),good=modifier(5,0),a=attribute("generic.maxHealth",30);good.putUniqueId("UUID",new UUID(0,142));a.put("Modifiers",NbtRecoveryChecks.list(bad,good));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Invalid modifier hid valid tail or base");retained(b,"Attribute",a);stable(b);}
    private static void unknownModifierData(){CompoundNBT m=modifier(5,0),a=attribute("generic.maxHealth",30);m.putString("Opaque","retain");a.put("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Known modifier fields lost");retained(b,"Attribute",a);stable(b);}
    private static void overflow(){CompoundNBT a=attribute("generic.maxHealth",30);a.put("Modifiers",NbtRecoveryChecks.list(modifier(Double.MAX_VALUE,2)));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Overflowing modifier activated");retained(b,"Attribute",a);stable(b);}
    private static void validModifiers(){CompoundNBT a=attribute("generic.maxHealth",30);CompoundNBT m0=modifier(-2,0),m1=modifier(.5,1),m2=modifier(.25,2);m1.putUniqueId("UUID",new UUID(0,142));m2.putUniqueId("UUID",new UUID(0,143));a.put("Modifiers",NbtRecoveryChecks.list(m0,m1,m2));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),52.5,"Valid signed/three-operation modifiers changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid modifiers quarantined");stable(b);}
    private static void zeroUuid(){CompoundNBT a=attribute("generic.maxHealth",30),m=modifier(5,0);m.putLong("UUIDMost",0);m.putLong("UUIDLeast",0);m.putByte("Operation",(byte)0);m.putInt("Amount",5);a.put("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Zero UUID or lossless numbers rejected");stable(b);}
    private static void validBases(){for(INBT raw:new INBT[]{ByteNBT.valueOf((byte)30),ShortNBT.valueOf((short)30),IntNBT.valueOf(30),LongNBT.valueOf(30),FloatNBT.valueOf(30.5F),DoubleNBT.valueOf(30.5)}){CompoundNBT a=attribute("generic.maxHealth",30);a.put("Base",raw);CompoundNBT n=with(a);n.putDouble("Health",12.5);EntityLootableBody b=read(n);value(b.getMaxHealth(),((NumberNBT)raw).getDouble(),"Valid base changed");value(b.getHealth(),12.5,"Fractional Health changed");stable(b);}}
    private static void repeated(){EntityLootableBody b=read(with(attribute("generic.maxHealth",30)));CompoundNBT n=base();n.remove("Attributes");n.remove("Health");n.remove("AbsorptionAmount");b.setAbsorptionAmount(5);b.read(n);value(b.getMaxHealth(),20,"Missing Attributes retained previous max");value(b.getHealth(),20,"Missing Health retained previous value");value(b.getAbsorptionAmount(),0,"Missing absorption retained previous value");stable(b);}
    private static void repeatedModifiers(){CompoundNBT a=attribute("generic.maxHealth",30);a.put("Modifiers",NbtRecoveryChecks.list(modifier(5,0)));EntityLootableBody b=read(with(a));b.read(with(attribute("generic.maxHealth",20)));value(b.getMaxHealth(),20,"Repeated read kept obsolete modifier");stable(b);}
    private static void pendingRemoval(){CompoundNBT n=base();n.putFloat("Health",0);EntityLootableBody b=read(n);b.baseTick();n=base();n.putDouble("Health",Double.NaN);b.read(n);b.baseTick();RuntimeChecks.require(b.isAlive(),"Repaired owner still dead");inventoryKept(b);stable(b);n=base();n.putFloat("Health",3.5F);b.removed=true;b.read(n);RuntimeChecks.require(b.isAlive(),"Reused entity kept stale dead flag");value(b.getHealth(),3.5,"Reset healed valid wounded corpse");}
    private static void inventory(){CompoundNBT n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.putString("id","absentfixture:missing");n.getList("Equipment",10).add(raw);n.putDouble("Health",Double.NaN);CompoundNBT a=attribute("generic.maxHealth",Double.NaN);n.put("Attributes",NbtRecoveryChecks.list(a,attribute("generic.followRange",24)));EntityLootableBody b=read(n);RuntimeChecks.require(b.isAlive(),"Invalid health made recoverable inventory inaccessible");NbtRecoveryChecks.retained(b,raw);retained(b,"Attribute",a);retained(b,"Health",n.get("Health"));stable(b);stable(read(NameLifetimeChecks.saved(b)));}
    private static void empty(){CompoundNBT n=base();n.put("Equipment",new ListNBT());n.putDouble("Health",Double.NaN);EntityLootableBody b=read(n);RuntimeChecks.require(b.isEmpty(),"Health evidence counted as inventory");retained(b,"Health",n.get("Health"));}
    private static void healthy(){CompoundNBT n=base();EntityLootableBody b=read(n);RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy save gained recovery records");stable(b);}

    static CompoundNBT malformedFields(){
        CompoundNBT n=new CompoundNBT();n.putDouble("Health",Double.NaN);n.putString("AbsorptionAmount","opaque saved absorption");
        CompoundNBT max=attribute("generic.maxHealth",Double.NaN),follow=attribute("generic.followRange",24),unknown=attribute("absentfixture:attribute",7);
        CompoundNBT bad=modifier(Double.NaN,0),good=modifier(2,0);good.putUniqueId("UUID",new UUID(0,144));
        follow.put("Modifiers",NbtRecoveryChecks.list(bad,good));unknown.putString("Opaque","retain original attribute");
        n.put("Attributes",NbtRecoveryChecks.list(max,follow,unknown));return n;
    }
    static void verifyWorldFields(EntityLootableBody b){
        value(b.getHealth(),20,"Disk health unsafe");value(b.getMaxHealth(),20,"Disk max health unsafe");value(b.getAbsorptionAmount(),0,"Disk absorption unsafe");
        value(b.getAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getValue(),26,"Valid attribute/modifier tail lost on disk");
        CompoundNBT raw=malformedFields();retained(b,"Health",raw.get("Health"));retained(b,"AbsorptionAmount",raw.get("AbsorptionAmount"));
        ListNBT entries=raw.getList("Attributes",10);for(int i=0;i<entries.size();i++)retained(b,"Attribute",entries.getCompound(i));
    }
}
