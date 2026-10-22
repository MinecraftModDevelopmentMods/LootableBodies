package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.*;
import net.minecraftforge.fml.common.registry.GameRegistry;

/** Raw malformed data written into real disposable region files, then read in later JVMs. */
final class NbtWorldChecks {
    private static final UUID NEAR=UUID.fromString("00000000-0000-4000-8000-000000000121"),FAR=UUID.fromString("00000000-0000-4000-8000-000000000122");
    private static final String RETURNING="lootablebodiesbuildtest:returning_item";
    private static Item returning;
    static void registerItem(){
        String phase=System.getProperty("lootablebodies.phase","");
        if(phase.startsWith("nbt-return") || phase.startsWith("upgrade-return") || phase.startsWith("upgrade-final")) {
            returning=new Item().setHasSubtypes(true).setRegistryName(new ResourceLocation(RETURNING));GameRegistry.register(returning);
        }
    }
    static void run(MinecraftServer server,String phase) throws Exception {
        WorldServer world=server.getWorld(0);File folder=world.getSaveHandler().getWorldDirectory();
        if(phase.equals("nbt-seed")) {
            seed(world,NEAR,160,folder,"near");seed(world,FAR,4096,folder,"far");
            System.out.println("NBT_WORLD_CHECK_PASS raw_region_seed");return;
        }
        boolean restore=phase.startsWith("nbt-return"),first=phase.equals("nbt-reload");
        world.getChunkFromChunkCoords(10,10);EntityLootableBody near=RuntimeChecks.find(world,NEAR);
        if(first){verify(near,false);settle(near);snapshot(folder,"near",near);}
        else if(phase.equals("nbt-return")){verify(near,true);snapshot(folder,"near-returned",near);}
        else verifySnapshot(folder,restore?"near-returned":"near",near);
        identity(near,NEAR,160);verify(near,restore);
        if(first)RuntimeChecks.require(!world.getChunkProvider().chunkExists(256,256),"Malformed far chunk opened early");
        else {
            world.getChunkFromChunkCoords(256,256);EntityLootableBody far=RuntimeChecks.find(world,FAR);
            if(phase.equals("nbt-deferred")){verify(far,false);settle(far);snapshot(folder,"far",far);}
            else if(phase.equals("nbt-return")){verify(far,true);snapshot(folder,"far-returned",far);}
            else verifySnapshot(folder,restore?"far-returned":"far",far);
            identity(far,FAR,4096);verify(far,restore);
        }
        try(FileWriter out=new FileWriter(new File(folder,"nbt-world-checks.txt"),true)){out.write("PASS "+phase+" exact_items_raw_records_identity\n");}
        System.out.println("NBT_WORLD_CHECK_PASS "+phase+" exact_items_raw_records_identity");
    }
    static void seed(WorldServer w,UUID id,int coordinate,File folder,String name) throws Exception {
        w.getChunkFromChunkCoords(coordinate>>4,coordinate>>4);RawBody b=new RawBody(w);b.setUniqueId(id);b.setPosition(coordinate+.5,80,coordinate+.5);b.setNoGravity(true);b.setUserName("NbtFixture");b.setRotation(37);RuntimeChecks.disableVacuum(b);
        for(int i=0;i<48;i++)b.setInventorySlotContents(i,known(i));RuntimeChecks.require(w.spawnEntity(b),"Raw fixture spawn");
        NBTTagCompound n=new NBTTagCompound();RuntimeChecks.require(b.writeToNBTOptional(n),"Raw fixture serialization");
        try(FileOutputStream out=new FileOutputStream(new File(folder,"raw-"+name+".nbt"))){CompressedStreamTools.writeCompressed(n,out);}
    }
    private static ItemStack known(int i){return i==6?NbtRecoveryChecks.item(120,17):NbtRecoveryChecks.item(i,i<6?1:64);}
    private static NBTTagCompound rawEquipment(){
        NBTTagCompound n=new NBTTagCompound();NBTTagList equipment=new NBTTagList();
        for(int i=0;i<48;i++) {
            NBTTagCompound entry=known(i).writeToNBT(new NBTTagCompound());entry.setByte("Slot",(byte)i);
            if(i==7){entry=NbtRecoveryChecks.entry(123,9,7);entry.setString("id",RETURNING);}
            if(i==8)entry=missing(124,5,8);
            if(i==9){entry=NbtRecoveryChecks.entry(125,1,9);entry.setByte("Count",(byte)0);}
            if(i==10){entry=NbtRecoveryChecks.entry(129,6,10);NBTTagCompound caps=new NBTTagCompound();caps.setString("absentfixture:inventory","opaque capabilities");entry.setTag("ForgeCaps",caps);}
            equipment.appendTag(entry);
        }
        equipment.appendTag(NbtRecoveryChecks.entry(121,11,6));NBTTagCompound bad=NbtRecoveryChecks.entry(122,13,6);bad.setInteger("Slot",999);equipment.appendTag(bad);n.setTag("Equipment",equipment);
        n.setTag("Aux",NbtRecoveryChecks.list(NbtRecoveryChecks.entry(126,7,0),missing(127,3,0),NbtRecoveryChecks.entry(128,2,0),NbtRecoveryChecks.entry(130,19,0),NbtRecoveryChecks.entry(131,23,0)));
        n.setInteger("Vac",256);
        n.setLong("DeathTime",Long.MIN_VALUE);
        n.setDouble("DecayTime",Double.NaN);
        NBTTagCompound name=new NBTTagCompound();name.setString("Opaque","saved malformed attribution");n.setTag("Name",name);
        NBTTagCompound health=HealthAttributeChecks.malformedFields();for(String key:health.getKeySet())n.setTag(key,health.getTag(key).copy());
        NBTTagCompound effects=EffectHurtChecks.malformedFields();for(String key:effects.getKeySet())n.setTag(key,effects.getTag(key).copy());
        NBTTagCompound rotation=RotationFallChecks.malformedFields();for(String key:rotation.getKeySet())n.setTag(key,rotation.getTag(key).copy());
        return n;
    }
    private static NBTTagCompound missing(int index,int count,int slot){NBTTagCompound n=NbtRecoveryChecks.entry(index,count,slot);n.setString("id","absentfixture:missing");return n;}
    private static final class RawBody extends EntityLootableBody {
        RawBody(World w){super(w);}
        @Override public boolean writeToNBTOptional(NBTTagCompound n){if(isDead)return false;n.setString("id","lootablebodies:corpse");writeToNBT(n);return true;}
        @Override public void writeEntityToNBT(NBTTagCompound n){super.writeEntityToNBT(n);NBTTagCompound raw=rawEquipment();for(String key:raw.getKeySet())n.setTag(key,raw.getTag(key).copy());n.setTag("Pos",PositionMotionChecks.vector(Double.NaN,80,posZ));n.setTag("Motion",PositionMotionChecks.vector(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));}
    }
    private static void settle(EntityLootableBody b){for(int i=0;i<8;i++)b.onEntityUpdate();}
    private static void identity(EntityLootableBody b,UUID id,int coordinate){RuntimeChecks.require(b.isEntityAlive() && b.getUniqueID().equals(id) && b.posX==coordinate+8.5 && b.posY==80 && b.posZ==coordinate+.5 && b.rotationYaw==37 && b.getCustomNameTag().equals("NbtFixture"),"Recovered corpse identity/position/rotation/name/health changed");}
    private static void add(Map<String,Integer> totals,ItemStack s){NBTTagCompound n=s.writeToNBT(new NBTTagCompound());n.removeTag("Count");String key=n.toString();totals.put(key,totals.getOrDefault(key,0)+s.getCount());}
    static void verify(EntityLootableBody b,boolean restored){
        verifyMetadata(b);
        Map<String,Integer> expected=new TreeMap<>();for(int i=0;i<48;i++)if(i<7 || i>10)add(expected,known(i));
        for(int[] spec:new int[][]{{121,11},{122,13},{126,7},{128,2},{130,19},{131,23}})add(expected,NbtRecoveryChecks.item(spec[0],spec[1]));
        if(restored){NBTTagCompound raw=NbtRecoveryChecks.entry(123,9,7);raw.setString("id",RETURNING);ItemStack s=new ItemStack(raw);RuntimeChecks.require((s != null && !s.isEmpty()) && s.getItem()==returning,"Returning fixture item not registered");add(expected,s);}
        RuntimeChecks.require(expected.equals(NbtRecoveryChecks.items(b)),"Disk recovery item/count/damage/custom NBT lost or duplicated");
        NBTTagCompound source=rawEquipment();int rawCount=0;
        for(String list:new String[]{"Equipment","Aux"}){NBTTagList entries=source.getTagList(list,10);for(int i=0;i<entries.tagCount();i++){
            NBTTagCompound raw=entries.getCompoundTagAt(i);boolean unresolved=raw.getString("id").equals("absentfixture:missing") || raw.getByte("Count")==0 || raw.hasKey("ForgeCaps") || (!restored && raw.getString("id").equals(RETURNING));
            if(unresolved){NbtRecoveryChecks.retained(b,raw);rawCount++;}
        }}
        RuntimeChecks.require(NbtRecoveryChecks.saved(b).getTagList(NbtRecoveryChecks.RECOVERY,10).tagCount()==rawCount && rawCount==(restored?4:5),"Raw recovery records lost/duplicated or resolved twice");
    }
    static void verifyMetadata(EntityLootableBody b){
        HealthAttributeChecks.verifyWorldFields(b);
        EffectHurtChecks.verifyWorldFields(b);
        PositionMotionChecks.verifyWorldFields(b);
        RotationFallChecks.verifyWorldFields(b);
        NBTTagCompound raw=rawEquipment(),saved=NameLifetimeChecks.saved(b);
        RuntimeChecks.require(b.getCustomNameTag().equals("NbtFixture") && saved.getString("Name").equals("NbtFixture"),"Malformed Name erased vanilla fallback attribution");
        RuntimeChecks.require(!saved.hasKey("Vac") && saved.getLong("DeathTime")>=0 && saved.getLong("DeathTime")<=b.world.getTotalWorldTime() && saved.getLong("DecayTime")>=0 && saved.getLong("DecayTime")<=b.world.getTotalWorldTime(),"Malformed disk timers entered active state");
        for(String key:new String[]{"Name","Vac","DeathTime","DecayTime"})NameLifetimeChecks.retained(b,key,raw.getTag(key));
        RuntimeChecks.require(saved.getTagList(NameLifetimeChecks.RECOVERY,10).tagCount()==16,"Disk metadata evidence grew or disappeared");
    }
    private static void snapshot(File folder,String name,EntityLootableBody b) throws Exception {RuntimeChecks.saveExpected(folder,"nbt-"+name,b);}
    private static void verifySnapshot(File folder,String name,EntityLootableBody b) throws Exception {
        b.onEntityUpdate();RuntimeChecks.verifyExpected(folder,"nbt-"+name,b);NBTTagCompound expected;try(FileInputStream in=new FileInputStream(new File(folder,"nbt-"+name+".nbt"))){expected=CompressedStreamTools.readCompressed(in);}
        RuntimeChecks.require(Objects.equals(expected.getTag(NbtRecoveryChecks.RECOVERY),NbtRecoveryChecks.saved(b).getTag(NbtRecoveryChecks.RECOVERY)),"Raw recovery NBT changed on actual disk reload");
        RuntimeChecks.require(NameLifetimeChecks.same(expected.getTag(NameLifetimeChecks.RECOVERY),NbtRecoveryChecks.saved(b).getTag(NameLifetimeChecks.RECOVERY)),"Raw scalar data changed on actual disk reload");
    }
}
