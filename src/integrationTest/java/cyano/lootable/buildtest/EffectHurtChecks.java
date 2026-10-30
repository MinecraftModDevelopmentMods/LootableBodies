package cyano.lootable.buildtest;
import net.minecraft.world.effect.MobEffectInstance;
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

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
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
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Real legacy readers, ticking and round trips; no production test dependency. */
final class EffectHurtChecks {
    private interface Check {void run() throws Exception;}
    private static ServerLevel world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    private static MobEffect extended;
    static void registerPotion(){
        RuntimeChecks.require(MobEffect.byId(200)==null,"Fixture potion ID already occupied");
        extended=new MobEffect(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL,0x123456){};
        // Forge 1.12 assigns IDs through registry events. Fill the isolated test
        // registry through its public API so unsigned-byte coverage still uses 200.
        for(int i=0;MobEffect.byId(199)==null && i<200;i++)
            net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.register(new MobEffect(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL,0){}.setRegistryName("lootablebodiesbuildtest:padding_"+i));
        net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.register(extended.setRegistryName("lootablebodiesbuildtest:extended_effect"));
        RuntimeChecks.require(MobEffect.getId(extended)==200,"Fixture potion must exercise unsigned byte ID 200");
        // Older probes saved a different number of padding effects. Retain their
        // registry names during upgrades without changing fresh-world ID tests.
        if(System.getProperty("lootablebodies.phase","").startsWith("upgrade-"))
            for(int i=0;i<200;i++) {
                net.minecraft.resources.ResourceLocation name=new net.minecraft.resources.ResourceLocation("lootablebodiesbuildtest:padding_"+i);
                if(!net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.containsKey(name))
                    net.minecraftforge.registries.ForgeRegistries.MOB_EFFECTS.register(new MobEffect(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL,0){}.setRegistryName(name));
            }
    }
    static void run(MinecraftServer server,ServerLevel target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        Tag[] wrong={StringTag.valueOf("opaque"),new CompoundTag(),new ByteArrayTag(new byte[]{1}),new IntArrayTag(new int[]{1}),new ListTag()};
        for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i];check("HurtTime_wrong_type_"+i,()->badHurt(raw));}
        for(Tag raw:new Tag[]{ShortTag.valueOf((short)-1),IntTag.valueOf(32768),LongTag.valueOf(65537),LongTag.valueOf(Long.MAX_VALUE),FloatTag.valueOf(2),DoubleTag.valueOf(2.5),DoubleTag.valueOf(Double.NaN),DoubleTag.valueOf(Double.POSITIVE_INFINITY)})check("HurtTime_invalid_"+raw,()->badHurt(raw));
        for(Tag raw:new Tag[]{ByteTag.valueOf((byte)7),ShortTag.valueOf((short)10),IntTag.valueOf(11),LongTag.valueOf(32767)})check("HurtTime_lossless_"+raw,()->validHurt(raw));
        check("missing_hurt_time_defaults_zero",()->{CompoundTag n=base();n.remove("HurtTime");EntityLootableBody b=read(n);require(b.hurtTime==0,"Missing hurt time not zero");stable(b);});
        for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i] instanceof ListTag?IntTag.valueOf(4):wrong[i];check("ActiveEffects_wrong_type_"+i,()->badList(raw));}
        check("ActiveEffects_wrong_element_type",()->{ListTag l=new ListTag();l.add(StringTag.valueOf("opaque"));badList(l);});
        check("empty_and_missing_effects_keep_defaults",()->{for(boolean empty:new boolean[]{false,true}){CompoundTag n=base();if(empty)n.put("ActiveEffects",new ListTag());EntityLootableBody b=read(n);require(b.getActiveEffects().isEmpty(),"Unexpected default effect");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy empty effects quarantined");stable(b);}});
        for(String key:new String[]{"Id","Duration","Amplifier"})for(int i=0;i<wrong.length;i++){final String k=key;final Tag raw=wrong[i];check(key+"_wrong_type_"+i,()->badEffect(k,raw));}
        for(Tag raw:new Tag[]{IntTag.valueOf(-1),IntTag.valueOf(256),LongTag.valueOf(4294967297L),DoubleTag.valueOf(1),ByteTag.valueOf((byte)201)})check("Id_invalid_or_absent_registry_"+raw,()->badEffect("Id",raw));
        check("Id_missing_retains_record",()->badEffect("Id",null));
        for(Tag raw:new Tag[]{IntTag.valueOf(-1),LongTag.valueOf(2147483648L),LongTag.valueOf(4294967496L),FloatTag.valueOf(100),DoubleTag.valueOf(Double.NaN),DoubleTag.valueOf(Double.NEGATIVE_INFINITY)})check("Duration_invalid_"+raw,()->badEffect("Duration",raw));
        for(Tag raw:new Tag[]{ShortTag.valueOf((short)-1),IntTag.valueOf(128),IntTag.valueOf(256),LongTag.valueOf(4294967296L),FloatTag.valueOf(1),DoubleTag.valueOf(Double.POSITIVE_INFINITY)})check("Amplifier_invalid_"+raw,()->badEffect("Amplifier",raw));
        for(String key:new String[]{"Ambient","ShowParticles","ShowIcon"}){
            for(int i=0;i<wrong.length;i++){final String k=key;final Tag raw=wrong[i];check(key+"_wrong_type_"+i,()->badFlag(k,raw));}
            for(Tag raw:new Tag[]{ByteTag.valueOf((byte)-1),IntTag.valueOf(2),LongTag.valueOf(256),FloatTag.valueOf(1),DoubleTag.valueOf(Double.NaN)})check(key+"_invalid_"+raw,()->badFlag(key,raw));
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
        check("native_icon_flag_survives_without_quarantine",()->{CompoundTag e=effect();e.putBoolean("ShowIcon",false);EntityLootableBody b=read(with(e));require(!b.getEffect(MobEffects.NIGHT_VISION).showIcon(),"Native icon visibility changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Native icon visibility quarantined");stable(b);});
        check("missing_icon_flag_follows_particle_default",()->{CompoundTag e=effect();e.remove("ShowIcon");e.putBoolean("ShowParticles",false);EntityLootableBody b=read(with(e));require(!b.getEffect(MobEffects.NIGHT_VISION).showIcon(),"Missing native icon default changed");stable(b);});
        check("metadata_only_does_not_prevent_empty_decay",EffectHurtChecks::empty);
        try(PrintWriter out=new PrintWriter(new File(folder,"effect-hurt-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        require(failures==0,"Effect/hurt regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_EFFECT_HURT_PASS checks="+results.size());
    }
    private static void check(String name,Check test){try{test.run();results.add("PASS "+name);System.out.println("EFFECT_HURT_PASS "+name);}catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("EFFECT_HURT_FAIL "+name+": "+e);e.printStackTrace();}}
    private static void require(boolean c,String why){RuntimeChecks.require(c,why);}
    private static CompoundTag saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static CompoundTag base(){EntityLootableBody b=new EntityLootableBody(world);b.setPos(160.5,80,160.5);b.setNoGravity(true);b.setItem(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(b);return saved(b);}
    private static EntityLootableBody read(CompoundTag n){CompoundTag before=n.copy();EntityLootableBody b=new EntityLootableBody(world);b.load(n);require(NameLifetimeChecks.same(before,n),"Input mutated");return b;}
    static CompoundTag effect(MobEffect p,int duration,int amplifier){return new MobEffectInstance(p,duration,amplifier,false,true).save(new CompoundTag());}
    private static CompoundTag effect(){return effect(MobEffects.NIGHT_VISION,200,1);}
    private static CompoundTag with(CompoundTag e){CompoundTag n=base();n.put("ActiveEffects",NbtRecoveryChecks.list(e));return n;}
    private static void stable(EntityLootableBody b){CompoundTag a=saved(b),z=saved(read(a));for(String k:new String[]{"Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY,"ActiveEffects","HurtTime","Health","AbsorptionAmount","Attributes"})require(NameLifetimeChecks.same(a.get(k),z.get(k)),"Round trip changed "+k);require(RuntimeChecks.sameStack(b.getItem(6),NbtRecoveryChecks.item(120,17)),"Complete known inventory changed");}
    private static void badHurt(Tag raw){CompoundTag n=base();n.put("HurtTime",raw.copy());EntityLootableBody b=read(n);require(b.hurtTime==0 && b.isAlive(),"Bad hurt time activated");NameLifetimeChecks.retained(b,"HurtTime",raw);stable(b);}
    private static void validHurt(Tag raw){CompoundTag n=base();n.put("HurtTime",raw);EntityLootableBody b=read(n);int v=((NumericTag)raw).getAsInt();require(b.hurtTime==v,"Valid countdown narrowed");stable(b);b.baseTick();require(b.hurtTime==v-1,"Countdown no longer ticks");}
    private static void badList(Tag raw){CompoundTag n=base();n.put("ActiveEffects",raw.copy());EntityLootableBody b=read(n);require(b.getActiveEffects().isEmpty(),"Bad effect list activated");NameLifetimeChecks.retained(b,"ActiveEffects",raw);stable(b);}
    private static void badEffect(String key,Tag raw){CompoundTag e=effect();if(raw==null)e.remove(key);else e.put(key,raw.copy());EntityLootableBody b=read(with(e));require(b.getActiveEffects().isEmpty(),"Malformed effect activated");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void badFlag(String key,Tag raw){CompoundTag e=effect();e.put(key,raw.copy());EntityLootableBody b=read(with(e));MobEffectInstance p=b.getEffect(MobEffects.NIGHT_VISION);require(p!=null && p.getDuration()==200 && p.getAmplifier()==1,"Bad optional flag lost valid effect");require(!p.isAmbient() && p.isVisible(),"Bad optional flag did not use defaults");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void numeric(){for(int type=0;type<4;type++){CompoundTag e=effect();Tag[] ids={ByteTag.valueOf((byte)16),ShortTag.valueOf((short)16),IntTag.valueOf(16),LongTag.valueOf(16)};Tag[] one={ByteTag.valueOf((byte)1),ShortTag.valueOf((short)1),IntTag.valueOf(1),LongTag.valueOf(1)};e.put("Id",ids[type]);e.put("Duration",one[type]);e.put("Amplifier",one[type].copy());e.put("Ambient",one[type].copy());e.put("ShowParticles",one[type].copy());EntityLootableBody b=read(with(e));MobEffectInstance p=b.getEffect(MobEffects.NIGHT_VISION);require(p!=null && p.getDuration()==1 && p.getAmplifier()==1 && p.isAmbient() && p.isVisible(),"Lossless integral effect changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid numbers quarantined");stable(b);}}
    private static void missingOptional(){CompoundTag e=effect();for(String k:new String[]{"Duration","Amplifier","Ambient","ShowParticles"})e.remove(k);EntityLootableBody b=read(with(e));MobEffectInstance p=b.getEffect(MobEffects.NIGHT_VISION);require(p!=null && p.getDuration()==0 && p.getAmplifier()==0 && !p.isAmbient() && p.isVisible(),"Legacy defaults changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Missing optional fields quarantined");stable(b);}
    private static void validDuration(int d){EntityLootableBody b=read(with(effect(MobEffects.NIGHT_VISION,d,0)));b.baseTick();MobEffectInstance p=b.getEffect(MobEffects.NIGHT_VISION);require(d==0?p==null:p!=null && p.getDuration()==d-1,"Valid duration tick/expiry changed");stable(b);}
    private static void signedAmplifiers(){for(int a:new int[]{-128,-1,0,127}){EntityLootableBody b=read(with(effect(MobEffects.NIGHT_VISION,200,a)));require(b.getEffect(MobEffects.NIGHT_VISION).getAmplifier()==Math.max(0,a),"Native 1.11 byte amplifier clamping changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Canonical signed byte quarantined");stable(b);}}
    private static void curativeItems(){CompoundTag e=effect();CompoundTag item=NbtRecoveryChecks.item(120,7).save(new CompoundTag());ListTag list=new ListTag();list.add(item);e.put("CurativeItems",list);EntityLootableBody b=read(with(e));require(b.getEffect(MobEffects.NIGHT_VISION).getCurativeItems().size()==1 && RuntimeChecks.sameStack(b.getEffect(MobEffects.NIGHT_VISION).getCurativeItems().get(0),ItemStack.of(item)),"Forge curative item NBT changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy Forge curative items quarantined");stable(b);}
    private static void badCurativeItems(){CompoundTag e=effect();e.putString("CurativeItems","opaque");EntityLootableBody b=read(with(e));NameLifetimeChecks.retained(b,"ActiveEffect",e);require(!b.getEffect(MobEffects.NIGHT_VISION).getCurativeItems().isEmpty(),"Malformed curative list did not use native defaults");stable(b);}
    private static void extendedId(){for(boolean wide:new boolean[]{false,true}){CompoundTag e=effect(extended,200,1);if(wide)e.putInt("Id",200);EntityLootableBody b=read(with(e));require(b.getEffect(extended)!=null && b.getEffect(extended).getDuration()==200,"Registered unsigned ID changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid extended ID quarantined");stable(b);}}
    private static void alias(){CompoundTag first=effect(extended,200,1),second=effect(extended,900,3),n=base();second.putLong("Id",200);n.put("ActiveEffects",NbtRecoveryChecks.list(first,second));EntityLootableBody b=read(n);require(b.getEffect(extended).getDuration()==200,"Numeric alias bypassed first valid effect");NameLifetimeChecks.retained(b,"ActiveEffect",second);stable(b);}
    private static void validTail(){CompoundTag bad=effect();bad.putString("Duration","opaque");CompoundTag n=base();n.put("ActiveEffects",NbtRecoveryChecks.list(bad,effect()));EntityLootableBody b=read(n);require(b.getEffect(MobEffects.NIGHT_VISION).getDuration()==200,"Bad first effect hid valid tail");NameLifetimeChecks.retained(b,"ActiveEffect",bad);stable(b);}
    private static void duplicate(){CompoundTag first=effect(),second=effect(MobEffects.NIGHT_VISION,900,3),n=base();n.put("ActiveEffects",NbtRecoveryChecks.list(first,second));EntityLootableBody b=read(n);require(b.getEffect(MobEffects.NIGHT_VISION).getDuration()==200,"Duplicate overwrote first effect");NameLifetimeChecks.retained(b,"ActiveEffect",second);stable(b);}
    private static void unknown(){CompoundTag e=effect();e.putString("Opaque","retain effect extension");EntityLootableBody b=read(with(e));require(b.getActiveEffects().size()==1,"Unknown sibling lost understood effect");NameLifetimeChecks.retained(b,"ActiveEffect",e);stable(b);}
    private static void repeated(){EntityLootableBody b=read(with(effect(MobEffects.INVISIBILITY,200,0)));b.baseTick();require(b.isInvisible(),"Fixture not invisible");b.load(base());require(b.getActiveEffects().isEmpty() && !b.isInvisible(),"Previous effects or render state survived read");stable(b);}
    private static void attributes(){EntityLootableBody original=new EntityLootableBody(world);original.setItem(6,NbtRecoveryChecks.item(120,17));RuntimeChecks.disableVacuum(original);original.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST,200,1));original.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED,200,0));original.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,200,0));original.setHealth(original.getMaxHealth());CompoundTag n=saved(original);EntityLootableBody b=read(n);require(b.getMaxHealth()==28 && b.getHealth()==28 && b.getAbsorptionAmount()==4 && b.getActiveEffects().size()==3,"Valid potion health/attributes/absorption changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy potion fields quarantined");stable(b);for(int i=0;i<200;i++)b.baseTick();require(b.getActiveEffects().isEmpty() && b.getMaxHealth()==20 && b.getAbsorptionAmount()==0 && b.getHealth()<=20,"Expiry did not clean up normal modifiers");}
    private static void repeatedAttributes(){EntityLootableBody original=new EntityLootableBody(world);original.addEffect(new MobEffectInstance(MobEffects.HEALTH_BOOST,200,1));EntityLootableBody b=read(saved(original));b.load(base());require(b.getActiveEffects().isEmpty() && b.getMaxHealth()==20,"Obsolete effects/modifiers survived read");stable(b);}
    private static void reusedOutput(){EntityLootableBody b=read(base());CompoundTag n=with(effect());b.addAdditionalSaveData(n);require(!n.contains("ActiveEffects"),"Writer retained obsolete ActiveEffects");stable(b);}
    private static void inventory(){CompoundTag n=base(),missing=NbtRecoveryChecks.entry(121,11,7);missing.putString("id","absentfixture:missing");n.getList("Equipment",10).add(missing);CompoundTag raw=malformedFields();for(String k:raw.getAllKeys())n.put(k,raw.get(k).copy());EntityLootableBody b=read(n);require(b.isAlive(),"Bad fields made corpse inaccessible");NbtRecoveryChecks.retained(b,missing);verifyWorldFields(b);stable(b);stable(read(saved(b)));}
    private static void empty(){CompoundTag n=base();n.put("Equipment",new ListTag());n.putString("HurtTime","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Metadata counts as inventory");NameLifetimeChecks.retained(b,"HurtTime",n.get("HurtTime"));}
    static CompoundTag malformedFields(){CompoundTag n=new CompoundTag();n.putLong("HurtTime",65537);CompoundTag bad=effect();bad.putString("Duration","opaque saved duration");CompoundTag unknown=effect();unknown.putInt("Id",250);unknown.putString("Opaque","missing effect data");n.put("ActiveEffects",NbtRecoveryChecks.list(bad,unknown));return n;}
    static void verifyWorldFields(EntityLootableBody b){require(b.hurtTime==0 && b.getActiveEffects().isEmpty(),"Malformed disk effect/hurt state activated");CompoundTag raw=malformedFields();NameLifetimeChecks.retained(b,"HurtTime",raw.get("HurtTime"));ListTag list=raw.getList("ActiveEffects",10);for(int i=0;i<list.size();i++)NameLifetimeChecks.retained(b,"ActiveEffect",list.getCompound(i));}
}
