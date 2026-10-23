package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.*;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;

/** Real legacy rotation/landing state, complete inventory and immutable source fixtures. */
final class RotationFallChecks {
    private interface Check {void run() throws Exception;}
    private static WorldServer world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    static void run(MinecraftServer server,WorldServer target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        INBTBase[] wrong={new NBTTagString("opaque"),new NBTTagCompound(),new NBTTagByteArray(new byte[]{1,2}),new NBTTagIntArray(new int[]{1,2}),new NBTTagList()};
        for(int i=0;i<wrong.length;i++){final INBTBase raw=wrong[i];check("FallDistance_wrong_type_"+i,()->badFall(raw));}
        for(int i=0;i<wrong.length;i++){final INBTBase raw=wrong[i] instanceof NBTTagList?new NBTTagFloat(Float.NaN):wrong[i];check("Rotation_wrong_root_type_"+i,()->badRotation(raw));}
        for(INBTBase raw:new INBTBase[]{new NBTTagString("opaque"),new NBTTagCompound(),new NBTTagByteArray(new byte[]{1})})check("Rotation_wrong_element_type_"+raw.getId(),()->{NBTTagList l=new NBTTagList();l.add(raw.copy());l.add(raw.copy());badRotation(l);});
        for(int count:new int[]{0,1,3})check("Rotation_wrong_length_"+count,()->length(count));
        for(int axis=0;axis<2;axis++)for(double value:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,Double.MAX_VALUE,-Double.MAX_VALUE,1e-300,-1e-300}){final int a=axis;check("Rotation_bad_component_"+axis+"_"+value,()->component(a,value));}
        for(INBTBase raw:new INBTBase[]{new NBTTagFloat(Float.NaN),new NBTTagFloat(Float.NEGATIVE_INFINITY),new NBTTagFloat(Float.POSITIVE_INFINITY),new NBTTagDouble(Double.NaN),new NBTTagDouble(Double.NEGATIVE_INFINITY),new NBTTagDouble(Double.POSITIVE_INFINITY),new NBTTagInt(-1),new NBTTagFloat(-.25F),new NBTTagDouble(-Double.MAX_VALUE),new NBTTagDouble(Double.MAX_VALUE),new NBTTagDouble(1e-300)})check("FallDistance_invalid_"+raw,()->badFall(raw));
        for(int type=0;type<6;type++){final int t=type;check("valid_rotation_numeric_type_"+type,()->numericRotation(t));check("valid_fall_numeric_type_"+type,()->numericFall(t));}
        for(float yaw:new float[]{0F,-0F,37.25F,-37.25F,359.75F,-359.75F,360F,-360F,720.25F,-720.25F,Float.MAX_VALUE,-Float.MAX_VALUE})check("valid_yaw_orientation_and_derived_angles_"+Float.floatToIntBits(yaw),()->validYaw(yaw));
        for(float pitch:new float[]{-90,90,720,-720,Float.MAX_VALUE,-Float.MAX_VALUE})check("valid_pitch_keeps_existing_flat_corpse_behavior_"+pitch,()->validPitch(pitch));
        for(float distance:new float[]{0F,-0F,Float.MIN_VALUE,12.5F,Float.MAX_VALUE})check("valid_representable_fall_distance_"+Float.floatToIntBits(distance),()->validFall(new NBTTagFloat(distance),distance));
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
    private static NBTTagCompound saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static NBTTagCompound base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setUserName("RotationFixture");b.setRotation(37.25F);b.fallDistance=12.5F;for(int i=0;i<48;i++)b.setInventorySlotContents(i,RuntimeChecks.stack(i));b.addItem(RuntimeChecks.stack(70));b.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(b);return saved(b);}
    private static EntityLootableBody read(NBTTagCompound n){NBTTagCompound before=n.copy();Entity e=EntityType.create(n,world);require(e instanceof EntityLootableBody,"Factory discarded corpse");require(NameLifetimeChecks.same(before,n),"Source NBT mutated");return (EntityLootableBody)e;}
    static NBTTagList rotation(float... angles){NBTTagList l=new NBTTagList();for(float a:angles)l.add(new NBTTagFloat(a));return l;}
    static NBTTagList wideRotation(double... angles){NBTTagList l=new NBTTagList();for(double a:angles)l.add(new NBTTagDouble(a));return l;}
    static void finite(EntityLootableBody b){for(float v:new float[]{b.rotationYaw,b.rotationPitch,b.prevRotationYaw,b.prevRotationPitch,b.rotationYawHead,b.prevRotationYawHead,b.renderYawOffset,b.prevRenderYawOffset,b.fallDistance})require(Float.isFinite(v),"Nonfinite rotation/derived angle/fall distance");require(b.fallDistance>=0,"Negative fall distance");}
    private static void value(float a,float z,String why){require(Float.isFinite(a) && a==z,why+" expected="+z+" actual="+a);}
    private static void angles(EntityLootableBody b,float yaw){finite(b);for(float v:new float[]{b.rotationYaw,b.prevRotationYaw,b.rotationYawHead,b.prevRotationYawHead,b.renderYawOffset,b.prevRenderYawOffset})value(v,yaw,"Yaw/derived orientation differs");value(b.rotationPitch,0,"Corpse pitch no longer flat");value(b.prevRotationPitch,0,"Previous pitch differs");}
    private static void kept(EntityLootableBody b){NBTTagCompound n=base(),a=saved(b);for(String k:new String[]{"Equipment","Aux"})require(NameLifetimeChecks.same(n.getTag(k),a.getTag(k)),"Complete inventory changed "+k);require(b.getSizeInventory()==48 && b.getCustomNameTag().equals("RotationFixture"),"Corpse identity/slots changed");}
    private static void stable(EntityLootableBody b){finite(b);kept(b);NBTTagCompound a=saved(b),z=saved(read(a));for(String k:new String[]{"Rotation","FallDistance","Pos","Motion","Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY})require(NameLifetimeChecks.same(a.getTag(k),z.getTag(k)),"Round trip changed "+k);}
    private static void badFall(INBTBase raw){NBTTagCompound n=base();n.setTag("FallDistance",raw.copy());EntityLootableBody b=read(n);value(b.fallDistance,0,"Bad fall distance activated");NameLifetimeChecks.retained(b,"FallDistance",raw);stable(b);}
    private static void badRotation(INBTBase raw){NBTTagCompound n=base();n.setTag("Rotation",raw.copy());EntityLootableBody b=read(n);angles(b,0);NameLifetimeChecks.retained(b,"Rotation",raw);stable(b);}
    private static void component(int axis,double raw){NBTTagCompound n=base();NBTTagList r=axis==0?wideRotation(raw,17):wideRotation(37.25,raw);n.setTag("Rotation",r);EntityLootableBody b=read(n);angles(b,axis==0?0:37.25F);NameLifetimeChecks.retained(b,"Rotation",r);stable(b);}
    private static void length(int count){float[] a=new float[count];for(int i=0;i<count;i++)a[i]=37.25F+i;NBTTagList r=rotation(a);NBTTagCompound n=base();n.setTag("Rotation",r);EntityLootableBody b=read(n);angles(b,count==0?0:37.25F);NameLifetimeChecks.retained(b,"Rotation",r);stable(b);}
    private static void numericRotation(int type){NBTTagList l=new NBTTagList();for(int i=0;i<2;i++){INBTBase[] n={new NBTTagByte((byte)37),new NBTTagShort((short)37),new NBTTagInt(37),new NBTTagLong(37),new NBTTagFloat(37.25F),new NBTTagDouble(37.25)};l.add(n[type]);}NBTTagCompound n=base();n.setTag("Rotation",l);EntityLootableBody b=read(n);angles(b,type<4?37:37.25F);require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid numeric rotation quarantined");stable(b);}
    private static void numericFall(int type){INBTBase[] n={new NBTTagByte((byte)12),new NBTTagShort((short)12),new NBTTagInt(12),new NBTTagLong(12),new NBTTagFloat(12.5F),new NBTTagDouble(12.5)};validFall(n[type],type<4?12:12.5F);}
    private static void validFall(INBTBase raw,float expected){NBTTagCompound n=base();n.setTag("FallDistance",raw);EntityLootableBody b=read(n);value(b.fallDistance,expected,"Valid fall distance changed");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid distance quarantined");stable(b);}
    private static void validYaw(float yaw){NBTTagCompound n=base();n.setTag("Rotation",rotation(yaw,17));EntityLootableBody b=read(n);angles(b,yaw%360F);require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid yaw quarantined");stable(b);}
    private static void validPitch(float pitch){NBTTagCompound n=base();n.setTag("Rotation",rotation(37.25F,pitch));EntityLootableBody b=read(n);angles(b,37.25F);require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Finite pitch quarantined despite existing flattening");stable(b);}
    private static void missing(){NBTTagCompound n=base();n.removeTag("Rotation");n.removeTag("FallDistance");EntityLootableBody b=read(n);angles(b,0);value(b.fallDistance,0,"Missing distance not zero");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Missing fields invented raw data");stable(b);}
    private static void repeated(){EntityLootableBody b=read(base());b.rotationYaw=Float.NaN;b.rotationPitch=Float.POSITIVE_INFINITY;b.prevRotationYaw=Float.NEGATIVE_INFINITY;b.prevRotationPitch=Float.NaN;b.rotationYawHead=Float.NaN;b.prevRotationYawHead=Float.NaN;b.renderYawOffset=Float.POSITIVE_INFINITY;b.prevRenderYawOffset=Float.NEGATIVE_INFINITY;b.fallDistance=Float.NaN;NBTTagCompound n=base();n.removeTag("Rotation");n.removeTag("FallDistance");b.read(n);angles(b,0);value(b.fallDistance,0,"Repeated read kept stale fall distance");stable(b);}
    private static void healthy(){EntityLootableBody b=read(base());kept(b);require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Healthy save gained recovery records");require(LootableBodies.corpseHP==20 && !LootableBodies.hurtByEnvironment,"Defaults changed");stable(b);}
    private static void chunkAdmission(){NBTTagCompound n=base();n.setTag("Pos",PositionMotionChecks.vector(Double.NaN,80,160.5));n.setTag("Rotation",wideRotation(37.25,Double.NaN));n.setDouble("FallDistance",Double.NaN);NBTTagCompound before=n.copy();Entity e=AnvilChunkLoader.readChunkEntity(n,world,RuntimeChecks.emptyChunk(world,10,10));require(e instanceof EntityLootableBody && !e.removed,"Chunk lost malformed corpse");EntityLootableBody b=(EntityLootableBody)e;require(b.posX==168.5 && b.posY==80 && b.posZ==160.5,"Position recovery changed");angles(b,37.25F);value(b.fallDistance,0,"Bad distance at admission");require(NameLifetimeChecks.same(before,n),"Chunk mutated raw input");stable(b);}
    private static void zeroHealth(){NBTTagCompound n=base();n.setTag("Rotation",rotation(Float.NaN,Float.NaN));n.setDouble("FallDistance",Double.NaN);n.setFloat("Health",0);EntityLootableBody b=read(n);require(b.getHealth()==0 && !b.isAlive(),"Rotation/fall repair healed zero health");angles(b,0);kept(b);}
    private static void inventory(){NBTTagCompound n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.setString("id","absentfixture:missing");n.getList("Equipment",10).add(raw);NBTTagCompound fields=malformedFields();for(String k:fields.keySet())n.setTag(k,fields.getTag(k).copy());EntityLootableBody b=read(n);require(b.isAlive(),"Bad fields made inventory inaccessible");verifyWorldFields(b);NbtRecoveryChecks.retained(b,raw);stable(b);NbtRecoveryChecks.retained(read(saved(b)),raw);}
    private static void empty(){NBTTagCompound n=base();n.setTag("Equipment",new NBTTagList());n.removeTag("Aux");n.setString("Rotation","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Rotation evidence counts as items");NameLifetimeChecks.retained(b,"Rotation",n.getTag("Rotation"));}
    private static void landing(boolean damage,boolean bad){LootableBodies.hurtByEnvironment=damage;LootableBodies.completelyInvulnerable=!damage;world.getChunk(10,10);world.setBlockState(new BlockPos(160,79,160),Blocks.STONE.getDefaultState());NBTTagCompound n=base();if(bad)n.setDouble("FallDistance",Double.NaN);EntityLootableBody b=read(n);if(bad)value(b.fallDistance,0,"Malformed distance entered landing");b.move(net.minecraft.entity.MoverType.SELF,0,-1,0);value(b.fallDistance,0,"Landing failed to reset distance");require(damage && !bad?b.getHealth()>0 && b.getHealth()<20:b.getHealth()==20,"Landing changed configured damage behavior");kept(b);}
    static NBTTagCompound malformedFields(){NBTTagCompound n=new NBTTagCompound();n.setTag("Rotation",wideRotation(37,Double.NaN));n.setDouble("FallDistance",Double.NaN);return n;}
    static void verifyWorldFields(EntityLootableBody b){finite(b);value(b.rotationYaw,37,"Valid disk yaw lost");value(b.rotationPitch,0,"Disk corpse pitch changed");value(b.fallDistance,0,"Disk fall distance unsafe");NBTTagCompound raw=malformedFields();NameLifetimeChecks.retained(b,"Rotation",raw.getTag("Rotation"));NameLifetimeChecks.retained(b,"FallDistance",raw.getTag("FallDistance"));}
}
