package cyano.lootable.buildtest;

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
import net.minecraft.world.WorldServer;

/** Load real entities, compare complete item data and exercise the legacy attribute engine. */
final class HealthAttributeChecks {
    private interface Check {void run() throws Exception;}
    private static WorldServer world;
    private static final List<String> results=new ArrayList<>();
    private static int failures;
    private static final UUID MOD=UUID.fromString("00000000-0000-4000-8000-000000000141");
    static void run(MinecraftServer server,WorldServer target,File folder) throws Exception {
        world=target;results.clear();failures=0;
        NBTBase[] wrong={new NBTTagString("opaque"),new NBTTagCompound(),new NBTTagList(),new NBTTagByteArray(new byte[]{1}),new NBTTagIntArray(new int[]{1})};
        for(String key:new String[]{"Health","AbsorptionAmount"}) {
            for(int i=0;i<wrong.length;i++){final NBTBase raw=wrong[i];check(key+"_wrong_type_"+i,()->badScalar(key,raw));}
            for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1,Double.MAX_VALUE,1e-300})check(key+"_invalid_value_"+value,()->badScalar(key,new NBTTagDouble(value)));
        }
        check("health_above_effective_max_retained",()->badScalar("Health",new NBTTagFloat(21)));
        for(int type=0;type<6;type++){final int t=type;check("valid_numeric_scalar_type_"+type,()->numeric(t));}
        check("valid_zero_health_keeps_death_semantics",HealthAttributeChecks::zero);
        check("missing_health_defaults_to_loaded_max",HealthAttributeChecks::missingHealth);
        check("configured_health_and_follow_range_defaults",HealthAttributeChecks::configured);
        check("configured_health_above_engine_cap_keeps_existing_clamp",HealthAttributeChecks::configuredCap);
        check("previous_configured_health_survives_later_config_change",HealthAttributeChecks::configuredHistory);
        for(int i=0;i<wrong.length;i++){final NBTBase raw=wrong[i] instanceof NBTTagList?new NBTTagByte((byte)1):wrong[i];check("Attributes_wrong_type_"+i,()->badList(raw));}
        check("empty_attribute_list_keeps_constructor_defaults",()->{NBTTagCompound n=base();n.setTag("Attributes",new NBTTagList());EntityLootableBody b=read(n);value(b.getMaxHealth(),20,"Empty list changed max");RuntimeChecks.require(!NameLifetimeChecks.saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid empty list quarantined");stable(b);});
        check("Attributes_wrong_element_type",()->{NBTTagList raw=new NBTTagList();raw.appendTag(new NBTTagString("opaque"));badList(raw);});
        check("attribute_missing_name",()->badAttribute("Name",null));
        check("attribute_wrong_name_type",()->badAttribute("Name",new NBTTagInt(1)));
        check("attribute_unknown_name_retained",()->badAttribute("Name",new NBTTagString("absentfixture:attribute")));
        check("attribute_missing_base",()->badAttribute("Base",null));
        for(int i=0;i<wrong.length;i++){final NBTBase raw=wrong[i];check("Base_wrong_type_"+i,()->badAttribute("Base",raw));}
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1,0,Double.MAX_VALUE})check("Base_invalid_value_"+value,()->badAttribute("Base",new NBTTagDouble(value)));
        check("invalid_base_does_not_hide_later_valid_attribute",HealthAttributeChecks::validTail);
        check("duplicate_attribute_keeps_first_valid_record",HealthAttributeChecks::duplicateAttribute);
        check("unknown_attribute_payload_preserved_with_valid_fields",HealthAttributeChecks::unknownAttributeData);
        check("Modifiers_wrong_list_type_keeps_base",()->badModifierList(new NBTTagString("opaque")));
        check("Modifiers_wrong_element_type_keeps_base",()->{NBTTagList l=new NBTTagList();l.appendTag(new NBTTagInt(3));badModifierList(l);});
        for(String field:new String[]{"Name","Amount","Operation","UUIDMost","UUIDLeast"}) {
            check("modifier_missing_"+field,()->badModifier(field,null));
            check("modifier_wrong_type_"+field,()->badModifier(field,field.equals("Name")?new NBTTagInt(1):new NBTTagString("opaque")));
        }
        check("modifier_blank_name",()->badModifier("Name",new NBTTagString("")));
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})check("modifier_nonfinite_amount_"+value,()->badModifier("Amount",new NBTTagDouble(value)));
        for(NBTBase value:new NBTBase[]{new NBTTagInt(-1),new NBTTagInt(3),new NBTTagLong(4294967296L),new NBTTagFloat(1F)})check("modifier_invalid_operation_"+value,()->badModifier("Operation",value));
        check("modifier_fractional_UUID_not_coerced",()->badModifier("UUIDMost",new NBTTagDouble(1.5)));
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
        float hp=LootableBodies.corpseHP;Set<Entity> before=new HashSet<>(world.loadedEntityList);
        try{test.run();results.add("PASS "+name);System.out.println("HEALTH_ATTRIBUTE_PASS "+name);}
        catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("HEALTH_ATTRIBUTE_FAIL "+name+": "+e);e.printStackTrace();}
        finally{LootableBodies.corpseHP=hp;for(Entity e:new ArrayList<Entity>(world.loadedEntityList))if(!before.contains(e))world.removeEntityDangerously(e);}
    }
    static NBTTagCompound base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setUserName("HealthFixture");b.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(b);return NameLifetimeChecks.saved(b);}
    static EntityLootableBody read(NBTTagCompound n){NBTTagCompound before=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.readFromNBT(n);RuntimeChecks.require(NameLifetimeChecks.same(before,n),"Input NBT mutated");return b;}
    static NBTTagCompound attribute(String name,double base){NBTTagCompound n=new NBTTagCompound();n.setString("Name",name);n.setDouble("Base",base);return n;}
    static NBTTagCompound modifier(double amount,int operation){NBTTagCompound n=new NBTTagCompound();n.setString("Name","Fixture modifier");n.setDouble("Amount",amount);n.setInteger("Operation",operation);n.setUniqueId("UUID",MOD);return n;}
    static NBTTagCompound with(NBTTagCompound a){NBTTagCompound n=base();n.setTag("Attributes",NbtRecoveryChecks.list(a));return n;}
    private static void value(double a,double b,String why){RuntimeChecks.require(Double.isFinite(a) && Math.abs(a-b)<1e-6,why+" expected="+b+" actual="+a);}
    private static void inventoryKept(EntityLootableBody b){RuntimeChecks.require(RuntimeChecks.sameStack(b.getStackInSlot(6),NbtRecoveryChecks.item(120,17)),"Complete recoverable inventory changed");}
    private static void stable(EntityLootableBody b){NBTTagCompound a=NameLifetimeChecks.saved(b),z=NameLifetimeChecks.saved(read(a));for(String key:new String[]{"Health","AbsorptionAmount","Attributes","Equipment","Aux",NameLifetimeChecks.RECOVERY,NbtRecoveryChecks.RECOVERY})RuntimeChecks.require(NameLifetimeChecks.same(a.getTag(key),z.getTag(key)),"Round trip changed "+key);inventoryKept(b);}
    private static void retained(EntityLootableBody b,String key,NBTBase raw){NameLifetimeChecks.retained(b,key,raw);}
    private static void badScalar(String key,NBTBase raw){NBTTagCompound n=base();n.setTag(key,raw.copy());EntityLootableBody b=read(n);value(key.equals("Health")?b.getHealth():b.getAbsorptionAmount(),key.equals("Health")?20:0,"Bad scalar did not use safe default");RuntimeChecks.require(b.isEntityAlive(),"Malformed scalar made corpse inaccessible");retained(b,key,raw);stable(b);}
    private static void numeric(int type){NBTBase[] values={new NBTTagByte((byte)1),new NBTTagShort((short)1),new NBTTagInt(1),new NBTTagLong(1),new NBTTagFloat(1),new NBTTagDouble(1)};NBTTagCompound n=base();n.setTag("Health",values[type]);n.setTag("AbsorptionAmount",values[type].copy());EntityLootableBody b=read(n);value(b.getHealth(),1,"Valid Health changed");value(b.getAbsorptionAmount(),1,"Valid absorption changed");stable(b);}
    private static void zero(){NBTTagCompound n=base();n.setFloat("Health",0);EntityLootableBody b=read(n);value(b.getHealth(),0,"Legitimate zero revived");RuntimeChecks.require(!b.isEntityAlive(),"Zero health no longer starts death handling");inventoryKept(b);}
    private static void missingHealth(){NBTTagCompound n=with(attribute("generic.maxHealth",30));n.removeTag("Health");EntityLootableBody b=read(n);value(b.getHealth(),30,"Missing Health did not use loaded max");stable(b);}
    private static void configured(){LootableBodies.corpseHP=37;NBTTagCompound n=base();n.removeTag("Attributes");n.removeTag("Health");EntityLootableBody b=read(n);value(b.getMaxHealth(),37,"Config health changed");value(b.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getBaseValue(),16,"EntityLiving follow-range default changed");stable(b);}
    private static void configuredCap(){LootableBodies.corpseHP=Short.MAX_VALUE;EntityLootableBody original=new EntityLootableBody(world);NBTTagCompound n=NameLifetimeChecks.saved(original);EntityLootableBody b=read(n);value(b.getMaxHealth(),original.getMaxHealth(),"Existing configured health clamp changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Healthy configured base quarantined");}
    private static void configuredHistory(){LootableBodies.corpseHP=Short.MAX_VALUE;EntityLootableBody old=new EntityLootableBody(world);old.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));NBTTagCompound n=NameLifetimeChecks.saved(old);LootableBodies.corpseHP=20;EntityLootableBody b=read(n);value(b.getMaxHealth(),old.getMaxHealth(),"Valid old configured base was replaced by today's default");value(b.getHealth(),old.getHealth(),"Valid old configured health changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Healthy historical config quarantined");stable(b);}
    private static void badList(NBTBase raw){NBTTagCompound n=base();n.setTag("Attributes",raw.copy());EntityLootableBody b=read(n);value(b.getMaxHealth(),20,"Bad list changed max health");retained(b,"Attributes",raw);stable(b);}
    private static void badAttribute(String field,NBTBase raw){NBTTagCompound a=attribute("generic.maxHealth",30);if(raw==null)a.removeTag(field);else a.setTag(field,raw.copy());EntityLootableBody b=read(with(a));value(b.getMaxHealth(),20,"Bad attribute changed default");retained(b,"Attribute",a);stable(b);}
    private static void validTail(){NBTTagCompound a=attribute("generic.maxHealth",Double.NaN),n=base();n.setTag("Attributes",NbtRecoveryChecks.list(a,attribute("generic.maxHealth",30),attribute("generic.followRange",24)));EntityLootableBody b=read(n);value(b.getMaxHealth(),30,"Invalid first entry hid valid max health");value(b.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getBaseValue(),24,"Valid tail lost");retained(b,"Attribute",a);stable(b);}
    private static void duplicateAttribute(){NBTTagCompound duplicate=attribute("generic.maxHealth",40),n=base();n.setTag("Attributes",NbtRecoveryChecks.list(attribute("generic.maxHealth",30),duplicate));EntityLootableBody b=read(n);value(b.getMaxHealth(),30,"Duplicate overwrote first max health");retained(b,"Attribute",duplicate);stable(b);}
    private static void unknownAttributeData(){NBTTagCompound a=attribute("generic.maxHealth",30);a.setString("Opaque","retain");EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Known fields lost with unknown sibling");retained(b,"Attribute",a);stable(b);}
    private static void badModifierList(NBTBase raw){NBTTagCompound a=attribute("generic.maxHealth",30);a.setTag("Modifiers",raw.copy());EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Invalid modifiers lost valid base");retained(b,"Attribute",a);stable(b);}
    private static void badModifier(String field,NBTBase raw){NBTTagCompound m=modifier(5,0);if(raw==null)m.removeTag(field);else m.setTag(field,raw.copy());NBTTagCompound a=attribute("generic.maxHealth",30);a.setTag("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Malformed modifier activated");retained(b,"Attribute",a);stable(b);}
    private static void duplicateModifier(){NBTTagCompound a=attribute("generic.maxHealth",20);a.setTag("Modifiers",NbtRecoveryChecks.list(modifier(2,0),modifier(7,0)));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),22,"Duplicate modifier replaced first");retained(b,"Attribute",a);stable(b);}
    private static void modifierTail(){NBTTagCompound bad=modifier(Double.NaN,0),good=modifier(5,0),a=attribute("generic.maxHealth",30);good.setUniqueId("UUID",new UUID(0,142));a.setTag("Modifiers",NbtRecoveryChecks.list(bad,good));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Invalid modifier hid valid tail or base");retained(b,"Attribute",a);stable(b);}
    private static void unknownModifierData(){NBTTagCompound m=modifier(5,0),a=attribute("generic.maxHealth",30);m.setString("Opaque","retain");a.setTag("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Known modifier fields lost");retained(b,"Attribute",a);stable(b);}
    private static void overflow(){NBTTagCompound a=attribute("generic.maxHealth",30);a.setTag("Modifiers",NbtRecoveryChecks.list(modifier(Double.MAX_VALUE,2)));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Overflowing modifier activated");retained(b,"Attribute",a);stable(b);}
    private static void validModifiers(){NBTTagCompound a=attribute("generic.maxHealth",30);NBTTagCompound m0=modifier(-2,0),m1=modifier(.5,1),m2=modifier(.25,2);m1.setUniqueId("UUID",new UUID(0,142));m2.setUniqueId("UUID",new UUID(0,143));a.setTag("Modifiers",NbtRecoveryChecks.list(m0,m1,m2));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),52.5,"Valid signed/three-operation modifiers changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid modifiers quarantined");stable(b);}
    private static void zeroUuid(){NBTTagCompound a=attribute("generic.maxHealth",30),m=modifier(5,0);m.setLong("UUIDMost",0);m.setLong("UUIDLeast",0);m.setByte("Operation",(byte)0);m.setInteger("Amount",5);a.setTag("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Zero UUID or lossless numbers rejected");stable(b);}
    private static void validBases(){for(NBTBase raw:new NBTBase[]{new NBTTagByte((byte)30),new NBTTagShort((short)30),new NBTTagInt(30),new NBTTagLong(30),new NBTTagFloat(30.5F),new NBTTagDouble(30.5)}){NBTTagCompound a=attribute("generic.maxHealth",30);a.setTag("Base",raw);NBTTagCompound n=with(a);n.setDouble("Health",12.5);EntityLootableBody b=read(n);value(b.getMaxHealth(),((NBTPrimitive)raw).getDouble(),"Valid base changed");value(b.getHealth(),12.5,"Fractional Health changed");stable(b);}}
    private static void repeated(){EntityLootableBody b=read(with(attribute("generic.maxHealth",30)));NBTTagCompound n=base();n.removeTag("Attributes");n.removeTag("Health");n.removeTag("AbsorptionAmount");b.setAbsorptionAmount(5);b.readFromNBT(n);value(b.getMaxHealth(),20,"Missing Attributes retained previous max");value(b.getHealth(),20,"Missing Health retained previous value");value(b.getAbsorptionAmount(),0,"Missing absorption retained previous value");stable(b);}
    private static void repeatedModifiers(){NBTTagCompound a=attribute("generic.maxHealth",30);a.setTag("Modifiers",NbtRecoveryChecks.list(modifier(5,0)));EntityLootableBody b=read(with(a));b.readFromNBT(with(attribute("generic.maxHealth",20)));value(b.getMaxHealth(),20,"Repeated read kept obsolete modifier");stable(b);}
    private static void pendingRemoval(){NBTTagCompound n=base();n.setFloat("Health",0);EntityLootableBody b=read(n);b.onEntityUpdate();n=base();n.setDouble("Health",Double.NaN);b.readFromNBT(n);b.onEntityUpdate();RuntimeChecks.require(b.isEntityAlive(),"Repaired owner still dead");inventoryKept(b);stable(b);n=base();n.setFloat("Health",3.5F);b.isDead=true;b.readFromNBT(n);RuntimeChecks.require(b.isEntityAlive(),"Reused entity kept stale dead flag");value(b.getHealth(),3.5,"Reset healed valid wounded corpse");}
    private static void inventory(){NBTTagCompound n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.setString("id","absentfixture:missing");n.getTagList("Equipment",10).appendTag(raw);n.setDouble("Health",Double.NaN);NBTTagCompound a=attribute("generic.maxHealth",Double.NaN);n.setTag("Attributes",NbtRecoveryChecks.list(a,attribute("generic.followRange",24)));EntityLootableBody b=read(n);RuntimeChecks.require(b.isEntityAlive(),"Invalid health made recoverable inventory inaccessible");NbtRecoveryChecks.retained(b,raw);retained(b,"Attribute",a);retained(b,"Health",n.getTag("Health"));stable(b);stable(read(NameLifetimeChecks.saved(b)));}
    private static void empty(){NBTTagCompound n=base();n.setTag("Equipment",new NBTTagList());n.setDouble("Health",Double.NaN);EntityLootableBody b=read(n);RuntimeChecks.require(b.isEmpty(),"Health evidence counted as inventory");retained(b,"Health",n.getTag("Health"));}
    private static void healthy(){NBTTagCompound n=base();EntityLootableBody b=read(n);RuntimeChecks.require(!NameLifetimeChecks.saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Healthy save gained recovery records");stable(b);}

    static NBTTagCompound malformedFields(){
        NBTTagCompound n=new NBTTagCompound();n.setDouble("Health",Double.NaN);n.setString("AbsorptionAmount","opaque saved absorption");
        NBTTagCompound max=attribute("generic.maxHealth",Double.NaN),follow=attribute("generic.followRange",24),unknown=attribute("absentfixture:attribute",7);
        NBTTagCompound bad=modifier(Double.NaN,0),good=modifier(2,0);good.setUniqueId("UUID",new UUID(0,144));
        follow.setTag("Modifiers",NbtRecoveryChecks.list(bad,good));unknown.setString("Opaque","retain original attribute");
        n.setTag("Attributes",NbtRecoveryChecks.list(max,follow,unknown));return n;
    }
    static void verifyWorldFields(EntityLootableBody b){
        value(b.getHealth(),20,"Disk health unsafe");value(b.getMaxHealth(),20,"Disk max health unsafe");value(b.getAbsorptionAmount(),0,"Disk absorption unsafe");
        value(b.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getAttributeValue(),26,"Valid attribute/modifier tail lost on disk");
        NBTTagCompound raw=malformedFields();retained(b,"Health",raw.getTag("Health"));retained(b,"AbsorptionAmount",raw.getTag("AbsorptionAmount"));
        NBTTagList entries=raw.getTagList("Attributes",10);for(int i=0;i<entries.tagCount();i++)retained(b,"Attribute",entries.getCompoundTagAt(i));
    }
}
