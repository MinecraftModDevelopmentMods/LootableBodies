package cyano.lootable.buildtest;
import net.minecraft.world.inventory.Slot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.fmllegacy.common.registry.GameRegistry;

/** Raw malformed data written into real disposable region files, then read in later JVMs. */
final class NbtWorldChecks {
    private static final UUID NEAR=UUID.fromString("00000000-0000-4000-8000-000000000121"),FAR=UUID.fromString("00000000-0000-4000-8000-000000000122");
    private static final String RETURNING="lootablebodiesbuildtest:returning_item";
    private static Item returning;
    static void registerItem(){
        String phase=System.getProperty("lootablebodies.phase","");
        if(phase.startsWith("nbt-return") || phase.startsWith("upgrade-return") || phase.startsWith("upgrade-final")) {
            returning=new Item(new Item.Properties()).setRegistryName(new ResourceLocation(RETURNING));net.minecraftforge.registries.ForgeRegistries.ITEMS.register(returning);
        }
    }
    static void run(MinecraftServer server,String phase) throws Exception {
        ServerLevel world=server.getLevel(FixtureApi.dimensionKey(0));File folder=world.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        if(phase.equals("nbt-seed")) {
            seed(world,NEAR,160,folder,"near");seed(world,FAR,4096,folder,"far");
            System.out.println("NBT_WORLD_CHECK_PASS raw_region_seed");return;
        }
        boolean restore=phase.startsWith("nbt-return"),first=phase.equals("nbt-reload");
        RuntimeChecks.awaitChunk(world,10,10);EntityLootableBody near=RuntimeChecks.find(world,NEAR);
        if(first){verify(near,false);settle(near);snapshot(folder,"near",near);}
        else if(phase.equals("nbt-return")){verify(near,true);snapshot(folder,"near-returned",near);}
        else verifySnapshot(folder,restore?"near-returned":"near",near);
        identity(near,NEAR,160);verify(near,restore);
        if(first)RuntimeChecks.require(!world.getChunkSource().hasChunk(256,256),"Malformed far chunk opened early");
        else {
            RuntimeChecks.awaitChunk(world,256,256);EntityLootableBody far=RuntimeChecks.find(world,FAR);
            if(phase.equals("nbt-deferred")){verify(far,false);settle(far);snapshot(folder,"far",far);}
            else if(phase.equals("nbt-return")){verify(far,true);snapshot(folder,"far-returned",far);}
            else verifySnapshot(folder,restore?"far-returned":"far",far);
            identity(far,FAR,4096);verify(far,restore);
        }
        try(FileWriter out=new FileWriter(new File(folder,"nbt-world-checks.txt"),true)){out.write("PASS "+phase+" exact_items_raw_records_identity\n");}
        System.out.println("NBT_WORLD_CHECK_PASS "+phase+" exact_items_raw_records_identity");
    }
    static void seed(ServerLevel w,UUID id,int coordinate,File folder,String name) throws Exception {
        RuntimeChecks.awaitChunk(w,coordinate>>4,coordinate>>4);RawBody b=new RawBody(w);b.setUUID(id);b.setPos(coordinate+.5,80,coordinate+.5);b.setNoGravity(true);b.setUserName("NbtFixture");b.setRotation(37);RuntimeChecks.disableVacuum(b);
        for(int i=0;i<48;i++)b.setItem(i,known(i));RuntimeChecks.require(w.addFreshEntity(b),"Raw fixture spawn");
        CompoundTag n=new CompoundTag();RuntimeChecks.require(b.saveAsPassenger(n),"Raw fixture serialization");
        try(FileOutputStream out=new FileOutputStream(new File(folder,"raw-"+name+".nbt"))){NbtIo.writeCompressed(n,out);}
    }
    private static ItemStack known(int i){return i==6?NbtRecoveryChecks.item(120,17):NbtRecoveryChecks.item(i,i<6?1:64);}
    private static CompoundTag rawEquipment(){
        CompoundTag n=new CompoundTag();ListTag equipment=new ListTag();
        for(int i=0;i<48;i++) {
            CompoundTag entry=known(i).save(new CompoundTag());entry.putByte("Slot",(byte)i);
            if(i==7){entry=NbtRecoveryChecks.entry(123,9,7);entry.putString("id",RETURNING);}
            if(i==8)entry=missing(124,5,8);
            if(i==9){entry=NbtRecoveryChecks.entry(125,1,9);entry.putByte("Count",(byte)0);}
            if(i==10){entry=NbtRecoveryChecks.entry(129,6,10);CompoundTag caps=new CompoundTag();caps.putString("absentfixture:inventory","opaque capabilities");entry.put("ForgeCaps",caps);}
            equipment.add(entry);
        }
        equipment.add(NbtRecoveryChecks.entry(121,11,6));CompoundTag bad=NbtRecoveryChecks.entry(122,13,6);bad.putInt("Slot",999);equipment.add(bad);n.put("Equipment",equipment);
        n.put("Aux",NbtRecoveryChecks.list(NbtRecoveryChecks.entry(126,7,0),missing(127,3,0),NbtRecoveryChecks.entry(128,2,0),NbtRecoveryChecks.entry(130,19,0),NbtRecoveryChecks.entry(131,23,0)));
        n.putInt("Vac",256);
        n.putLong("DeathTime",Long.MIN_VALUE);
        n.putDouble("DecayTime",Double.NaN);
        CompoundTag name=new CompoundTag();name.putString("Opaque","saved malformed attribution");n.put("Name",name);
        CompoundTag health=HealthAttributeChecks.malformedFields();for(String key:health.getAllKeys())n.put(key,health.get(key).copy());
        CompoundTag effects=EffectHurtChecks.malformedFields();for(String key:effects.getAllKeys())n.put(key,effects.get(key).copy());
        CompoundTag rotation=RotationFallChecks.malformedFields();for(String key:rotation.getAllKeys())n.put(key,rotation.get(key).copy());
        return n;
    }
    private static CompoundTag missing(int index,int count,int slot){CompoundTag n=NbtRecoveryChecks.entry(index,count,slot);n.putString("id","absentfixture:missing");return n;}
    private static final class RawBody extends EntityLootableBody {
        RawBody(Level w){super(w);}
        @Override public boolean saveAsPassenger(CompoundTag n){if(isRemoved())return false;n.putString("id","lootablebodies:corpse");saveWithoutId(n);return true;}
        @Override public void addAdditionalSaveData(CompoundTag n){super.addAdditionalSaveData(n);CompoundTag raw=rawEquipment();for(String key:raw.getAllKeys())n.put(key,raw.get(key).copy());n.put("Pos",PositionMotionChecks.vector(Double.NaN,80,getZ()));n.put("Motion",PositionMotionChecks.vector(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY));}
    }
    private static void settle(EntityLootableBody b){for(int i=0;i<8;i++)b.baseTick();}
    private static void identity(EntityLootableBody b,UUID id,int coordinate){RuntimeChecks.require(b.isAlive() && b.getUUID().equals(id) && b.getX()==coordinate+8.5 && b.getY()==80 && b.getZ()==coordinate+.5 && b.getYRot()==37 && b.getCustomNameTag().equals("NbtFixture"),"Recovered corpse identity/position/rotation/name/health changed");}
    private static void add(Map<String,Integer> totals,ItemStack s){CompoundTag n=s.save(new CompoundTag());n.remove("Count");String key=n.toString();totals.put(key,totals.getOrDefault(key,0)+s.getCount());}
    static void verify(EntityLootableBody b,boolean restored){
        verifyMetadata(b);
        Map<String,Integer> expected=new TreeMap<>();for(int i=0;i<48;i++)if(i<7 || i>10)add(expected,known(i));
        for(int[] spec:new int[][]{{121,11},{122,13},{126,7},{128,2},{130,19},{131,23}})add(expected,NbtRecoveryChecks.item(spec[0],spec[1]));
        if(restored){CompoundTag raw=NbtRecoveryChecks.entry(123,9,7);raw.putString("id",RETURNING);ItemStack s=ItemStack.of(raw);RuntimeChecks.require((s != null && !s.isEmpty()) && s.getItem()==returning,"Returning fixture item not registered");add(expected,s);}
        RuntimeChecks.require(expected.equals(NbtRecoveryChecks.items(b)),"Disk recovery item/count/damage/custom NBT lost or duplicated");
        CompoundTag source=rawEquipment();int rawCount=0;
        for(String list:new String[]{"Equipment","Aux"}){ListTag entries=source.getList(list,10);for(int i=0;i<entries.size();i++){
            CompoundTag raw=entries.getCompound(i);boolean unresolved=raw.getString("id").equals("absentfixture:missing") || raw.getByte("Count")==0 || raw.contains("ForgeCaps") || (!restored && raw.getString("id").equals(RETURNING));
            if(unresolved){NbtRecoveryChecks.retained(b,raw);rawCount++;}
        }}
        RuntimeChecks.require(NbtRecoveryChecks.saved(b).getList(NbtRecoveryChecks.RECOVERY,10).size()==rawCount && rawCount==(restored?4:5),"Raw recovery records lost/duplicated or resolved twice");
    }
    static void verifyMetadata(EntityLootableBody b){
        HealthAttributeChecks.verifyWorldFields(b);
        EffectHurtChecks.verifyWorldFields(b);
        PositionMotionChecks.verifyWorldFields(b);
        RotationFallChecks.verifyWorldFields(b);
        CompoundTag raw=rawEquipment(),saved=NameLifetimeChecks.saved(b);
        RuntimeChecks.require(b.getCustomNameTag().equals("NbtFixture") && saved.getString("Name").equals("NbtFixture"),"Malformed Name erased vanilla fallback attribution");
        RuntimeChecks.require(!saved.contains("Vac") && saved.getLong("DeathTime")>=0 && saved.getLong("DeathTime")<=b.level.getGameTime() && saved.getLong("DecayTime")>=0 && saved.getLong("DecayTime")<=b.level.getGameTime(),"Malformed disk timers entered active state");
        for(String key:new String[]{"Name","Vac","DeathTime","DecayTime"})NameLifetimeChecks.retained(b,key,raw.get(key));
        RuntimeChecks.require(saved.getList(NameLifetimeChecks.RECOVERY,10).size()==16,"Disk metadata evidence grew or disappeared");
    }
    private static void snapshot(File folder,String name,EntityLootableBody b) throws Exception {RuntimeChecks.saveExpected(folder,"nbt-"+name,b);}
    private static void verifySnapshot(File folder,String name,EntityLootableBody b) throws Exception {
        b.baseTick();RuntimeChecks.verifyExpected(folder,"nbt-"+name,b);CompoundTag expected;try(FileInputStream in=new FileInputStream(new File(folder,"nbt-"+name+".nbt"))){expected=NbtIo.readCompressed(in);}
        RuntimeChecks.require(Objects.equals(expected.get(NbtRecoveryChecks.RECOVERY),NbtRecoveryChecks.saved(b).get(NbtRecoveryChecks.RECOVERY)),"Raw recovery NBT changed on actual disk reload");
        RuntimeChecks.require(NameLifetimeChecks.same(expected.get(NameLifetimeChecks.RECOVERY),NbtRecoveryChecks.saved(b).get(NameLifetimeChecks.RECOVERY)),"Raw scalar data changed on actual disk reload");
    }
}
