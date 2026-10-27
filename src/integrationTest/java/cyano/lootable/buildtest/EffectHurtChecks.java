package cyano.lootable.buildtest;
import net.minecraft.potion.EffectInstance;
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

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.potion.Effects;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.potion.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;

/** Real legacy readers, ticking and round trips; no production test dependency. */
final class EffectHurtChecks {
    private interface Check {void run() throws Exception;}
    private static ServerWorld world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    private static Effect extended;
    static void registerPotion(){
        RuntimeChecks.require(Effect.get(200)==null,"Fixture potion ID already occupied");
        extended=new Effect(net.minecraft.potion.EffectType.BENEFICIAL,0x123456){};
        // Forge 1.12 assigns IDs through registry events. Fill the isolated test
        // registry through its public API so unsigned-byte coverage still uses 200.
        for(int i=0;Effect.get(199)==null && i<200;i++)
            net.minecraftforge.registries.ForgeRegistries.POTIONS.register(new Effect(net.minecraft.potion.EffectType.BENEFICIAL,0){}.setRegistryName("lootablebodiesbuildtest:padding_"+i));
        net.minecraftforge.registries.ForgeRegistries.POTIONS.register(extended.setRegistryName("lootablebodiesbuildtest:extended_effect"));
        RuntimeChecks.require(Effect.getId(extended)==200,"Fixture potion must exercise unsigned byte ID 200");
        // Older probes saved a different number of padding effects. Retain their
        // registry names during upgrades without changing fresh-world ID tests.
        if(System.getProperty("lootablebodies.phase","").startsWith("upgrade-"))
            for(int i=0;i<200;i++) {
                net.minecraft.util.ResourceLocation name=new net.minecraft.util.ResourceLocation("lootablebodiesbuildtest:padding_"+i);
                if(!net.minecraftforge.registries.ForgeRegistries.POTIONS.containsKey(name))
                    net.minecraftforge.registries.ForgeRegistries.POTIONS.register(new Effect(net.minecraft.potion.EffectType.BENEFICIAL,0){}.setRegistryName(name));
            }
    }
    static void run(MinecraftServer server,ServerWorld target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        INBT[] wrong={StringNBT.valueOf("opaque"),new CompoundNBT(),new ByteArrayNBT(new byte[]{1}),new IntArrayNBT(new int[]{1}),new ListNBT()};
        for(int i=0;i<wrong.length;i++){final INBT raw=wrong[i];check("HurtTime_wrong_type_"+i,()->badHurt(raw));}
        for(INBT raw:new INBT[]{ShortNBT.valueOf((short)-1),IntNBT.valueOf(32768),LongNBT.valueOf(65537),LongNBT.valueOf(Long.MAX_VALUE),FloatNBT.valueOf(2),DoubleNBT.valueOf(2.5),DoubleNBT.valueOf(Double.NaN),DoubleNBT.valueOf(Double.POSITIVE_INFINITY)})check("HurtTime_invalid_"+raw,()->badHurt(raw));
        for(INBT raw:new INBT[]{ByteNBT.valueOf((byte)7),ShortNBT.valueOf((short)10),IntNBT.valueOf(11),LongNBT.valueOf(32767)})check("HurtTime_lossless_"+raw,()->validHurt(raw));
        check("missing_hurt_time_defaults_zero",()->{CompoundNBT n=base();n.remove("HurtTime");EntityLootableBody b=read(n);require(b.hurtTime==0,"Missing hurt time not zero");stable(b);});
        for(int i=0;i<wrong.length;i++){final INBT raw=wrong[i] instanceof ListNBT?IntNBT.valueOf(4):wrong[i];check("ActiveEffects_wrong_type_"+i,()->badList(raw));}
        check("ActiveEffects_wrong_element_type",()->{ListNBT l=new ListNBT();l.add(StringNBT.valueOf("opaque"));badList(l);});
        check("empty_and_missing_effects_keep_defaults",()->{for(boolean empty:new boolean[]{false,true}){CompoundNBT n=base();if(empty)n.put("ActiveEffects",new ListNBT());EntityLootableBody b=read(n);require(b.getActivePotionEffects().isEmpty(),"Unexpected default effect");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy empty effects quarantined");stable(b);}});
        for(String key:new String[]{"Id","Duration","Amplifier"})for(int i=0;i<wrong.length;i++){final String k=key;final INBT raw=wrong[i];check(key+"_wrong_type_"+i,()->badEffect(k,raw));}
        for(INBT raw:new INBT[]{IntNBT.valueOf(-1),IntNBT.valueOf(256),LongNBT.valueOf(4294967297L),DoubleNBT.valueOf(1),ByteNBT.valueOf((byte)201)})check("Id_invalid_or_absent_registry_"+raw,()->badEffect("Id",raw));
        check("Id_missing_retains_record",()->badEffect("Id",null));
        for(INBT raw:new INBT[]{IntNBT.valueOf(-1),LongNBT.valueOf(2147483648L),LongNBT.valueOf(4294967496L),FloatNBT.valueOf(100),DoubleNBT.valueOf(Double.NaN),DoubleNBT.valueOf(Double.NEGATIVE_INFINITY)})check("Duration_invalid_"+raw,()->badEffect("Duration",raw));
        for(INBT raw:new INBT[]{ShortNBT.valueOf((short)-1),IntNBT.valueOf(128),IntNBT.valueOf(256),LongNBT.valueOf(4294967296L),FloatNBT.valueOf(1),DoubleNBT.valueOf(Double.POSITIVE_INFINITY)})check("Amplifier_invalid_"+raw,()->badEffect("Amplifier",raw));
        for(String key:new String[]{"Ambient","ShowParticles","ShowIcon"}){
            for(int i=0;i<wrong.length;i++){final String k=key;final INBT raw=wrong[i];check(key+"_wrong_type_"+i,()->badFlag(k,raw));}
            for(INBT raw:new INBT[]{ByteNBT.valueOf((byte)-1),IntNBT.valueOf(2),LongNBT.valueOf(256),FloatNBT.valueOf(1),DoubleNBT.valueOf(Double.NaN)})check(key+"_invalid_"+raw,()->badFlag(key,raw));
        }
        check("lossless_integral_effect_fields",EffectHurtChecks::numeric);
        check("missing_optional_effect_fields_keep_legacy_defaults",EffectHurtChecks::missingOptional);
        check("zero_duration_expires_normally",()->validDuration(0));
        check("maximum_duration_does_not_overflow",()->validDuration(Integer.MAX_VALUE));
        check("signed_byte_amplifier_encodings_keep_native_111_clamping",EffectHurtChecks::signedAmplifiers);
        check("forge_curative_items_keep_complete_item_data",EffectHurtChecks::curativeItems);
        check("malformed_curative_items_retained_with_safe_defaults",EffectHurtChecks::badCurativeItems);
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
        check("native_icon_flag_survives_without_quarantine",()->{CompoundNBT e=effect();e.putBoolean("ShowIcon",false);EntityLootableBody b=read(with(e));require(!b.getActivePotionEffect(Effects.NIGHT_VISION).isShowIcon(),"Native icon visibility changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Native icon visibility quarantined");stable(b);});
        check("missing_icon_flag_follows_particle_default",()->{CompoundNBT e=effect();e.remove("ShowIcon");e.putBoolean("ShowParticles",false);EntityLootableBody b=read(with(e));require(!b.getActivePotionEffect(Effects.NIGHT_VISION).isShowIcon(),"Missing native icon default changed");stable(b);});
        check("metadata_only_does_not_prevent_empty_decay",EffectHurtChecks::empty);
        try(PrintWriter out=new PrintWriter(new File(folder,"effect-hurt-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        require(failures==0,"Effect/hurt regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_EFFECT_HURT_PASS checks="+results.size());
    }
    private static void check(String name,Check test){try{test.run();results.add("PASS "+name);System.out.println("EFFECT_HURT_PASS "+name);}catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("EFFECT_HURT_FAIL "+name+": "+e);e.printStackTrace();}}
    private static void require(boolean c,String why){RuntimeChecks.require(c,why);}
    private static CompoundNBT saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static CompoundNBT base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(b);return saved(b);}
    private static EntityLootableBody read(CompoundNBT n){CompoundNBT before=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.read(n);require(NameLifetimeChecks.same(before,n),"Input mutated");return b;}
    static CompoundNBT effect(Effect p,int duration,int amplifier){return new EffectInstance(p,duration,amplifier,false,true).write(new CompoundNBT());}
    private static CompoundNBT effect(){return effect(Effects.NIGHT_VISION,200,1);}
    private static CompoundNBT with(CompoundNBT e){CompoundNBT n=base();n.put("ActiveEffects",NbtRecoveryChecks.list(e));return n;}
    private static void stable(EntityLootableBody b){CompoundNBT a=saved(b),z=saved(read(a));for(String k:new String[]{"Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY,"ActiveEffects","HurtTime","Health","AbsorptionAmount","Attributes"})require(NameLifetimeChecks.same(a.get(k),z.get(k)),"Round trip changed "+k);require(RuntimeChecks.sameStack(b.getStackInSlot(6),NbtRecoveryChecks.item(120,17)),"Complete known inventory changed");}
    private static void badHurt(INBT raw){CompoundNBT n=base();n.put("HurtTime",raw.copy());EntityLootableBody b=read(n);require(b.hurtTime==0 && b.isAlive(),"Bad hurt time activated");NameLifetimeChecks.retained(b,"HurtTime",raw);stable(b);}
    private static void validHurt(INBT raw){CompoundNBT n=base();n.put("HurtTime",raw);EntityLootableBody b=read(n);int v=((NumberNBT)raw).getInt();require(b.hurtTime==v,"Valid countdown narrowed");stable(b);b.baseTick();require(b.hurtTime==v-1,"Countdown no longer ticks");}
    private static void badList(INBT raw){CompoundNBT n=base();n.put("ActiveEffects",raw.copy());EntityLootableBody b=read(n);require(b.getActivePotionEffects().isEmpty(),"Bad effect list activated");NameLifetimeChecks.retained(b,"ActiveEffects",raw);stable(b);}
    private static void badEffect(String key,INBT raw){CompoundNBT e=effect();if(raw==null)e.remove(key);else e.put(key,raw.copy());EntityLootableBody b=read(with(e));require(b.getActivePotionEffects().isEmpty(),"Malformed effect activated");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void badFlag(String key,INBT raw){CompoundNBT e=effect();e.put(key,raw.copy());EntityLootableBody b=read(with(e));EffectInstance p=b.getActivePotionEffect(Effects.NIGHT_VISION);require(p!=null && p.getDuration()==200 && p.getAmplifier()==1,"Bad optional flag lost valid effect");require(!p.isAmbient() && p.doesShowParticles(),"Bad optional flag did not use defaults");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void numeric(){for(int type=0;type<4;type++){CompoundNBT e=effect();INBT[] ids={ByteNBT.valueOf((byte)16),ShortNBT.valueOf((short)16),IntNBT.valueOf(16),LongNBT.valueOf(16)};INBT[] one={ByteNBT.valueOf((byte)1),ShortNBT.valueOf((short)1),IntNBT.valueOf(1),LongNBT.valueOf(1)};e.put("Id",ids[type]);e.put("Duration",one[type]);e.put("Amplifier",one[type].copy());e.put("Ambient",one[type].copy());e.put("ShowParticles",one[type].copy());EntityLootableBody b=read(with(e));EffectInstance p=b.getActivePotionEffect(Effects.NIGHT_VISION);require(p!=null && p.getDuration()==1 && p.getAmplifier()==1 && p.isAmbient() && p.doesShowParticles(),"Lossless integral effect changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid numbers quarantined");stable(b);}}
    private static void missingOptional(){CompoundNBT e=effect();for(String k:new String[]{"Duration","Amplifier","Ambient","ShowParticles"})e.remove(k);EntityLootableBody b=read(with(e));EffectInstance p=b.getActivePotionEffect(Effects.NIGHT_VISION);require(p!=null && p.getDuration()==0 && p.getAmplifier()==0 && !p.isAmbient() && p.doesShowParticles(),"Legacy defaults changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Missing optional fields quarantined");stable(b);}
    private static void validDuration(int d){EntityLootableBody b=read(with(effect(Effects.NIGHT_VISION,d,0)));b.baseTick();EffectInstance p=b.getActivePotionEffect(Effects.NIGHT_VISION);require(d==0?p==null:p!=null && p.getDuration()==d-1,"Valid duration tick/expiry changed");stable(b);}
    private static void signedAmplifiers(){for(int a:new int[]{-128,-1,0,127}){EntityLootableBody b=read(with(effect(Effects.NIGHT_VISION,200,a)));require(b.getActivePotionEffect(Effects.NIGHT_VISION).getAmplifier()==Math.max(0,a),"Native 1.11 byte amplifier clamping changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Canonical signed byte quarantined");stable(b);}}
    private static void curativeItems(){CompoundNBT e=effect();CompoundNBT item=NbtRecoveryChecks.item(120,7).write(new CompoundNBT());ListNBT list=new ListNBT();list.add(item);e.put("CurativeItems",list);EntityLootableBody b=read(with(e));require(b.getActivePotionEffect(Effects.NIGHT_VISION).getCurativeItems().size()==1 && RuntimeChecks.sameStack(b.getActivePotionEffect(Effects.NIGHT_VISION).getCurativeItems().get(0),ItemStack.read(item)),"Forge curative item NBT changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy Forge curative items quarantined");stable(b);}
    private static void badCurativeItems(){CompoundNBT e=effect();e.putString("CurativeItems","opaque");EntityLootableBody b=read(with(e));NameLifetimeChecks.retained(b,"ActiveEffect",e);require(!b.getActivePotionEffect(Effects.NIGHT_VISION).getCurativeItems().isEmpty(),"Malformed curative list did not use native defaults");stable(b);}
    private static void extendedId(){for(boolean wide:new boolean[]{false,true}){CompoundNBT e=effect(extended,200,1);if(wide)e.putInt("Id",200);EntityLootableBody b=read(with(e));require(b.getActivePotionEffect(extended)!=null && b.getActivePotionEffect(extended).getDuration()==200,"Registered unsigned ID changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid extended ID quarantined");stable(b);}}
    private static void alias(){CompoundNBT first=effect(extended,200,1),second=effect(extended,900,3),n=base();second.putLong("Id",200);n.put("ActiveEffects",NbtRecoveryChecks.list(first,second));EntityLootableBody b=read(n);require(b.getActivePotionEffect(extended).getDuration()==200,"Numeric alias bypassed first valid effect");NameLifetimeChecks.retained(b,"ActiveEffect",second);stable(b);}
    private static void validTail(){CompoundNBT bad=effect();bad.putString("Duration","opaque");CompoundNBT n=base();n.put("ActiveEffects",NbtRecoveryChecks.list(bad,effect()));EntityLootableBody b=read(n);require(b.getActivePotionEffect(Effects.NIGHT_VISION).getDuration()==200,"Bad first effect hid valid tail");NameLifetimeChecks.retained(b,"ActiveEffect",bad);stable(b);}
    private static void duplicate(){CompoundNBT first=effect(),second=effect(Effects.NIGHT_VISION,900,3),n=base();n.put("ActiveEffects",NbtRecoveryChecks.list(first,second));EntityLootableBody b=read(n);require(b.getActivePotionEffect(Effects.NIGHT_VISION).getDuration()==200,"Duplicate overwrote first effect");NameLifetimeChecks.retained(b,"ActiveEffect",second);stable(b);}
    private static void unknown(){CompoundNBT e=effect();e.putString("Opaque","retain effect extension");EntityLootableBody b=read(with(e));require(b.getActivePotionEffects().size()==1,"Unknown sibling lost understood effect");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void repeated(){EntityLootableBody b=read(with(effect(Effects.INVISIBILITY,200,0)));b.baseTick();require(b.isInvisible(),"Fixture not invisible");b.read(base());require(b.getActivePotionEffects().isEmpty() && !b.isInvisible(),"Previous effects or render state survived read");stable(b);}
    private static void attributes(){EntityLootableBody original=new EntityLootableBody(world);original.setInventorySlotContents(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(original);original.addPotionEffect(new EffectInstance(Effects.HEALTH_BOOST,200,1));original.addPotionEffect(new EffectInstance(Effects.SPEED,200,0));original.addPotionEffect(new EffectInstance(Effects.ABSORPTION,200,0));original.setHealth(original.getMaxHealth());CompoundNBT n=saved(original);EntityLootableBody b=read(n);require(b.getMaxHealth()==28 && b.getHealth()==28 && b.getAbsorptionAmount()==4 && b.getActivePotionEffects().size()==3,"Valid potion health/attributes/absorption changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy potion fields quarantined");stable(b);for(int i=0;i<200;i++)b.baseTick();require(b.getActivePotionEffects().isEmpty() && b.getMaxHealth()==20 && b.getAbsorptionAmount()==0 && b.getHealth()<=20,"Expiry did not clean up normal modifiers");}
    private static void repeatedAttributes(){EntityLootableBody original=new EntityLootableBody(world);original.addPotionEffect(new EffectInstance(Effects.HEALTH_BOOST,200,1));EntityLootableBody b=read(saved(original));b.read(base());require(b.getActivePotionEffects().isEmpty() && b.getMaxHealth()==20,"Obsolete effects/modifiers survived read");stable(b);}
    private static void reusedOutput(){EntityLootableBody b=read(base());CompoundNBT n=with(effect());b.writeAdditional(n);require(!n.contains("ActiveEffects"),"Writer retained obsolete ActiveEffects");stable(b);}
    private static void inventory(){CompoundNBT n=base(),missing=NbtRecoveryChecks.entry(121,11,7);missing.putString("id","absentfixture:missing");n.getList("Equipment",10).add(missing);CompoundNBT raw=malformedFields();for(String k:raw.keySet())n.put(k,raw.get(k).copy());EntityLootableBody b=read(n);require(b.isAlive(),"Bad fields made corpse inaccessible");NbtRecoveryChecks.retained(b,missing);verifyWorldFields(b);stable(b);stable(read(saved(b)));}
    private static void empty(){CompoundNBT n=base();n.put("Equipment",new ListNBT());n.putString("HurtTime","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Metadata counts as inventory");NameLifetimeChecks.retained(b,"HurtTime",n.get("HurtTime"));}
    static CompoundNBT malformedFields(){CompoundNBT n=new CompoundNBT();n.putLong("HurtTime",65537);CompoundNBT bad=effect();bad.putString("Duration","opaque saved duration");CompoundNBT unknown=effect();unknown.putInt("Id",250);unknown.putString("Opaque","missing effect data");n.put("ActiveEffects",NbtRecoveryChecks.list(bad,unknown));return n;}
    static void verifyWorldFields(EntityLootableBody b){require(b.hurtTime==0 && b.getActivePotionEffects().isEmpty(),"Malformed disk effect/hurt state activated");CompoundNBT raw=malformedFields();NameLifetimeChecks.retained(b,"HurtTime",raw.get("HurtTime"));ListNBT list=raw.getList("ActiveEffects",10);for(int i=0;i<list.size();i++)NameLifetimeChecks.retained(b,"ActiveEffect",list.getCompound(i));}
}
