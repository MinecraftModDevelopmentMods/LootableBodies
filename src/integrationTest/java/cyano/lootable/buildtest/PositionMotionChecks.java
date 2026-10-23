package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;

/** Validate before vanilla builds geometry, then exercise actual chunk admission. */
final class PositionMotionChecks {
    private interface Check {void run() throws Exception;}
    private static WorldServer world;
    private static int failures;
    private static final List<String> results=new ArrayList<>();
    static void run(MinecraftServer server,WorldServer target,File folder) throws Exception {
        world=target;failures=0;results.clear();
        NBTBase[] wrong={new NBTTagString("opaque"),new NBTTagCompound(),new NBTTagByteArray(new byte[]{1,2,3}),new NBTTagIntArray(new int[]{1,2,3}),new NBTTagDouble(Double.NaN)};
        for(String key:new String[]{"Pos","Motion"}) {
            for(int i=0;i<wrong.length;i++){final NBTBase raw=wrong[i];check(key+"_wrong_root_type_"+i,()->badVector(key,raw));}
            for(NBTBase raw:new NBTBase[]{new NBTTagString("opaque"),new NBTTagCompound(),new NBTTagByteArray(new byte[]{1})})check(key+"_wrong_element_type_"+raw.getId(),()->{NBTTagList list=new NBTTagList();for(int i=0;i<3;i++)list.appendTag(raw.copy());badVector(key,list);});
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
    static NBTTagList vector(double... values){NBTTagList l=new NBTTagList();for(double v:values)l.appendTag(new NBTTagDouble(v));return l;}
    private static NBTTagCompound saved(EntityLootableBody b){return NameLifetimeChecks.saved(b);}
    private static NBTTagCompound base(){EntityLootableBody b=new EntityLootableBody(world);b.setPosition(160.5,80,160.5);b.setNoGravity(true);b.setUserName("PositionFixture");for(int i=0;i<48;i++)b.setInventorySlotContents(i,RuntimeChecks.stack(i));b.addItem(RuntimeChecks.stack(70));b.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(b);b.motionX=.25;b.motionY=-.5;b.motionZ=.75;return saved(b);}
    private static EntityLootableBody read(NBTTagCompound n){NBTTagCompound before=n.copy();Entity e=EntityList.createEntityFromNBT(n,world);require(e instanceof EntityLootableBody,"Factory discarded corpse");require(NameLifetimeChecks.same(before,n),"Input NBT mutated");return (EntityLootableBody)e;}
    static void finite(EntityLootableBody b){for(double v:new double[]{b.posX,b.posY,b.posZ,b.motionX,b.motionY,b.motionZ,b.prevPosX,b.prevPosY,b.prevPosZ,b.lastTickPosX,b.lastTickPosY,b.lastTickPosZ})require(Double.isFinite(v),"Nonfinite geometry/derived position");AxisAlignedBB a=b.getEntityBoundingBox();for(double v:new double[]{a.minX,a.minY,a.minZ,a.maxX,a.maxY,a.maxZ})require(Double.isFinite(v),"Nonfinite bounding box");require(Math.abs(a.minX-(b.posX-b.width/2))<1e-6 && a.minY==b.posY && Math.abs(a.maxZ-(b.posZ+b.width/2))<1e-6,"Bounding box differs from sanitized position");}
    private static double[] values(EntityLootableBody b,String key){return key.equals("Pos")?new double[]{b.posX,b.posY,b.posZ}:new double[]{b.motionX,b.motionY,b.motionZ};}
    private static double fallback(String key,int axis){return key.equals("Pos") && axis==1?64:0;}
    private static void sameValue(double a,double z,String why){require(Double.isFinite(a) && a==z,why+" expected="+z+" actual="+a);}
    private static void kept(EntityLootableBody b){NBTTagCompound n=base(),a=saved(b);for(String k:new String[]{"Equipment","Aux"})require(NameLifetimeChecks.same(n.getTag(k),a.getTag(k)),"Complete inventory changed "+k);require(b.getSizeInventory()==48 && b.getCustomNameTag().equals("PositionFixture"),"Identity/default changed");}
    private static void stable(EntityLootableBody b){finite(b);kept(b);NBTTagCompound a=saved(b),z=saved(read(a));for(String k:new String[]{"Pos","Motion","Equipment","Aux",NbtRecoveryChecks.RECOVERY,NameLifetimeChecks.RECOVERY})require(NameLifetimeChecks.same(a.getTag(k),z.getTag(k)),"Round trip changed "+k);}
    private static void badVector(String key,NBTBase raw){NBTTagCompound n=base();n.setTag(key,raw.copy());EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],fallback(key,i),"Bad vector component");NameLifetimeChecks.retained(b,key,raw);stable(b);}
    private static void component(String key,int axis,double value){double[] v=key.equals("Pos")?new double[]{160.5,80,160.5}:new double[]{.25,-.5,.75};v[axis]=value;NBTTagCompound n=base();NBTTagList raw=vector(v);n.setTag(key,raw);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],i==axis?fallback(key,i):v[i],"Lost valid sibling or activated invalid component");NameLifetimeChecks.retained(b,key,raw);stable(b);}
    private static void length(String key,int count){double[] v=new double[count];for(int i=0;i<count;i++)v[i]=i+1;NBTTagList raw=vector(v);NBTTagCompound n=base();n.setTag(key,raw);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],i<count?v[i]:fallback(key,i),"Wrong-length vector lost prefix/default");NameLifetimeChecks.retained(b,key,raw);stable(b);}
    private static void numeric(String key,int type){NBTTagList l=new NBTTagList();for(int i=0;i<3;i++){NBTBase[] a={new NBTTagByte((byte)(i+1)),new NBTTagShort((short)(i+1)),new NBTTagInt(i+1),new NBTTagLong(i+1),new NBTTagFloat(i+1.25F),new NBTTagDouble(i+1.25)};l.appendTag(a[type]);}NBTTagCompound n=base();n.setTag(key,l);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],type<4?i+1:i+1.25,"Valid numeric vector lost");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid vector quarantined");stable(b);}
    private static void missing(String key){NBTTagCompound n=base();n.removeTag(key);EntityLootableBody b=read(n);for(int i=0;i<3;i++)sameValue(values(b,key)[i],fallback(key,i),"Missing vector default");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Missing field invented raw data");stable(b);}
    private static void signed(){NBTTagCompound n=base();n.setTag("Pos",vector(-160.25,500.5,-160.75));n.setTag("Motion",vector(-2.25,.125,-.5));EntityLootableBody b=read(n);sameValue(b.posX,-160.25,"Negative X");sameValue(b.posY,500.5,"High altitude");sameValue(b.posZ,-160.75,"Negative Z");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid signed position quarantined");stable(b);}
    private static void boundaries(){NBTTagCompound n=base();n.setTag("Pos",vector(-3e7,3e7,3e7));n.setTag("Motion",vector(-10,0,10));EntityLootableBody b=read(n);sameValue(b.posX,-3e7,"Boundary X");sameValue(b.posY,3e7,"Boundary Y");sameValue(b.posZ,3e7,"Boundary Z");sameValue(b.motionX,-10,"Boundary motion");sameValue(b.motionZ,10,"Boundary motion");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Boundary quarantined");stable(b);}
    private static void voidPosition(){NBTTagCompound n=base();n.setTag("Pos",vector(160.5,-65,160.5));EntityLootableBody b=read(n);sameValue(b.posY,-65,"Valid void position moved");require(!saved(b).hasKey(NameLifetimeChecks.RECOVERY),"Valid negative Y quarantined");stable(b);}
    private static void repeated(){EntityLootableBody b=read(base());b.setPosition(42,100,43);b.motionX=4;b.motionY=5;b.motionZ=6;NBTTagCompound n=base();n.setString("Pos","opaque");n.removeTag("Motion");b.readFromNBT(n);sameValue(b.posX,0,"Reused old X");sameValue(b.posY,64,"Reused old Y");sameValue(b.posZ,0,"Reused old Z");for(double v:values(b,"Motion"))sameValue(v,0,"Reused old motion");stable(b);}
    private static EntityLootableBody chunk(NBTTagCompound n,int x,int z){NBTTagCompound before=n.copy();Entity e=AnvilChunkLoader.readChunkEntity(n,world,new Chunk(world,x,z));require(e instanceof EntityLootableBody && !e.isDead,"Chunk discarded or killed malformed corpse");require(NameLifetimeChecks.same(before,n),"Chunk loader mutated source");EntityLootableBody b=(EntityLootableBody)e;require(b.addedToChunk && b.chunkCoordX==x && b.chunkCoordZ==z,"Admission coordinates wrong");finite(b);return b;}
    private static void chunkAdmission(){NBTTagCompound n=base();n.setTag("Pos",vector(Double.NaN,80,160.5));EntityLootableBody b=chunk(n,10,10);sameValue(b.posX,168.5,"Containing chunk anchor missing");sameValue(b.posZ,160.5,"Valid Z was moved");NameLifetimeChecks.retained(b,"Pos",n.getTag("Pos"));stable(b);}
    private static void allInvalidChunk(){NBTTagCompound n=base();n.setString("Pos","opaque");EntityLootableBody b=chunk(n,-11,10);sameValue(b.posX,-167.5,"Negative containing chunk center");sameValue(b.posY,64,"Invalid Y default");sameValue(b.posZ,168.5,"Containing Z chunk center");stable(b);}
    private static void finiteMismatch(boolean x){NBTTagCompound n=base();EntityLootableBody b=chunk(n,x?-11:10,x?10:11);sameValue(b.posX,x?-167.5:160.5,"Finite mismatched X");sameValue(b.posZ,x?160.5:184.5,"Finite mismatched Z");NameLifetimeChecks.retained(b,"Pos",n.getTag("Pos"));stable(b);}
    private static void healthyChunk(){NBTTagCompound n=base();EntityLootableBody b=chunk(n,10,10);sameValue(b.posX,160.5,"Healthy chunk position moved");b.setPosition(176.5,80,160.5);new Chunk(world,11,10).addEntity(b);sameValue(b.posX,176.5,"Healthy later movement was anchored");require(!b.isDead,"Healthy later movement killed");stable(b);}
    private static void notRevived(boolean previousInvalid){NBTTagCompound n=base();if(previousInvalid)n.setTag("Pos",vector(Double.NaN,80,160.5));EntityLootableBody b=read(n);if(previousInvalid)b.readFromNBT(base());b.isDead=true;new Chunk(world,10,10).addEntity(b);require(b.isDead && b.posX==160.5,"Position hook revived ordinary removal or kept an obsolete repair");b.isDead=false;stable(b);}
    private static void deadHealth(){NBTTagCompound n=base();n.setTag("Pos",vector(Double.NaN,80,160.5));n.setFloat("Health",0);EntityLootableBody b=chunk(n,10,10);require(b.getHealth()==0 && !b.isEntityAlive(),"Position repair healed legitimate zero health");kept(b);}
    private static void empty(){NBTTagCompound n=base();n.setTag("Equipment",new NBTTagList());n.removeTag("Aux");n.setString("Motion","opaque");EntityLootableBody b=read(n);require(b.isEmpty(),"Geometry evidence counts as items");NameLifetimeChecks.retained(b,"Motion",n.getTag("Motion"));}
    private static void inventory(){NBTTagCompound n=base(),raw=NbtRecoveryChecks.entry(121,11,7);raw.setString("id","absentfixture:missing");n.getTagList("Equipment",10).appendTag(raw);n.setTag("Pos",vector(Double.NaN,80,160.5));n.setTag("Motion",vector(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));EntityLootableBody b=chunk(n,10,10);require(b.isEntityAlive(),"Corrupt geometry made inventory inaccessible");NbtRecoveryChecks.retained(b,raw);verifyWorldFields(b);stable(b);NbtRecoveryChecks.retained(read(saved(b)),raw);}
    static void verifyWorldFields(EntityLootableBody b){finite(b);sameValue(b.motionX,0,"Disk motion X");sameValue(b.motionY,0,"Disk motion Y");sameValue(b.motionZ,0,"Disk motion Z");NameLifetimeChecks.retained(b,"Pos",vector(Double.NaN,80,b.posZ));NameLifetimeChecks.retained(b,"Motion",vector(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));}
}
