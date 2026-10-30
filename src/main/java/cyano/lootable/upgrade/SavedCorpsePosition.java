package cyano.lootable.upgrade;

import net.minecraft.nbt.*;

/** Recover saved coordinates before native 1.17 builds entity-section indexes. */
public final class SavedCorpsePosition {
    private static final String RECOVERY="LootableBodiesInvalidFields";
    private SavedCorpsePosition() { }
    /** Native Entity.load clamps Y at twenty million before building geometry. */
    public static boolean isSafeCoordinate(int axis,double value) {
        if(axis<0 || axis>2)throw new IllegalArgumentException("Invalid position axis");
        return Double.isFinite(value) && Math.abs(value)<=(axis==1?2.0E7D:3.0E7D);
    }
    static void prepare(ListTag roots,int chunkX,int chunkZ) {
        for(Tag root:roots)visit((CompoundTag)root,chunkX,chunkZ,true,0);
    }
    private static void visit(CompoundTag entity,int x,int z,boolean root,int depth) {
        if(depth>512)throw new IllegalStateException("Saved corpse passenger tree is too deep");
        if(UpgradeJournal.corpse(entity))repair(entity,x,z,root);
        for(Tag rider:entity.getList("Passengers",10))visit((CompoundTag)rider,x,z,false,depth+1);
    }
    private static void repair(CompoundTag entity,int x,int z,boolean root) {
        Tag raw=entity.get("Pos");ListTag list=raw instanceof ListTag?(ListTag)raw:null;
        boolean changed=list==null || list.size()!=3;
        double[] position=new double[3];
        for(int axis=0;axis<3;axis++) {
            Tag value=list!=null && axis<list.size()?list.get(axis):null;
            double number=value instanceof NumericTag?((NumericTag)value).getAsDouble():Double.NaN;
            if(!isSafeCoordinate(axis,number)) {
                number=axis==1?64D:(axis==0?x:z)*16D+8.5D;changed=true;
            }
            position[axis]=number;
        }
        // A passenger may legitimately straddle its vehicle's saved chunk.
        if(root)for(int axis:new int[]{0,2}) {
            int chunk=axis==0?x:z;
            if(Math.floor(position[axis]/16D)!=chunk){position[axis]=chunk*16D+8.5D;changed=true;}
        }
        if(!changed)return;
        for(int axis=0;axis<3;axis++)if(!isSafeCoordinate(axis,position[axis]))
            throw new IllegalStateException("Saved containing chunk has no safe corpse position");
        ListTag normalized=new ListTag();for(double number:position)normalized.add(DoubleTag.valueOf(number));
        entity.put("Pos",normalized);
        cyano.lootable.ModLog.warning("Lootable Bodies recovered malformed corpse position in chunk %s,%s",x,z);
        if(raw==null)return;
        Tag metadata=entity.get(RECOVERY);ListTag records=new ListTag();
        if(metadata instanceof ListTag && (((ListTag)metadata).isEmpty() || ((ListTag)metadata).getElementType()==10))
            records=(ListTag)metadata.copy();
        else if(metadata!=null)retain(records,"Recovery",metadata);
        retain(records,"Pos",raw);entity.put(RECOVERY,records);
    }
    private static void retain(ListTag records,String source,Tag value) {
        CompoundTag record=new CompoundTag();record.putString("Source",source);record.put("Data",value.copy());
        if(!records.contains(record))records.add(record);
    }
}
