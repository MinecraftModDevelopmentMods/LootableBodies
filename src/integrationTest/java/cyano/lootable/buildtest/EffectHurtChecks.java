package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.potion.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Real legacy readers, ticking and round trips; no production test dependency. */
final class EffectHurtChecks {
    private interface Check {void run() throws Exception;}
    private static WorldServer world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    private static Potion extended;
    static void registerPotion(){
        RuntimeChecks.require(Potion.getPotionById(200)==null,"Fixture potion ID already occupied");
        extended=new Potion(false,0x123456){};
        Potion.REGISTRY.register(200,new net.minecraft.util.ResourceLocation("lootablebodiesbuildtest:extended_effect"),extended);
    }
    static void run(MinecraftServer server,WorldServer target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        NBTBase[] wrong={new NBTTagString("opaque"),new NBTTagCompound(),new NBTTagByteArray(new byte[]{1}),new NBTTagIntArray(new int[]{1}),new NBTTagList()};
        for(int i=0;i<wrong.length;i++){final NBTBase raw=wrong[i];check("HurtTime_wrong_type_"+i,()->badHurt(raw));}
        for(NBTBase raw:new NBTBase[]{new NBTTagShort((short)-1),new NBTTagInt(32768),new NBTTagLong(65537),new NBTTagLong(Long.MAX_VALUE),new NBTTagFloat(2),new NBTTagDouble(2.5),new NBTTagDouble(Double.NaN),new NBTTagDouble(Double.POSITIVE_INFINITY)})check("HurtTime_invalid_"+raw,()->badHurt(raw));
        for(NBTBase raw:new NBTBase[]{new NBTTagByte((byte)7),new NBTTagShort((short)10),new NBTTagInt(11),new NBTTagLong(32767)})check("HurtTime_lossless_"+raw,()->validHurt(raw));
        check("missing_hurt_time_defaults_zero",()->{NBTTagCompound n=base();n.removeTag("HurtTime");EntityLootableBody b=read(n);require(b.hurtTime==0,"Missing hurt time not zero");stable(b);});
        for(int i=0;i<wrong.length;i++){final NBTBase raw=wrong[i] instanceof NBTTagList?new NBTTagInt(4):wrong[i];check("ActiveEffects_wrong_type_"+i,()->badList(raw));}
        check("ActiveEffects_wrong_element_type",()->{NBTTagList l=new NBTTagList();l.appendTag(new NBTTagString("opaque"));badList(l);});
        check("empty_and_missing_effects_keep_defaults",()->{for(boolean empty:new boolean[]{false,true}){NBTTagCompound n=base();if(empty)n.setTag("ActiveEffects",new NBTTagList());EntityLootableBody b=read(n);require(b.getActivePotionEffects().isEmpty(),"Unexpected default effect");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Healthy empty effects quarantined");stable(b);}});
        for(String key:new String[]{"Id","Duration","Amplifier"})for(int i=0;i<wrong.length;i++){final String k=key;final NBTBase raw=wrong[i];check(key+"_wrong_type_"+i,()->badEffect(k,raw));}
        for(NBTBase raw:new NBTBase[]{new NBTTagInt(-1),new NBTTagInt(256),new NBTTagLong(4294967297L),new NBTTagDouble(1),new NBTTagByte((byte)201)})check("Id_invalid_or_absent_registry_"+raw,()->badEffect("Id",raw));
        check("Id_missing_retains_record",()->badEffect("Id",null));
        for(NBTBase raw:new NBTBase[]{new NBTTagInt(-1),new NBTTagLong(2147483648L),new NBTTagLong(4294967496L),new NBTTagFloat(100),new NBTTagDouble(Double.NaN),new NBTTagDouble(Double.NEGATIVE_INFINITY)})check("Duration_invalid_"+raw,()->badEffect("Duration",raw));
        for(NBTBase raw:new NBTBase[]{new NBTTagShort((short)-1),new NBTTagInt(128),new NBTTagInt(256),new NBTTagLong(4294967296L),new NBTTagFloat(1),new NBTTagDouble(Double.POSITIVE_INFINITY)})check("Amplifier_invalid_"+raw,()->badEffect("Amplifier",raw));
        for(String key:new String[]{"Ambient","ShowParticles"}){
            for(int i=0;i<wrong.length;i++){final String k=key;final NBTBase raw=wrong[i];check(key+"_wrong_type_"+i,()->badFlag(k,raw));}
            for(NBTBase raw:new NBTBase[]{new NBTTagByte((byte)-1),new NBTTagInt(2),new NBTTagLong(256),new NBTTagFloat(1),new NBTTagDouble(Double.NaN)})check(key+"_invalid_"+raw,()->badFlag(key,raw));
        }
        check("lossless_integral_effect_fields",EffectHurtChecks::numeric);
        check("missing_optional_effect_fields_keep_legacy_defaults",EffectHurtChecks::missingOptional);
        check("zero_duration_expires_normally",()->validDuration(0));
        check("maximum_duration_does_not_overflow",()->validDuration(Integer.MAX_VALUE));
        check("signed_byte_amplifier_encodings_keep_legacy_meaning",EffectHurtChecks::signedAmplifiers);
        check("registered_unsigned_byte_effect_id_survives_reload",EffectHurtChecks::extendedId);
        check("duplicate_id_numeric_alias_keeps_first",EffectHurtChecks::alias);
        check("invalid_record_does_not_hide_valid_tail",EffectHurtChecks::validTail);
        check("duplicate_effect_keeps_first_valid_record",EffectHurtChecks::duplicate);
        check("unknown_effect_payload_retained_with_known_fields",EffectHurtChecks::unknown);
        check("repeated_read_replaces_effects_and_metadata",EffectHurtChecks::repeated);
        check("valid_potion_attributes_health_absorption_survive_reload",EffectHurtChecks::attributes);
        check("repeated_read_removes_obsolete_potion_modifiers",EffectHurtChecks::repeatedAttributes);
        check("reused_output_does_not_keep_previous_effect_list",EffectHurtChecks::reusedOutput);
        check("invalid_effect_and_hurt_fields_keep_complete_unresolved_inventory",EffectHurtChecks::inventory);
        check("metadata_only_does_not_prevent_empty_decay",EffectHurtChecks::empty);
        try(PrintWriter out=new PrintWriter(new File(folder,"effect-hurt-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        require(failures==0,"Effect/hurt regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_EFFECT_HURT_PASS checks="+results.size());
    }
    private static void check(String name,Check test){try{test.run();results.add("PASS "+name);System.out.println("EFFECT_HURT_PASS "+name);}catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("EFFECT_HURT_FAIL "+name+": "+e);e.printStackTrace();}}
    private static void require(boolean c,String why){RuntimeChecks.require(c,why);}
    private static NBTTagCompound saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static NBTTagCompound base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(b);return saved(b);}
    private static EntityLootableBody read(NBTTagCompound n){NBTTagCompound before=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.readFromNBT(n);require(NameLifetimeChecks.same(before,n),"Input mutated");return b;}
    static NBTTagCompound effect(Potion p,int duration,int amplifier){return new PotionEffect(p,duration,amplifier,false,true).writeCustomPotionEffectToNBT(new NBTTagCompound());}
    private static NBTTagCompound effect(){return effect(MobEffects.NIGHT_VISION,200,1);}
    private static NBTTagCompound with(NBTTagCompound e){NBTTagCompound n=base();n.setTag("ActiveEffects",NbtRecoveryChecks.list(e));return n;}
    private static void stable(EntityLootableBody b){NBTTagCompound a=saved(b),z=saved(read(a));for(String k:new String[]{"Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY,"ActiveEffects","HurtTime","Health","AbsorptionAmount","Attributes"})require(NameLifetimeChecks.same(a.getTag(k),z.getTag(k)),"Round trip changed "+k);require(ItemStack.areItemStacksEqual(b.getStackInSlot(6),NbtRecoveryChecks.item(120,17)),"Complete known inventory changed");}
    private static void badHurt(NBTBase raw){NBTTagCompound n=base();n.setTag("HurtTime",raw.copy());EntityLootableBody b=read(n);require(b.hurtTime==0 && b.isEntityAlive(),"Bad hurt time activated");NameLifetimeChecks.retained(b,"HurtTime",raw);stable(b);}
    private static void validHurt(NBTBase raw){NBTTagCompound n=base();n.setTag("HurtTime",raw);EntityLootableBody b=read(n);int v=((NBTPrimitive)raw).getInt();require(b.hurtTime==v,"Valid countdown narrowed");stable(b);b.onEntityUpdate();require(b.hurtTime==v-1,"Countdown no longer ticks");}
    private static void badList(NBTBase raw){NBTTagCompound n=base();n.setTag("ActiveEffects",raw.copy());EntityLootableBody b=read(n);require(b.getActivePotionEffects().isEmpty(),"Bad effect list activated");NameLifetimeChecks.retained(b,"ActiveEffects",raw);stable(b);}
    private static void badEffect(String key,NBTBase raw){NBTTagCompound e=effect();if(raw==null)e.removeTag(key);else e.setTag(key,raw.copy());EntityLootableBody b=read(with(e));require(b.getActivePotionEffects().isEmpty(),"Malformed effect activated");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void badFlag(String key,NBTBase raw){NBTTagCompound e=effect();e.setTag(key,raw.copy());EntityLootableBody b=read(with(e));PotionEffect p=b.getActivePotionEffect(MobEffects.NIGHT_VISION);require(p!=null && p.getDuration()==200 && p.getAmplifier()==1,"Bad optional flag lost valid effect");require(!p.getIsAmbient() && p.doesShowParticles(),"Bad optional flag did not use defaults");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void numeric(){for(int type=0;type<4;type++){NBTTagCompound e=effect();NBTBase[] ids={new NBTTagByte((byte)16),new NBTTagShort((short)16),new NBTTagInt(16),new NBTTagLong(16)};NBTBase[] one={new NBTTagByte((byte)1),new NBTTagShort((short)1),new NBTTagInt(1),new NBTTagLong(1)};e.setTag("Id",ids[type]);e.setTag("Duration",one[type]);e.setTag("Amplifier",one[type].copy());e.setTag("Ambient",one[type].copy());e.setTag("ShowParticles",one[type].copy());EntityLootableBody b=read(with(e));PotionEffect p=b.getActivePotionEffect(MobEffects.NIGHT_VISION);require(p!=null && p.getDuration()==1 && p.getAmplifier()==1 && p.getIsAmbient() && p.doesShowParticles(),"Lossless integral effect changed");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid numbers quarantined");stable(b);}}
    private static void missingOptional(){NBTTagCompound e=effect();for(String k:new String[]{"Duration","Amplifier","Ambient","ShowParticles"})e.removeTag(k);EntityLootableBody b=read(with(e));PotionEffect p=b.getActivePotionEffect(MobEffects.NIGHT_VISION);require(p!=null && p.getDuration()==0 && p.getAmplifier()==0 && !p.getIsAmbient() && p.doesShowParticles(),"Legacy defaults changed");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Missing optional fields quarantined");stable(b);}
    private static void validDuration(int d){EntityLootableBody b=read(with(effect(MobEffects.NIGHT_VISION,d,0)));b.onEntityUpdate();PotionEffect p=b.getActivePotionEffect(MobEffects.NIGHT_VISION);require(d==0?p==null:p!=null && p.getDuration()==d-1,"Valid duration tick/expiry changed");stable(b);}
    private static void signedAmplifiers(){for(int a:new int[]{-128,-1,0,127}){EntityLootableBody b=read(with(effect(MobEffects.NIGHT_VISION,200,a)));require(b.getActivePotionEffect(MobEffects.NIGHT_VISION).getAmplifier()==a,"Existing byte amplifier interpretation changed");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Canonical signed byte quarantined");stable(b);}}
    private static void extendedId(){for(boolean wide:new boolean[]{false,true}){NBTTagCompound e=effect(extended,200,1);if(wide)e.setInteger("Id",200);EntityLootableBody b=read(with(e));require(b.getActivePotionEffect(extended)!=null && b.getActivePotionEffect(extended).getDuration()==200,"Registered unsigned ID changed");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid extended ID quarantined");stable(b);}}
    private static void alias(){NBTTagCompound first=effect(extended,200,1),second=effect(extended,900,3),n=base();second.setLong("Id",200);n.setTag("ActiveEffects",NbtRecoveryChecks.list(first,second));EntityLootableBody b=read(n);require(b.getActivePotionEffect(extended).getDuration()==200,"Numeric alias bypassed first valid effect");NameLifetimeChecks.retained(b,"ActiveEffect",second);stable(b);}
    private static void validTail(){NBTTagCompound bad=effect();bad.setString("Duration","opaque");NBTTagCompound n=base();n.setTag("ActiveEffects",NbtRecoveryChecks.list(bad,effect()));EntityLootableBody b=read(n);require(b.getActivePotionEffect(MobEffects.NIGHT_VISION).getDuration()==200,"Bad first effect hid valid tail");NameLifetimeChecks.retained(b,"ActiveEffect",bad);stable(b);}
    private static void duplicate(){NBTTagCompound first=effect(),second=effect(MobEffects.NIGHT_VISION,900,3),n=base();n.setTag("ActiveEffects",NbtRecoveryChecks.list(first,second));EntityLootableBody b=read(n);require(b.getActivePotionEffect(MobEffects.NIGHT_VISION).getDuration()==200,"Duplicate overwrote first effect");NameLifetimeChecks.retained(b,"ActiveEffect",second);stable(b);}
    private static void unknown(){NBTTagCompound e=effect();e.setString("Opaque","retain effect extension");EntityLootableBody b=read(with(e));require(b.getActivePotionEffects().size()==1,"Unknown sibling lost understood effect");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void repeated(){EntityLootableBody b=read(with(effect(MobEffects.INVISIBILITY,200,0)));b.onEntityUpdate();require(b.isInvisible(),"Fixture not invisible");b.readFromNBT(base());require(b.getActivePotionEffects().isEmpty() && !b.isInvisible(),"Previous effects or render state survived read");stable(b);}
    private static void attributes(){EntityLootableBody original=new EntityLootableBody(world);original.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(original);original.addPotionEffect(new PotionEffect(MobEffects.HEALTH_BOOST,200,1));original.addPotionEffect(new PotionEffect(MobEffects.SPEED,200,0));original.addPotionEffect(new PotionEffect(MobEffects.ABSORPTION,200,0));original.setHealth(original.getMaxHealth());NBTTagCompound n=saved(original);EntityLootableBody b=read(n);require(b.getMaxHealth()==28 && b.getHealth()==28 && b.getAbsorptionAmount()==4 && b.getActivePotionEffects().size()==3,"Valid potion health/attributes/absorption changed");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Healthy potion fields quarantined");stable(b);for(int i=0;i<200;i++)b.onEntityUpdate();require(b.getActivePotionEffects().isEmpty() && b.getMaxHealth()==20 && b.getAbsorptionAmount()==0 && b.getHealth()<=20,"Expiry did not clean up normal modifiers");}
    private static void repeatedAttributes(){EntityLootableBody original=new EntityLootableBody(world);original.addPotionEffect(new PotionEffect(MobEffects.HEALTH_BOOST,200,1));EntityLootableBody b=read(saved(original));b.readFromNBT(base());require(b.getActivePotionEffects().isEmpty() && b.getMaxHealth()==20,"Obsolete effects/modifiers survived read");stable(b);}
    private static void reusedOutput(){EntityLootableBody b=read(base());NBTTagCompound n=with(effect());b.writeEntityToNBT(n);require(!n.hasKey("ActiveEffects"),"Writer retained obsolete ActiveEffects");stable(b);}
    private static void inventory(){NBTTagCompound n=base(),missing=NbtRecoveryChecks.entry(121,11,7);missing.setString("id","absentfixture:missing");n.getTagList("Equipment",10).appendTag(missing);NBTTagCompound raw=malformedFields();for(String k:raw.getKeySet())n.setTag(k,raw.getTag(k).copy());EntityLootableBody b=read(n);require(b.isEntityAlive(),"Bad fields made corpse inaccessible");NbtRecoveryChecks.retained(b,missing);verifyWorldFields(b);stable(b);stable(read(saved(b)));}
    private static void empty(){NBTTagCompound n=base();n.setTag("Equipment",new NBTTagList());n.setString("HurtTime","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Metadata counts as inventory");NameLifetimeChecks.retained(b,"HurtTime",n.getTag("HurtTime"));}
    static NBTTagCompound malformedFields(){NBTTagCompound n=new NBTTagCompound();n.setLong("HurtTime",65537);NBTTagCompound bad=effect();bad.setString("Duration","opaque saved duration");NBTTagCompound unknown=effect();unknown.setInteger("Id",250);unknown.setString("Opaque","missing effect data");n.setTag("ActiveEffects",NbtRecoveryChecks.list(bad,unknown));return n;}
    static void verifyWorldFields(EntityLootableBody b){require(b.hurtTime==0 && b.getActivePotionEffects().isEmpty(),"Malformed disk effect/hurt state activated");NBTTagCompound raw=malformedFields();NameLifetimeChecks.retained(b,"HurtTime",raw.getTag("HurtTime"));NBTTagList list=raw.getTagList("ActiveEffects",10);for(int i=0;i<list.tagCount();i++)NameLifetimeChecks.retained(b,"ActiveEffect",list.getCompoundTagAt(i));}
}
