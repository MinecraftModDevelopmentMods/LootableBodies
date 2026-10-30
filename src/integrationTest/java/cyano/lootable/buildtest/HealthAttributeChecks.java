package cyano.lootable.buildtest;
import net.minecraft.world.entity.ai.attributes.Attribute;
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

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Load real entities, compare complete item data and exercise the legacy attribute engine. */
final class HealthAttributeChecks {
    private interface Check {void run() throws Exception;}
    private static ServerLevel world;
    private static final List<String> results=new ArrayList<>();
    private static int failures;
    private static final UUID MOD=UUID.fromString("00000000-0000-4000-8000-000000000141");
    static void run(MinecraftServer server,ServerLevel target,File folder) throws Exception {
        world=target;results.clear();failures=0;
        Tag[] wrong={StringTag.valueOf("opaque"),new CompoundTag(),new ListTag(),new ByteArrayTag(new byte[]{1}),new IntArrayTag(new int[]{1})};
        for(String key:new String[]{"Health","AbsorptionAmount"}) {
            for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i];check(key+"_wrong_type_"+i,()->badScalar(key,raw));}
            for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1,Double.MAX_VALUE,1e-300})check(key+"_invalid_value_"+value,()->badScalar(key,DoubleTag.valueOf(value)));
        }
        check("health_above_effective_max_retained",()->badScalar("Health",FloatTag.valueOf(21)));
        for(int type=0;type<6;type++){final int t=type;check("valid_numeric_scalar_type_"+type,()->numeric(t));}
        check("valid_zero_health_keeps_death_semantics",HealthAttributeChecks::zero);
        check("missing_health_defaults_to_loaded_max",HealthAttributeChecks::missingHealth);
        check("configured_health_and_follow_range_defaults",HealthAttributeChecks::configured);
        check("configured_health_above_engine_cap_keeps_existing_clamp",HealthAttributeChecks::configuredCap);
        check("previous_configured_health_survives_later_config_change",HealthAttributeChecks::configuredHistory);
        for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i] instanceof ListTag?ByteTag.valueOf((byte)1):wrong[i];check("Attributes_wrong_type_"+i,()->badList(raw));}
        check("empty_attribute_list_keeps_constructor_defaults",()->{CompoundTag n=base();n.put("Attributes",new ListTag());EntityLootableBody b=read(n);value(b.getMaxHealth(),20,"Empty list changed max");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid empty list quarantined");stable(b);});
        check("Attributes_wrong_element_type",()->{ListTag raw=new ListTag();raw.add(StringTag.valueOf("opaque"));badList(raw);});
        check("attribute_missing_name",()->badAttribute("Name",null));
        check("attribute_wrong_name_type",()->badAttribute("Name",IntTag.valueOf(1)));
        check("attribute_unknown_name_retained",()->badAttribute("Name",StringTag.valueOf("absentfixture:attribute")));
        check("attribute_missing_base",()->badAttribute("Base",null));
        for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i];check("Base_wrong_type_"+i,()->badAttribute("Base",raw));}
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1,0,Double.MAX_VALUE})check("Base_invalid_value_"+value,()->badAttribute("Base",DoubleTag.valueOf(value)));
        check("invalid_base_does_not_hide_later_valid_attribute",HealthAttributeChecks::validTail);
        check("duplicate_attribute_keeps_first_valid_record",HealthAttributeChecks::duplicateAttribute);
        check("unknown_attribute_payload_preserved_with_valid_fields",HealthAttributeChecks::unknownAttributeData);
        check("Modifiers_wrong_list_type_keeps_base",()->badModifierList(StringTag.valueOf("opaque")));
        check("Modifiers_wrong_element_type_keeps_base",()->{ListTag l=new ListTag();l.add(IntTag.valueOf(3));badModifierList(l);});
        for(String field:new String[]{"Name","Amount","Operation","UUIDMost","UUIDLeast"}) {
            check("modifier_missing_"+field,()->badModifier(field,null));
            check("modifier_wrong_type_"+field,()->badModifier(field,field.equals("Name")?IntTag.valueOf(1):StringTag.valueOf("opaque")));
        }
        check("modifier_blank_name",()->badModifier("Name",StringTag.valueOf("")));
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})check("modifier_nonfinite_amount_"+value,()->badModifier("Amount",DoubleTag.valueOf(value)));
        for(Tag value:new Tag[]{IntTag.valueOf(-1),IntTag.valueOf(3),LongTag.valueOf(4294967296L),FloatTag.valueOf(1F)})check("modifier_invalid_operation_"+value,()->badModifier("Operation",value));
        check("modifier_fractional_UUID_not_coerced",()->badModifier("UUIDMost",DoubleTag.valueOf(1.5)));
        check("duplicate_modifier_UUID_keeps_first",HealthAttributeChecks::duplicateModifier);
        check("bad_modifier_keeps_valid_base_and_tail",HealthAttributeChecks::modifierTail);
        check("unknown_modifier_payload_retained",HealthAttributeChecks::unknownModifierData);
        check("finite_modifier_overflow_not_activated",HealthAttributeChecks::overflow);
        check("valid_signed_modifiers_all_three_operations",HealthAttributeChecks::validModifiers);
        check("valid_zero_UUID_and_integral_modifier_numbers",HealthAttributeChecks::zeroUuid);
        check("native116_modifier_uuid_arrays_survive_reload",()->nativeUuid(false,false));
        check("native116_conflicting_modifier_uuid_is_retained",()->nativeUuid(true,false));
        check("native116_malformed_modifier_uuid_is_retained",()->nativeUuid(false,true));
        check("legacy115_forge_attribute_names_keep_values_and_wounded_health",HealthAttributeChecks::legacyForgeAttributes);
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
    static CompoundTag base(){EntityLootableBody b=new EntityLootableBody(world);b.setPos(160.5,80,160.5);b.setNoGravity(true);b.setUserName("HealthFixture");b.setItem(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(b);return NameLifetimeChecks.saved(b);}
    static EntityLootableBody read(CompoundTag n){CompoundTag before=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.load(n);RuntimeChecks.require(NameLifetimeChecks.same(before,n),"Input NBT mutated");return b;}
    static CompoundTag attribute(String name,double base){CompoundTag n=new CompoundTag();n.putString("Name",name);n.putDouble("Base",base);return n;}
    private static void legacyUuid(CompoundTag n,UUID id){n.remove("UUID");n.putLong("UUIDMost",id.getMostSignificantBits());n.putLong("UUIDLeast",id.getLeastSignificantBits());}
    static CompoundTag modifier(double amount,int operation){CompoundTag n=new CompoundTag();n.putString("Name","Fixture modifier");n.putDouble("Amount",amount);n.putInt("Operation",operation);legacyUuid(n,MOD);return n;}
    static CompoundTag with(CompoundTag a){CompoundTag n=base();n.put("Attributes",NbtRecoveryChecks.list(a));return n;}
    private static void value(double a,double b,String why){RuntimeChecks.require(Double.isFinite(a) && Math.abs(a-b)<1e-6,why+" expected="+b+" actual="+a);}
    private static void inventoryKept(EntityLootableBody b){RuntimeChecks.require(RuntimeChecks.sameStack(b.getItem(6),NbtRecoveryChecks.item(120,17)),"Complete recoverable inventory changed");}
    private static void stable(EntityLootableBody b){CompoundTag a=NameLifetimeChecks.saved(b),z=NameLifetimeChecks.saved(read(a));for(String key:new String[]{"Health","AbsorptionAmount","Attributes","Equipment","Aux",NameLifetimeChecks.RECOVERY,NbtRecoveryChecks.RECOVERY})RuntimeChecks.require(NameLifetimeChecks.same(a.get(key),z.get(key)),"Round trip changed "+key);inventoryKept(b);}
    private static void retained(EntityLootableBody b,String key,Tag raw){NameLifetimeChecks.retained(b,key,raw);}
    private static void badScalar(String key,Tag raw){CompoundTag n=base();n.put(key,raw.copy());EntityLootableBody b=read(n);value(key.equals("Health")?b.getHealth():b.getAbsorptionAmount(),key.equals("Health")?20:0,"Bad scalar did not use safe default");RuntimeChecks.require(b.isAlive(),"Malformed scalar made corpse inaccessible");retained(b,key,raw);stable(b);}
    private static void numeric(int type){Tag[] values={ByteTag.valueOf((byte)1),ShortTag.valueOf((short)1),IntTag.valueOf(1),LongTag.valueOf(1),FloatTag.valueOf(1),DoubleTag.valueOf(1)};CompoundTag n=base();n.put("Health",values[type]);n.put("AbsorptionAmount",values[type].copy());EntityLootableBody b=read(n);value(b.getHealth(),1,"Valid Health changed");value(b.getAbsorptionAmount(),1,"Valid absorption changed");stable(b);}
    private static void zero(){CompoundTag n=base();n.putFloat("Health",0);EntityLootableBody b=read(n);value(b.getHealth(),0,"Legitimate zero revived");RuntimeChecks.require(!b.isAlive(),"Zero health no longer starts death handling");inventoryKept(b);}
    private static void missingHealth(){CompoundTag n=with(attribute("generic.maxHealth",30));n.remove("Health");EntityLootableBody b=read(n);value(b.getHealth(),30,"Missing Health did not use loaded max");stable(b);}
    private static void configured(){LootableBodies.corpseHP=37;CompoundTag n=base();n.remove("Attributes");n.remove("Health");EntityLootableBody b=read(n);value(b.getMaxHealth(),37,"Config health changed");value(b.getAttribute(Attributes.FOLLOW_RANGE).getBaseValue(),16,"EntityLiving follow-range default changed");stable(b);}
    private static void configuredCap(){LootableBodies.corpseHP=Short.MAX_VALUE;EntityLootableBody original=new EntityLootableBody(world);CompoundTag n=NameLifetimeChecks.saved(original);EntityLootableBody b=read(n);value(b.getMaxHealth(),original.getMaxHealth(),"Existing configured health clamp changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy configured base quarantined");}
    private static void configuredHistory(){LootableBodies.corpseHP=Short.MAX_VALUE;EntityLootableBody old=new EntityLootableBody(world);old.setItem(6,NbtRecoveryChecks.item(120,17));CompoundTag n=NameLifetimeChecks.saved(old);LootableBodies.corpseHP=20;EntityLootableBody b=read(n);value(b.getMaxHealth(),old.getMaxHealth(),"Valid old configured base was replaced by today's default");value(b.getHealth(),old.getHealth(),"Valid old configured health changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy historical config quarantined");stable(b);}
    private static void badList(Tag raw){CompoundTag n=base();n.put("Attributes",raw.copy());EntityLootableBody b=read(n);value(b.getMaxHealth(),20,"Bad list changed max health");retained(b,"Attributes",raw);stable(b);}
    private static void badAttribute(String field,Tag raw){CompoundTag a=attribute("generic.maxHealth",30);if(raw==null)a.remove(field);else a.put(field,raw.copy());EntityLootableBody b=read(with(a));value(b.getMaxHealth(),20,"Bad attribute changed default");retained(b,"Attribute",a);stable(b);}
    private static void validTail(){CompoundTag a=attribute("generic.maxHealth",Double.NaN),n=base();n.put("Attributes",NbtRecoveryChecks.list(a,attribute("generic.maxHealth",30),attribute("generic.followRange",24)));EntityLootableBody b=read(n);value(b.getMaxHealth(),30,"Invalid first entry hid valid max health");value(b.getAttribute(Attributes.FOLLOW_RANGE).getBaseValue(),24,"Valid tail lost");retained(b,"Attribute",a);stable(b);}
    private static void duplicateAttribute(){CompoundTag duplicate=attribute("generic.maxHealth",40),n=base();n.put("Attributes",NbtRecoveryChecks.list(attribute("generic.maxHealth",30),duplicate));EntityLootableBody b=read(n);value(b.getMaxHealth(),30,"Duplicate overwrote first max health");retained(b,"Attribute",duplicate);stable(b);}
    private static void unknownAttributeData(){CompoundTag a=attribute("generic.maxHealth",30);a.putString("Opaque","retain");EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Known fields lost with unknown sibling");retained(b,"Attribute",a);stable(b);}
    private static void badModifierList(Tag raw){CompoundTag a=attribute("generic.maxHealth",30);a.put("Modifiers",raw.copy());EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Invalid modifiers lost valid base");retained(b,"Attribute",a);stable(b);}
    private static void badModifier(String field,Tag raw){CompoundTag m=modifier(5,0);if(raw==null)m.remove(field);else m.put(field,raw.copy());CompoundTag a=attribute("generic.maxHealth",30);a.put("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Malformed modifier activated");retained(b,"Attribute",a);stable(b);}
    private static void duplicateModifier(){CompoundTag a=attribute("generic.maxHealth",20);a.put("Modifiers",NbtRecoveryChecks.list(modifier(2,0),modifier(7,0)));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),22,"Duplicate modifier replaced first");retained(b,"Attribute",a);stable(b);}
    private static void modifierTail(){CompoundTag bad=modifier(Double.NaN,0),good=modifier(5,0),a=attribute("generic.maxHealth",30);legacyUuid(good,new UUID(0,142));a.put("Modifiers",NbtRecoveryChecks.list(bad,good));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Invalid modifier hid valid tail or base");retained(b,"Attribute",a);stable(b);}
    private static void unknownModifierData(){CompoundTag m=modifier(5,0),a=attribute("generic.maxHealth",30);m.putString("Opaque","retain");a.put("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Known modifier fields lost");retained(b,"Attribute",a);stable(b);}
    private static void overflow(){CompoundTag a=attribute("generic.maxHealth",30);a.put("Modifiers",NbtRecoveryChecks.list(modifier(Double.MAX_VALUE,2)));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),30,"Overflowing modifier activated");retained(b,"Attribute",a);stable(b);}
    private static void validModifiers(){CompoundTag a=attribute("generic.maxHealth",30);CompoundTag m0=modifier(-2,0),m1=modifier(.5,1),m2=modifier(.25,2);legacyUuid(m1,new UUID(0,142));legacyUuid(m2,new UUID(0,143));a.put("Modifiers",NbtRecoveryChecks.list(m0,m1,m2));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),52.5,"Valid signed/three-operation modifiers changed");RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid modifiers quarantined");stable(b);}
    private static void zeroUuid(){CompoundTag a=attribute("generic.maxHealth",30),m=modifier(5,0);m.putLong("UUIDMost",0);m.putLong("UUIDLeast",0);m.putByte("Operation",(byte)0);m.putInt("Amount",5);a.put("Modifiers",NbtRecoveryChecks.list(m));EntityLootableBody b=read(with(a));value(b.getMaxHealth(),35,"Zero UUID or lossless numbers rejected");stable(b);}
    private static void validBases(){for(Tag raw:new Tag[]{ByteTag.valueOf((byte)30),ShortTag.valueOf((short)30),IntTag.valueOf(30),LongTag.valueOf(30),FloatTag.valueOf(30.5F),DoubleTag.valueOf(30.5)}){CompoundTag a=attribute("generic.maxHealth",30);a.put("Base",raw);CompoundTag n=with(a);n.putDouble("Health",12.5);EntityLootableBody b=read(n);value(b.getMaxHealth(),((NumericTag)raw).getAsDouble(),"Valid base changed");value(b.getHealth(),12.5,"Fractional Health changed");stable(b);}}
    private static void repeated(){EntityLootableBody b=read(with(attribute("generic.maxHealth",30)));CompoundTag n=base();n.remove("Attributes");n.remove("Health");n.remove("AbsorptionAmount");b.setAbsorptionAmount(5);b.load(n);value(b.getMaxHealth(),20,"Missing Attributes retained previous max");value(b.getHealth(),20,"Missing Health retained previous value");value(b.getAbsorptionAmount(),0,"Missing absorption retained previous value");stable(b);}
    private static void repeatedModifiers(){CompoundTag a=attribute("generic.maxHealth",30);a.put("Modifiers",NbtRecoveryChecks.list(modifier(5,0)));EntityLootableBody b=read(with(a));b.load(with(attribute("generic.maxHealth",20)));value(b.getMaxHealth(),20,"Repeated read kept obsolete modifier");stable(b);}
    private static void pendingRemoval(){CompoundTag n=base();n.putFloat("Health",0);EntityLootableBody b=read(n);b.baseTick();n=base();n.putDouble("Health",Double.NaN);b.load(n);b.baseTick();RuntimeChecks.require(b.isAlive(),"Repaired owner still dead");inventoryKept(b);stable(b);n=base();n.putFloat("Health",3.5F);b.discard();RuntimeChecks.require(b.isRemoved(),"Native removal fixture was not applied");b.load(n);RuntimeChecks.require(b.isAlive(),"Reused entity kept stale dead flag");value(b.getHealth(),3.5,"Reset healed valid wounded corpse");}
    private static void inventory(){CompoundTag n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.putString("id","absentfixture:missing");n.getList("Equipment",10).add(raw);n.putDouble("Health",Double.NaN);CompoundTag a=attribute("generic.maxHealth",Double.NaN);n.put("Attributes",NbtRecoveryChecks.list(a,attribute("generic.followRange",24)));EntityLootableBody b=read(n);RuntimeChecks.require(b.isAlive(),"Invalid health made recoverable inventory inaccessible");NbtRecoveryChecks.retained(b,raw);retained(b,"Attribute",a);retained(b,"Health",n.get("Health"));stable(b);stable(read(NameLifetimeChecks.saved(b)));}
    private static void empty(){CompoundTag n=base();n.put("Equipment",new ListTag());n.putDouble("Health",Double.NaN);EntityLootableBody b=read(n);RuntimeChecks.require(b.isEmpty(),"Health evidence counted as inventory");retained(b,"Health",n.get("Health"));}
    private static void healthy(){CompoundTag n=base();EntityLootableBody b=read(n);RuntimeChecks.require(!NameLifetimeChecks.saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy save gained recovery records");stable(b);}

    private static void nativeUuid(boolean conflict,boolean malformed){
        CompoundTag modifier=modifier(5,0);modifier.remove("UUIDMost");modifier.remove("UUIDLeast");
        modifier.putUUID("UUID",MOD);
        if(conflict){modifier.putLong("UUIDMost",3);modifier.putLong("UUIDLeast",4);}
        if(malformed)modifier.putIntArray("UUID",new int[]{1,2,3});
        CompoundTag attribute=attribute("minecraft:generic.max_health",30);attribute.put("Modifiers",NbtRecoveryChecks.list(modifier));
        EntityLootableBody body=read(with(attribute));value(body.getMaxHealth(),conflict||malformed?30:35,"Native UUID modifier handling changed");
        if(conflict||malformed)retained(body,"Attribute",attribute);
        else RuntimeChecks.require(!NameLifetimeChecks.saved(body).contains(NameLifetimeChecks.RECOVERY),"Valid native modifier quarantined");
        stable(body);
    }
    private static void legacyForgeAttributes(){
        String[][] names={{"forge.swimSpeed","forge:swim_speed"},{"forge.nameTagDistance","forge:nametag_distance"},{"forge.entity_gravity","forge:entity_gravity"}};
        double[] bases={1.75D,70D,.12D};ListTag list=new ListTag();for(int i=0;i<names.length;i++)list.add(attribute(names[i][0],bases[i]));
        CompoundTag source=base();source.put("Attributes",list);source.putFloat("Health",7.5F);CompoundTag before=source.copy();EntityLootableBody body=read(source);
        value(body.getHealth(),7.5D,"Legacy Forge attributes healed a wounded corpse");
        for(int i=0;i<names.length;i++)value(body.getAttribute(net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getValue(new net.minecraft.resources.ResourceLocation(names[i][1]))).getBaseValue(),bases[i],"Legacy Forge attribute changed");
        RuntimeChecks.require(!NameLifetimeChecks.saved(body).contains(NameLifetimeChecks.RECOVERY),"Known legacy Forge attributes were quarantined");
        RuntimeChecks.require(TypedNbt.same(before,source),"Legacy attribute source mutated");stable(body);
    }

    static CompoundTag malformedFields(){
        CompoundTag n=new CompoundTag();n.putDouble("Health",Double.NaN);n.putString("AbsorptionAmount","opaque saved absorption");
        CompoundTag max=attribute("generic.maxHealth",Double.NaN),follow=attribute("generic.followRange",24),unknown=attribute("absentfixture:attribute",7);
        CompoundTag bad=modifier(Double.NaN,0),good=modifier(2,0);legacyUuid(good,new UUID(0,144));
        follow.put("Modifiers",NbtRecoveryChecks.list(bad,good));unknown.putString("Opaque","retain original attribute");
        n.put("Attributes",NbtRecoveryChecks.list(max,follow,unknown));return n;
    }
    static void verifyWorldFields(EntityLootableBody b){
        value(b.getHealth(),20,"Disk health unsafe");value(b.getMaxHealth(),20,"Disk max health unsafe");value(b.getAbsorptionAmount(),0,"Disk absorption unsafe");
        value(b.getAttribute(Attributes.FOLLOW_RANGE).getValue(),26,"Valid attribute/modifier tail lost on disk");
        CompoundTag raw=malformedFields();retained(b,"Health",raw.get("Health"));retained(b,"AbsorptionAmount",raw.get("AbsorptionAmount"));
        ListTag entries=raw.getList("Attributes",10);for(int i=0;i<entries.size();i++)retained(b,"Attribute",entries.getCompound(i));
    }
}
