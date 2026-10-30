package cyano.lootable.buildtest;

import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.nbt.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Controlled packaged pre-world conversion/refusal; separate from old writers. */
public final class NativePreflightChecks {
    static final String INJECTED="NATIVE117_INJECTED_ENTITY_CHUNK_REFUSAL";
    private static final AtomicInteger early=new AtomicInteger(),worlds=new AtomicInteger(),starts=new AtomicInteger();
    private static String mode;
    static void install(net.minecraftforge.eventbus.api.IEventBus bus) {
        mode=System.getProperty("lootablebodies.nativePreflight","");
        if(mode.isEmpty())return;
        if(!mode.equals("refuse") && !mode.equals("convert"))throw new IllegalArgumentException("Unknown preflight control");
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new NativePreflightChecks());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            try {Files.writeString(Path.of("preflight-control.receipt"),"mode="+mode+" earlyConversions="+early.get()+" worldLoads="+worlds.get()+" serverStarts="+starts.get()+"\n",StandardOpenOption.CREATE_NEW);}
            catch(Exception failure){failure.printStackTrace();}
        },"PreflightControlReceipt"));
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent event)->event.enqueueWork(()->{
            DataFixer nativeFixer=NativeVanillaDataFixer.get();
            DataFixer observer=new DataFixer() {
                public Schema getSchema(int version){return nativeFixer.getSchema(version);}
                public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to) {
                    if(type.typeName().equals("entity_chunk") && net.minecraftforge.fmllegacy.server.ServerLifecycleHooks.getCurrentServer()==null) {
                        boolean preflight=java.util.Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(
                                frame->frame.getClassName().equals("cyano.lootable.upgrade.UpgradeJournal") && frame.getMethodName().equals("validateEntityChunks"));
                        if(!preflight || worlds.get()!=0 || starts.get()!=0)throw new AssertionError("Entity conversion missed the pre-world boundary");
                        early.incrementAndGet();System.out.println("NATIVE117_PREWORLD_ENTITY_CONVERSION from="+from+" to="+to+" mode="+mode);
                        if(mode.equals("refuse"))throw new IllegalStateException(INJECTED+": controlled negative fixture, not a native Minecraft error");
                    }
                    return nativeFixer.update(type,input,from,to);
                }
            };
            cyano.lootable.upgrade.UpgradePreservation.register(observer);
            System.out.println("NATIVE117_PREFLIGHT_CONTROL_INSTALLED mode="+mode);
        }));
    }
    @SubscribeEvent public void loaded(net.minecraftforge.event.world.WorldEvent.Load event){worlds.incrementAndGet();}
    @SubscribeEvent public void starting(net.minecraftforge.fmlserverevents.FMLServerAboutToStartEvent event){starts.incrementAndGet();}
    static void verify(MinecraftServer server) throws Exception {
        RuntimeChecks.require(mode.equals("convert") && early.get()>0,"No actual pre-world entity conversion observed");
        net.minecraft.server.level.ServerLevel world=server.overworld();RuntimeChecks.awaitChunk(world,0,0);
        cyano.lootable.entities.EntityLootableBody body=RuntimeChecks.find(world,
                java.util.UUID.fromString("00000000-1171-4171-8171-000000000099"));
        RuntimeChecks.require(body!=null && body.getContainerSize()==48,"Native entity storage did not admit the preserved corpse");
        for(int slot=0;slot<48;slot++) {
            net.minecraft.world.item.ItemStack item=body.getItem(slot);
            // The synthetic codec source uses diamonds even in armour slots.
            // Existing placement rules preserve those invalid records in Aux.
            if(slot<4){RuntimeChecks.require(item.isEmpty(),"Invalid diamond entered an armour slot");continue;}
            RuntimeChecks.require(item.getItem()==net.minecraft.world.item.Items.DIAMOND && item.getCount()==slot+1
                    && item.hasTag() && item.getTag().getString("keep").equals("slot-"+slot),"Preflight inventory changed at slot"+slot);
        }
        CompoundTag saved=new CompoundTag();body.saveAsPassenger(saved);
        RuntimeChecks.require(saved.getInt("LootableBodiesDataVersion")==7,"Preflight corpse marker was not advanced");
        ListTag aux=saved.getList("Aux",10);RuntimeChecks.require(aux.size()==5,"Preflight Aux record count changed");
        for(int index=0;index<5;index++) {
            int sourceSlot=index<4?index:47;CompoundTag item=aux.getCompound(index);
            RuntimeChecks.require(item.getString("id").equals("minecraft:diamond") && item.getByte("Count")==sourceSlot+1
                    && item.getCompound("tag").getString("keep").equals("slot-"+sourceSlot),"Preflight Aux changed at record"+index);
        }
        RuntimeChecks.require(saved.getList("LootableBodiesUnresolved",10).stream().anyMatch(value->value instanceof CompoundTag
                && ((CompoundTag)value).getString("Source").equals("FixtureUnknown")
                && ((CompoundTag)value).getCompound("Data").getString("id").equals("minecraft:diamond")
                && ((CompoundTag)value).getCompound("Data").getByte("Count")==47
                && ((CompoundTag)value).getCompound("Data").getByte("Slot")==46
                && ((CompoundTag)value).getCompound("Data").getCompound("tag").getString("keep").equals("slot-46")),"Raw Source/Data recovery evidence changed");
        Files.writeString(Path.of("preflight-conversion.pass"),"48inputEquipmentRecords/Aux/Source/Data/entityStorage conserved\n",StandardOpenOption.CREATE_NEW);
        System.out.println("NATIVE117_PACKAGED_PREFLIGHT_CONVERSION_PASS");
    }
}
