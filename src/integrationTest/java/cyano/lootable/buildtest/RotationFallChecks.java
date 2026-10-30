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
import net.minecraft.core.PositionImpl;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkStorage;

/** Real legacy rotation/landing state, complete inventory and immutable source fixtures. */
final class RotationFallChecks {
    private interface Check {void run() throws Exception;}
    private static ServerLevel world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    static void run(MinecraftServer server,ServerLevel target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        Tag[] wrong={StringTag.valueOf("opaque"),new CompoundTag(),new ByteArrayTag(new byte[]{1,2}),new IntArrayTag(new int[]{1,2}),new ListTag()};
        for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i];check("FallDistance_wrong_type_"+i,()->badFall(raw));}
        for(int i=0;i<wrong.length;i++){final Tag raw=wrong[i] instanceof ListTag?FloatTag.valueOf(Float.NaN):wrong[i];check("Rotation_wrong_root_type_"+i,()->badRotation(raw));}
        for(Tag raw:new Tag[]{StringTag.valueOf("opaque"),new CompoundTag(),new ByteArrayTag(new byte[]{1})})check("Rotation_wrong_element_type_"+raw.getId(),()->{ListTag l=new ListTag();l.add(raw.copy());l.add(raw.copy());badRotation(l);});
        for(int count:new int[]{0,1,3})check("Rotation_wrong_length_"+count,()->length(count));
        for(int axis=0;axis<2;axis++)for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.MAX_VALUE,-Double.MAX_VALUE,1e-300,-1e-300}){final int a=axis;check("Rotation_bad_component_"+axis+"_"+value,()->component(a,value));}
        for(Tag raw:new Tag[]{FloatTag.valueOf(Float.NaN),FloatTag.valueOf(Float.NEGATIVE_INFINITY),FloatTag.valueOf(Float.POSITIVE_INFINITY),DoubleTag.valueOf(Double.NaN),DoubleTag.valueOf(Double.NEGATIVE_INFINITY),DoubleTag.valueOf(Double.POSITIVE_INFINITY),IntTag.valueOf(-1),FloatTag.valueOf(-.25F),DoubleTag.valueOf(-Double.MAX_VALUE),DoubleTag.valueOf(Double.MAX_VALUE),DoubleTag.valueOf(1e-300)})check("FallDistance_invalid_"+raw,()->badFall(raw));
        for(int type=0;type<6;type++){final int t=type;check("valid_rotation_numeric_type_"+type,()->numericRotation(t));check("valid_fall_numeric_type_"+type,()->numericFall(t));}
        for(float yaw:new float[]{0F,-0F,37.25F,-37.25F,359.75F,-359.75F,360F,-360F,720.25F,-720.25F,Float.MAX_VALUE,-Float.MAX_VALUE})check("valid_yaw_orientation_and_derived_angles_"+Float.floatToIntBits(yaw),()->validYaw(yaw));
        for(float pitch:new float[]{-90,90,720,-720,Float.MAX_VALUE,-Float.MAX_VALUE})check("valid_pitch_keeps_existing_flat_corpse_behavior_"+pitch,()->validPitch(pitch));
        for(float distance:new float[]{0F,-0F,Float.MIN_VALUE,12.5F,Float.MAX_VALUE})check("valid_representable_fall_distance_"+Float.floatToIntBits(distance),()->validFall(FloatTag.valueOf(distance),distance));
        check("missing_rotation_and_fall_default_to_zero",RotationFallChecks::missing);
        check("repeated_read_resets_previous_head_body_and_fall_state",RotationFallChecks::repeated);
        check("healthy_saved_layout_and_defaults_unchanged",RotationFallChecks::healthy);
        check("malformed_angles_and_position_keep_containing_chunk_inventory",RotationFallChecks::chunkAdmission);
        check("malformed_fields_do_not_heal_valid_zero_health",RotationFallChecks::zeroHealth);
        check("malformed_fields_keep_complete_and_unresolved_inventory",RotationFallChecks::inventory);
        check("metadata_only_does_not_count_as_items",RotationFallChecks::empty);
        check("landing_keeps_default_environmental_damage_disabled",()->landing(false,false));
        check("landing_keeps_enabled_environmental_damage",()->landing(true,false));
        check("repaired_fall_distance_does_not_invent_landing_damage",()->landing(true,true));
        try(PrintWriter out=new PrintWriter(new File(folder,"rotation-fall-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        require(failures==0,"Rotation/fall regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_ROTATION_FALL_PASS checks="+results.size());
    }
    private static void check(String name,Check test){boolean env=LootableBodies.hurtByEnvironment,inv=LootableBodies.completelyInvulnerable;try{test.run();results.add("PASS "+name);System.out.println("ROTATION_FALL_PASS "+name);}catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("ROTATION_FALL_FAIL "+name+": "+e);e.printStackTrace();}finally{LootableBodies.hurtByEnvironment=env;LootableBodies.completelyInvulnerable=inv;}}
    private static void require(boolean c,String why){RuntimeChecks.require(c,why);}
    private static CompoundTag saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static CompoundTag base(){EntityLootableBody b=new EntityLootableBody(world);b.setPos(160.5,80,160.5);b.setNoGravity(true);b.setUserName("RotationFixture");b.setRotation(37.25F);b.fallDistance=12.5F;for(int i=0;i<48;i++)b.setItem(i,RuntimeChecks.stack(i));b.addItem(RuntimeChecks.stack(70));b.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(b);return saved(b);}
    private static EntityLootableBody read(CompoundTag n){CompoundTag before=n.copy();Entity e=FixtureApi.loadEntity(n,world);require(e instanceof EntityLootableBody,"Factory discarded corpse");require(NameLifetimeChecks.same(before,n),"Source NBT mutated");return (EntityLootableBody)e;}
    static ListTag rotation(float... angles){ListTag l=new ListTag();for(float a:angles)l.add(FloatTag.valueOf(a));return l;}
    static ListTag wideRotation(double... angles){ListTag l=new ListTag();for(double a:angles)l.add(DoubleTag.valueOf(a));return l;}
    static void finite(EntityLootableBody b){for(float v:new float[]{b.getYRot(),b.getXRot(),b.yRotO,b.xRotO,b.yHeadRot,b.yHeadRotO,b.yBodyRot,b.yBodyRotO,b.fallDistance})require(Float.isFinite(v),"Nonfinite rotation/derived angle/fall distance");require(b.fallDistance>=0,"Negative fall distance");}
    private static void value(float a,float z,String why){require(Float.isFinite(a) && a==z,why+" expected="+z+" actual="+a);}
    private static void angles(EntityLootableBody b,float yaw){finite(b);for(float v:new float[]{b.getYRot(),b.yRotO,b.yHeadRot,b.yHeadRotO,b.yBodyRot,b.yBodyRotO})value(v,yaw,"Yaw/derived orientation differs");value(b.getXRot(),0,"Corpse pitch no longer flat");value(b.xRotO,0,"Previous pitch differs");}
    private static void kept(EntityLootableBody b){CompoundTag n=base(),a=saved(b);for(String k:new String[]{"Equipment","Aux"})require(NameLifetimeChecks.same(n.get(k),a.get(k)),"Complete inventory changed "+k);require(b.getContainerSize()==48 && b.getCustomNameTag().equals("RotationFixture"),"Corpse identity/slots changed");}
    private static void stable(EntityLootableBody b){finite(b);kept(b);CompoundTag a=saved(b),z=saved(read(a));for(String k:new String[]{"Rotation","FallDistance","Pos","Motion","Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY})require(NameLifetimeChecks.same(a.get(k),z.get(k)),"Round trip changed "+k);}
    private static void badFall(Tag raw){CompoundTag n=base();n.put("FallDistance",raw.copy());EntityLootableBody b=read(n);value(b.fallDistance,0,"Bad fall distance activated");NameLifetimeChecks.retained(b,"FallDistance",raw);stable(b);}
    private static void badRotation(Tag raw){CompoundTag n=base();n.put("Rotation",raw.copy());EntityLootableBody b=read(n);angles(b,0);NameLifetimeChecks.retained(b,"Rotation",raw);stable(b);}
    private static void component(int axis,double raw){CompoundTag n=base();ListTag r=axis==0?wideRotation(raw,17):wideRotation(37.25,raw);n.put("Rotation",r);EntityLootableBody b=read(n);angles(b,axis==0?0:37.25F);NameLifetimeChecks.retained(b,"Rotation",r);stable(b);}
    private static void length(int count){float[] a=new float[count];for(int i=0;i<count;i++)a[i]=37.25F+i;ListTag r=rotation(a);CompoundTag n=base();n.put("Rotation",r);EntityLootableBody b=read(n);angles(b,count==0?0:37.25F);NameLifetimeChecks.retained(b,"Rotation",r);stable(b);}
    private static void numericRotation(int type){ListTag l=new ListTag();for(int i=0;i<2;i++){Tag[] n={ByteTag.valueOf((byte)37),ShortTag.valueOf((short)37),IntTag.valueOf(37),LongTag.valueOf(37),FloatTag.valueOf(37.25F),DoubleTag.valueOf(37.25)};l.add(n[type]);}CompoundTag n=base();n.put("Rotation",l);EntityLootableBody b=read(n);angles(b,type<4?37:37.25F);require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid numeric rotation quarantined");stable(b);}
    private static void numericFall(int type){Tag[] n={ByteTag.valueOf((byte)12),ShortTag.valueOf((short)12),IntTag.valueOf(12),LongTag.valueOf(12),FloatTag.valueOf(12.5F),DoubleTag.valueOf(12.5)};validFall(n[type],type<4?12:12.5F);}
    private static void validFall(Tag raw,float expected){CompoundTag n=base();n.put("FallDistance",raw);EntityLootableBody b=read(n);value(b.fallDistance,expected,"Valid fall distance changed");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid distance quarantined");stable(b);}
    private static void validYaw(float yaw){CompoundTag n=base();n.put("Rotation",rotation(yaw,17));EntityLootableBody b=read(n);angles(b,yaw%360F);require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid yaw quarantined");stable(b);}
    private static void validPitch(float pitch){CompoundTag n=base();n.put("Rotation",rotation(37.25F,pitch));EntityLootableBody b=read(n);angles(b,37.25F);require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Finite pitch quarantined despite existing flattening");stable(b);}
    private static void missing(){CompoundTag n=base();n.remove("Rotation");n.remove("FallDistance");EntityLootableBody b=read(n);angles(b,0);value(b.fallDistance,0,"Missing distance not zero");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Missing fields invented raw data");stable(b);}
    private static void repeated(){EntityLootableBody b=read(base());net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(Entity.class,b,Float.NaN,"f_19857_");net.minecraftforge.fml.util.ObfuscationReflectionHelper.setPrivateValue(Entity.class,b,Float.POSITIVE_INFINITY,"f_19858_");b.yRotO=Float.NEGATIVE_INFINITY;b.xRotO=Float.NaN;b.yHeadRot=Float.NaN;b.yHeadRotO=Float.NaN;b.yBodyRot=Float.POSITIVE_INFINITY;b.yBodyRotO=Float.NEGATIVE_INFINITY;b.fallDistance=Float.NaN;CompoundTag n=base();n.remove("Rotation");n.remove("FallDistance");b.load(n);angles(b,0);value(b.fallDistance,0,"Repeated read kept stale fall distance");stable(b);}
    private static void healthy(){EntityLootableBody b=read(base());kept(b);require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Healthy save gained recovery records");require(LootableBodies.corpseHP==20 && !LootableBodies.hurtByEnvironment,"Defaults changed");stable(b);}
    private static void chunkAdmission(){CompoundTag n=base();n.put("Pos",PositionMotionChecks.vector(Double.NaN,80,160.5));n.put("Rotation",wideRotation(37.25,Double.NaN));n.putDouble("FallDistance",Double.NaN);CompoundTag before=n.copy();Entity e=FixtureApi.readChunkEntity(n,world,RuntimeChecks.emptyChunk(world,10,10));require(e instanceof EntityLootableBody && !e.isRemoved(),"Chunk lost malformed corpse");EntityLootableBody b=(EntityLootableBody)e;require(b.getX()==168.5 && b.getY()==80 && b.getZ()==160.5,"Position recovery changed");angles(b,37.25F);value(b.fallDistance,0,"Bad distance at admission");require(NameLifetimeChecks.same(before,n),"Chunk mutated raw input");stable(b);}
    private static void zeroHealth(){CompoundTag n=base();n.put("Rotation",rotation(Float.NaN,Float.NaN));n.putDouble("FallDistance",Double.NaN);n.putFloat("Health",0);EntityLootableBody b=read(n);require(b.getHealth()==0 && !b.isAlive(),"Rotation/fall repair healed zero health");angles(b,0);kept(b);}
    private static void inventory(){CompoundTag n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.putString("id","absentfixture:missing");n.getList("Equipment",10).add(raw);CompoundTag fields=malformedFields();for(String k:fields.getAllKeys())n.put(k,fields.get(k).copy());EntityLootableBody b=read(n);require(b.isAlive(),"Bad fields made inventory inaccessible");verifyWorldFields(b);NbtRecoveryChecks.retained(b,raw);stable(b);NbtRecoveryChecks.retained(read(saved(b)),raw);}
    private static void empty(){CompoundTag n=base();n.put("Equipment",new ListTag());n.remove("Aux");n.putString("Rotation","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Rotation evidence counts as items");NameLifetimeChecks.retained(b,"Rotation",n.get("Rotation"));}
    private static void landing(boolean damage,boolean bad){LootableBodies.hurtByEnvironment=damage;LootableBodies.completelyInvulnerable=!damage;world.getChunk(10,10);world.setBlockAndUpdate(new BlockPos(160,79,160),Blocks.STONE.defaultBlockState());CompoundTag n=base();if(bad)n.putDouble("FallDistance",Double.NaN);EntityLootableBody b=read(n);if(bad)value(b.fallDistance,0,"Malformed distance entered landing");b.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(0,-1,0));value(b.fallDistance,0,"Landing failed to reset distance");require(damage && !bad?b.getHealth()>0 && b.getHealth()<20:b.getHealth()==20,"Landing changed configured damage behavior");kept(b);}
    static CompoundTag malformedFields(){CompoundTag n=new CompoundTag();n.put("Rotation",wideRotation(37,Double.NaN));n.putDouble("FallDistance",Double.NaN);return n;}
    static void verifyWorldFields(EntityLootableBody b){finite(b);value(b.getYRot(),37,"Valid disk yaw lost");value(b.getXRot(),0,"Disk corpse pitch changed");value(b.fallDistance,0,"Disk fall distance unsafe");CompoundTag raw=malformedFields();NameLifetimeChecks.retained(b,"Rotation",raw.get("Rotation"));NameLifetimeChecks.retained(b,"FallDistance",raw.get("FallDistance"));}
}
