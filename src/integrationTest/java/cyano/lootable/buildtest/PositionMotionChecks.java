package cyano.lootable.buildtest;
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
import net.minecraft.dispenser.Position;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ChunkLoader;

/** Validate before vanilla builds geometry, then exercise actual chunk admission. */
final class PositionMotionChecks {
    private interface Check {void run() throws Exception;}
    private static ServerWorld world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    static void run(MinecraftServer server,ServerWorld target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        INBT[] wrong={new StringNBT("opaque"),new CompoundNBT(),new ByteArrayNBT(new byte[]{1,2,3}),new IntArrayNBT(new int[]{1,2,3}),new DoubleNBT(Double.NaN)};
        for(String key:new String[]{"Pos","Motion"}) {
            for(int i=0;i<wrong.length;i++){final INBT raw=wrong[i];check(key+"_wrong_root_type_"+i,()->badVector(key,raw));}
            for(INBT raw:new INBT[]{new StringNBT("opaque"),new CompoundNBT(),new ByteArrayNBT(new byte[]{1})})check(key+"_wrong_element_type_"+raw.getId(),()->{ListNBT list=new ListNBT();for(int i=0;i<3;i++)list.add(raw.copy());badVector(key,list);});
            for(int count:new int[]{0,1,2,4})check(key+"_wrong_length_"+count,()->length(key,count));
            for(int axis=0;axis<3;axis++)for(double v:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY}){final int a=axis;check(key+"_nonfinite_axis_"+axis+"_"+v,()->component(key,a,v));}
            for(int type=0;type<6;type++){final int t=type;check(key+"_valid_numeric_type_"+type,()->numeric(key,t));}
            check(key+"_missing_uses_safe_defaults",()->missing(key));
        }
        for(int axis=0;axis<3;axis++)for(double v:new double[]{-10.01,10.01,Double.MAX_VALUE,-Double.MAX_VALUE}){final int a=axis;check("Motion_outside_engine_limit_axis_"+axis+"_"+v,()->component("Motion",a,v));}
        for(int axis=0;axis<3;axis++)for(double v:new double[]{-30000000.01,30000000.01,Double.MAX_VALUE,-Double.MAX_VALUE}){final int a=axis;check("Pos_outside_engine_limit_axis_"+axis+"_"+v,()->component("Pos",a,v));}
        check("valid_negative_coordinates_and_high_altitude",PositionMotionChecks::signed);
        check("valid_boundary_values_preserved",PositionMotionChecks::boundaries);
        check("valid_void_position_keeps_saved_value",PositionMotionChecks::voidPosition);
        check("repeated_read_does_not_reuse_previous_geometry",PositionMotionChecks::repeated);
        check("chunk_admission_repairs_missing_horizontal_coordinates",PositionMotionChecks::chunkAdmission);
        check("chunk_admission_repairs_all_invalid_position",PositionMotionChecks::allInvalidChunk);
        check("finite_saved_X_in_wrong_containing_chunk_keeps_inventory",()->finiteMismatch(true));
        check("finite_saved_Z_in_wrong_containing_chunk_keeps_inventory",()->finiteMismatch(false));
        check("healthy_chunk_admission_and_later_movement_unchanged",PositionMotionChecks::healthyChunk);
        check("healthy_removed_entity_is_not_revived_on_chunk_entry",()->notRevived(false));
        check("valid_repeated_read_clears_previous_pending_position_repair",()->notRevived(true));
        check("position_repair_does_not_heal_saved_zero_health",PositionMotionChecks::deadHealth);
        check("geometry_metadata_alone_is_not_inventory",PositionMotionChecks::empty);
        check("unknown_items_and_complete_inventory_survive_geometry_repair",PositionMotionChecks::inventory);
        try(PrintWriter out=new PrintWriter(new File(folder,"position-motion-checks.txt"),"UTF-8")){for(String s:results)out.println(s);out.println("checks="+results.size()+" failures="+failures);}
        require(failures==0,"Position/motion regressions: "+failures+" / "+results.size());System.out.println("LOOTABLE_BODIES_POSITION_MOTION_PASS checks="+results.size());
    }
    private static void check(String name,Check test){try{test.run();results.add("PASS "+name);System.out.println("POSITION_MOTION_PASS "+name);}catch(Throwable e){failures++;results.add("FAIL "+name+": "+e);System.out.println("POSITION_MOTION_FAIL "+name+": "+e);e.printStackTrace();}}
    private static void require(boolean c,String why){RuntimeChecks.require(c,why);}
    static ListNBT vector(double... values){ListNBT l=new ListNBT();for(double v:values)l.add(new DoubleNBT(v));return l;}
    private static CompoundNBT saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static CompoundNBT base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setUserName("PositionFixture");for(int i=0;i<48;i++)b.setInventorySlotContents(i,RuntimeChecks.stack(i));b.addItem(RuntimeChecks.stack(70));b.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(b);b.setMotion(.25,b.getMotion().y,b.getMotion().z);b.setMotion(b.getMotion().x,-.5,b.getMotion().z);b.setMotion(b.getMotion().x,b.getMotion().y,.75);return saved(b);}
    private static EntityLootableBody read(CompoundNBT n){CompoundNBT before=n.copy();Entity e=FixtureApi.loadEntity(n,world);require(e instanceof EntityLootableBody,"Factory discarded corpse");require(NameLifetimeChecks.same(before,n),"Input NBT mutated");return (EntityLootableBody)e;}
    static void finite(EntityLootableBody b){for(double v:new double[]{b.posX,b.posY,b.posZ,b.getMotion().x,b.getMotion().y,b.getMotion().z,b.prevPosX,b.prevPosY,b.prevPosZ,b.lastTickPosX,b.lastTickPosY,b.lastTickPosZ})require(Double.isFinite(v),"Nonfinite geometry/derived position");AxisAlignedBB a=b.getBoundingBox();for(double v:new double[]{a.minX,a.minY,a.minZ,a.maxX,a.maxY,a.maxZ})require(Double.isFinite(v),"Nonfinite bounding box");require(Math.abs(a.minX-(b.posX-b.getWidth()/2))<1e-6 && a.minY==b.posY && Math.abs(a.maxZ-(b.posZ+b.getWidth()/2))<1e-6,"Bounding box differs from sanitized position");}
    private static double[] values(EntityLootableBody b,String key){return key.equals("Pos")?new double[]{b.posX,b.posY,b.posZ}:new double[]{b.getMotion().x,b.getMotion().y,b.getMotion().z};}
    private static double fallback(String key,int axis){return key.equals("Pos") && axis==1?64:0;}
    private static void sameValue(double a,double z,String why){require(Double.isFinite(a) && a==z,why+" expected="+z+" actual="+a);}
    private static void kept(EntityLootableBody b){CompoundNBT n=base(),a=saved(b);for(String k:new String[]{"Equipment","Aux"})require(NameLifetimeChecks.same(n.get(k),a.get(k)),"Complete inventory changed "+k);require(b.getSizeInventory()==48 && b.getCustomNameTag().equals("PositionFixture"),"Identity/default changed");}
    private static void stable(EntityLootableBody b){finite(b);kept(b);CompoundNBT a=saved(b),z=saved(read(a));for(String k:new String[]{"Pos","Motion","Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY})require(NameLifetimeChecks.same(a.get(k),z.get(k)),"Round trip changed "+k);}
    private static void badVector(String key,INBT raw){CompoundNBT n=base();n.put(key,raw.copy());EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],fallback(key,i),"Bad vector component");NameLifetimeChecks.retained(b,key,raw);stable(b);}
    private static void component(String key,int axis,double value){double[] v=key.equals("Pos")?new double[]{160.5,80,160.5}:new double[]{.25,-.5,.75};v[axis]=value;CompoundNBT n=base();ListNBT raw=vector(v);n.put(key,raw);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],i==axis?fallback(key,i):v[i],"Lost valid sibling or activated invalid component");NameLifetimeChecks.retained(b,key,raw);stable(b);}
    private static void length(String key,int count){double[] v=new double[count];for(int i=0;i<count;i++)v[i]=i+1;ListNBT raw=vector(v);CompoundNBT n=base();n.put(key,raw);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],i<count?v[i]:fallback(key,i),"Wrong-length vector lost prefix/default");NameLifetimeChecks.retained(b,key,raw);stable(b);}
    private static void numeric(String key,int type){ListNBT l=new ListNBT();for(int i=0;i<3;i++){INBT[] a={new ByteNBT((byte)(i+1)),new ShortNBT((short)(i+1)),new IntNBT(i+1),new LongNBT(i+1),new FloatNBT(i+1.25F),new DoubleNBT(i+1.25)};l.add(a[type]);}CompoundNBT n=base();n.put(key,l);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],type<4?i+1:i+1.25,"Valid numeric vector lost");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid vector quarantined");stable(b);}
    private static void missing(String key){CompoundNBT n=base();n.remove(key);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],fallback(key,i),"Missing vector default");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Missing field invented raw data");stable(b);}
    private static void signed(){CompoundNBT n=base();n.put("Pos",vector(-160.25,500.5,-160.75));n.put("Motion",vector(-2.25,.125,-.5));EntityLootableBody b=read(n);sameValue(b.posX,-160.25,"Negative X");sameValue(b.posY,500.5,"High altitude");sameValue(b.posZ,-160.75,"Negative Z");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid signed position quarantined");stable(b);}
    private static void boundaries(){CompoundNBT n=base();n.put("Pos",vector(-3e7,3e7,3e7));n.put("Motion",vector(-10,0,10));EntityLootableBody b=read(n);sameValue(b.posX,-3e7,"Boundary X");sameValue(b.posY,3e7,"Boundary Y");sameValue(b.posZ,3e7,"Boundary Z");sameValue(b.getMotion().x,-10,"Boundary motion");sameValue(b.getMotion().z,10,"Boundary motion");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Boundary quarantined");stable(b);}
    private static void voidPosition(){CompoundNBT n=base();n.put("Pos",vector(160.5,-65,160.5));EntityLootableBody b=read(n);sameValue(b.posY,-65,"Valid void position moved");require(!saved(b).contains(NameLifetimeChecks.RECOVERY),"Valid negative Y quarantined");stable(b);}
    private static void repeated(){EntityLootableBody b=read(base());b.setPosition(42,100,43);b.setMotion(4,b.getMotion().y,b.getMotion().z);b.setMotion(b.getMotion().x,5,b.getMotion().z);b.setMotion(b.getMotion().x,b.getMotion().y,6);CompoundNBT n=base();n.putString("Pos","opaque");n.remove("Motion");b.read(n);sameValue(b.posX,0,"Reused old X");sameValue(b.posY,64,"Reused old Y");sameValue(b.posZ,0,"Reused old Z");for(double v:values(b,"Motion"))sameValue(v,0,"Reused old motion");stable(b);}
    private static EntityLootableBody chunk(CompoundNBT n,int x,int z){CompoundNBT before=n.copy();Entity e=FixtureApi.readChunkEntity(n,world,RuntimeChecks.emptyChunk(world,x,z));require(e instanceof EntityLootableBody && !e.removed,"Chunk discarded or killed malformed corpse");require(NameLifetimeChecks.same(before,n),"Chunk loader mutated source");EntityLootableBody b=(EntityLootableBody)e;require(b.addedToChunk && b.chunkCoordX==x && b.chunkCoordZ==z,"Admission coordinates wrong");finite(b);return b;}
    private static void chunkAdmission(){CompoundNBT n=base();n.put("Pos",vector(Double.NaN,80,160.5));EntityLootableBody b=chunk(n,10,10);sameValue(b.posX,168.5,"Containing chunk anchor missing");sameValue(b.posZ,160.5,"Valid Z was moved");NameLifetimeChecks.retained(b,"Pos",n.get("Pos"));stable(b);}
    private static void allInvalidChunk(){CompoundNBT n=base();n.putString("Pos","opaque");EntityLootableBody b=chunk(n,-11,10);sameValue(b.posX,-167.5,"Negative containing chunk center");sameValue(b.posY,64,"Invalid Y default");sameValue(b.posZ,168.5,"Containing Z chunk center");stable(b);}
    private static void finiteMismatch(boolean x){CompoundNBT n=base();EntityLootableBody b=chunk(n,x?-11:10,x?10:11);sameValue(b.posX,x?-167.5:160.5,"Finite mismatched X");sameValue(b.posZ,x?160.5:184.5,"Finite mismatched Z");NameLifetimeChecks.retained(b,"Pos",n.get("Pos"));stable(b);}
    private static void healthyChunk(){CompoundNBT n=base();EntityLootableBody b=chunk(n,10,10);sameValue(b.posX,160.5,"Healthy chunk position moved");b.setPosition(176.5,80,160.5);RuntimeChecks.emptyChunk(world,11,10).addEntity(b);sameValue(b.posX,176.5,"Healthy later movement was anchored");require(!b.removed,"Healthy later movement killed");stable(b);}
    private static void notRevived(boolean previousInvalid){CompoundNBT n=base();if(previousInvalid)n.put("Pos",vector(Double.NaN,80,160.5));EntityLootableBody b=read(n);if(previousInvalid)b.read(base());b.removed=true;RuntimeChecks.emptyChunk(world,10,10).addEntity(b);require(b.removed && b.posX==160.5,"Position hook revived ordinary removal or kept an obsolete repair");b.removed=false;stable(b);}
    private static void deadHealth(){CompoundNBT n=base();n.put("Pos",vector(Double.NaN,80,160.5));n.putFloat("Health",0);EntityLootableBody b=chunk(n,10,10);require(b.getHealth()==0 && !b.isAlive(),"Position repair healed legitimate zero health");kept(b);}
    private static void empty(){CompoundNBT n=base();n.put("Equipment",new ListNBT());n.remove("Aux");n.putString("Motion","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Geometry evidence counts as items");NameLifetimeChecks.retained(b,"Motion",n.get("Motion"));}
    private static void inventory(){CompoundNBT n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.putString("id","absentfixture:missing");n.getList("Equipment",10).add(raw);n.put("Pos",vector(Double.NaN,80,160.5));n.put("Motion",vector(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));EntityLootableBody b=chunk(n,10,10);require(b.isAlive(),"Corrupt geometry made inventory inaccessible");NbtRecoveryChecks.retained(b,raw);verifyWorldFields(b);stable(b);NbtRecoveryChecks.retained(read(saved(b)),raw);}
    static void verifyWorldFields(EntityLootableBody b){finite(b);sameValue(b.getMotion().x,0,"Disk motion X");sameValue(b.getMotion().y,0,"Disk motion Y");sameValue(b.getMotion().z,0,"Disk motion Z");NameLifetimeChecks.retained(b,"Pos",vector(Double.NaN,80,b.posZ));NameLifetimeChecks.retained(b,"Motion",vector(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));}
}
