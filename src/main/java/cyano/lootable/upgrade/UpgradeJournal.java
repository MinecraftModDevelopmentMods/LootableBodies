package cyano.lootable.upgrade;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.*;
import net.minecraft.nbt.*;

/** Read-only Anvil inspection and immutable, checksummed corpse originals. */
public final class UpgradeJournal {
    private static final int FORMAT=2;
    private static final long MAX_NBT=64L*1024*1024;
    private static final Pattern REGION=Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private final Path world, directory, snapshots, chunks;
    private final Map<UUID,Path> active=new HashMap<>();

    private UpgradeJournal(Path world) throws IOException {
        this.world=world.toRealPath();
        directory=this.world.resolve("data/lootablebodies-upgrade");
        snapshots=directory.resolve("snapshots");
        chunks=directory.resolve("chunks");
    }

    /** Complete preservation before Minecraft opens any existing chunk. */
    public static UpgradeJournal scan(Path world) throws IOException {
        Files.createDirectories(world);
        UpgradeJournal journal=new UpgradeJournal(world);
        journal.inspect();
        return journal;
    }

    public int size() { return active.size(); }

    /** Only an entity already present in this chunk may consume its original. */
    public NBTTagCompound originalFor(UUID id,int chunkX,int chunkZ) throws IOException {
        Path path=active.get(id);
        if(path==null) return null;
        NBTTagCompound original=readSnapshot(path);
        return original.getInteger("ChunkX")==chunkX && original.getInteger("ChunkZ")==chunkZ ? original : null;
    }

    NBTTagCompound originalFor(UUID id) throws IOException {
        Path path=active.get(id);
        return path==null ? null : readSnapshot(path);
    }

    static boolean corpse(NBTTagCompound entity) {
        String id=entity.getString("id");
        return id.equals("lootablebodies:corpse") || id.equals("lootablebodies.Corpse");
    }

    private void inspect() throws IOException {
        // Reject links rather than silently skipping a linked dimension or writing
        // a recovery journal outside this world. No region is opened in rw mode.
        List<Path> regions=new ArrayList<>();
        if(Files.isSymbolicLink(directory) || Files.isSymbolicLink(world.resolve("data")))
            throw new IOException("Lootable Bodies upgrade journal must be inside the world");
        try(Stream<Path> paths=Files.walk(world)) {
            for(Iterator<Path> it=paths.iterator();it.hasNext();) {
                Path path=it.next();
                if(Files.isSymbolicLink(path)) throw new IOException("Cannot preserve a world containing linked paths: "+world.relativize(path));
                if(Files.isRegularFile(path) && path.getParent().getFileName().toString().equals("region")
                        && REGION.matcher(path.getFileName().toString()).matches()) regions.add(path);
            }
        }
        regions.sort(Comparator.comparing(Path::toString));
        Files.createDirectories(snapshots);
        Files.createDirectories(chunks);
        // Old originals remain an integrity-checked recovery archive even after
        // their corpses have been looted or removed. They are never reinserted.
        try(DirectoryStream<Path> files=Files.newDirectoryStream(snapshots,"*.nbt")) {
            for(Path path:files) readSnapshot(path);
        }
        Map<String,NBTTagCompound> previous=readIndex();
        NBTTagCompound worldVersions=readWorldVersions();
        NBTTagList updated=new NBTTagList();
        for(Path region:regions) {
            String relative=world.relativize(region).toString().replace('\\','/');
            String hash=fileDigest(region);
            NBTTagCompound record=previous.get(relative);
            if(record==null || !hash.equals(record.getString("Digest")))
                record=scanRegion(region,relative,hash,worldVersions);
            else record=record.copy();
            NBTTagList originals=record.getTagList("Snapshots",8);
            for(int i=0;i<originals.tagCount();i++) {
                Path path=snapshotPath(originals.getStringTagAt(i));
                NBTTagCompound original=readSnapshot(path);
                if(!relative.equals(original.getString("Region")) || !hash.equals(original.getString("RegionDigest")))
                    throw new IOException("Recovery index does not match its source region");
                NBTTagCompound entity=original.getCompoundTag("Entity");
                UUID uuid=uuid(entity);
                if(active.put(uuid,path)!=null) throw new IOException("Duplicate corpse UUID across saved chunks: "+uuid);
            }
            // Detect another writer during inspection, before trusting the scan.
            if(!hash.equals(fileDigest(region))) throw new IOException("Region changed during corpse preservation: "+relative);
            updated.appendTag(record);
        }
        NBTTagCompound index=new NBTTagCompound();index.setInteger("Format",FORMAT);index.setTag("Regions",updated);
        byte[] payload=compressed(index);
        NBTTagCompound envelope=new NBTTagCompound();envelope.setInteger("Format",FORMAT);
        envelope.setString("Digest",digest(payload));envelope.setByteArray("Payload",payload);
        atomic(directory.resolve("index.nbt"),compressed(envelope),true);
    }

    private Map<String,NBTTagCompound> readIndex() throws IOException {
        Map<String,NBTTagCompound> records=new HashMap<>();
        Path index=directory.resolve("index.nbt");
        if(!Files.exists(index)) return records;
        NBTTagCompound envelope=readCompressed(index);
        byte[] payload=envelope.getByteArray("Payload");
        if(!envelope.hasKey("Format",3) || envelope.getInteger("Format")!=FORMAT
                || !envelope.hasKey("Digest",8) || !envelope.hasKey("Payload",7)
                || !digest(payload).equals(envelope.getString("Digest")))
            throw new IOException("Lootable Bodies upgrade index checksum mismatch");
        NBTTagCompound nbt;
        try(DataInputStream input=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            nbt=CompressedStreamTools.read(input,new NBTSizeTracker(MAX_NBT));
        }
        if(!nbt.hasKey("Format",3) || nbt.getInteger("Format")!=FORMAT || !compoundList(nbt.getTag("Regions")))
            throw new IOException("Invalid Lootable Bodies upgrade index");
        NBTTagList regions=nbt.getTagList("Regions",10);
        for(int i=0;i<regions.tagCount();i++) {
            NBTTagCompound record=regions.getCompoundTagAt(i);
            if(!record.hasKey("Path",8) || !record.hasKey("Digest",8) || !record.getString("Digest").matches("[a-f0-9]{64}")
                    || !(record.getTag("Snapshots") instanceof NBTTagList)) throw new IOException("Invalid recovery region record");
            NBTTagList list=(NBTTagList)record.getTag("Snapshots");
            if(list.tagCount()>0 && list.getTagType()!=8) throw new IOException("Invalid recovery snapshot list");
            if(records.put(record.getString("Path"),record)!=null) throw new IOException("Duplicate recovery region record");
            for(int j=0;j<list.tagCount();j++) readSnapshot(snapshotPath(list.getStringTagAt(j)));
        }
        return records;
    }

    private NBTTagCompound scanRegion(Path file,String relative,String hash,NBTTagCompound worldVersions) throws IOException {
        Matcher name=REGION.matcher(file.getFileName().toString());
        if(!name.matches()) throw new IOException("Invalid region name");
        long rx=Long.parseLong(name.group(1)),rz=Long.parseLong(name.group(2));
        if(rx<Integer.MIN_VALUE/32 || rx>Integer.MAX_VALUE/32 || rz<Integer.MIN_VALUE/32 || rz>Integer.MAX_VALUE/32)
            throw new IOException("Region coordinates out of range");
        NBTTagList preserved=new NBTTagList();
        try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")) {
            long length=input.length();
            if(length<8192 || length%4096!=0) throw new IOException("Incomplete region: "+relative);
            int[] offsets=new int[1024];for(int i=0;i<1024;i++) offsets[i]=input.readInt();
            Set<Integer> allocated=new HashSet<>();allocated.add(0);allocated.add(1);
            for(int i=0;i<1024;i++) {
                if(offsets[i]==0) continue;
                int sector=offsets[i]>>>8,count=offsets[i]&255;
                if(sector<2 || count==0 || ((long)sector+count)*4096>length) throw new IOException("Invalid region allocation: "+relative);
                for(int s=sector;s<sector+count;s++) if(!allocated.add(s)) throw new IOException("Overlapping region allocations: "+relative);
                input.seek((long)sector*4096);int bytes=input.readInt(),compression=input.readUnsignedByte();
                if(bytes<2 || bytes>(count*4096-4)) throw new IOException("Invalid compressed chunk size: "+relative);
                byte[] payload=new byte[bytes-1];input.readFully(payload);
                InputStream stream=compression==1 ? new GZIPInputStream(new ByteArrayInputStream(payload)) :
                        compression==2 ? new InflaterInputStream(new ByteArrayInputStream(payload)) : null;
                if(stream==null) throw new IOException("Unknown chunk compression: "+relative);
                NBTTagCompound chunk;
                try(DataInputStream nbt=new DataInputStream(stream)) { chunk=CompressedStreamTools.read(nbt,new NBTSizeTracker(MAX_NBT)); }
                catch(RuntimeException damaged) { throw new IOException("Unreadable chunk in "+relative,damaged); }
                if(!chunk.hasKey("Level",10)) throw new IOException("Missing chunk Level: "+relative);
                NBTTagCompound level=chunk.getCompoundTag("Level");int x=(int)rx*32+(i%32),z=(int)rz*32+(i/32);
                if(!level.hasKey("xPos",3) || !level.hasKey("zPos",3) || level.getInteger("xPos")!=x || level.getInteger("zPos")!=z)
                    throw new IOException("Chunk coordinate mismatch in "+relative);
                if(!level.hasKey("Entities")) continue;
                if(!compoundList(level.getTag("Entities"))) throw new IOException("Unreadable entity list in "+relative);
                NBTTagList entities=level.getTagList("Entities",10);
                String originalChunk=digest(payload);
                for(int e=0;e<entities.tagCount();e++) {
                    NBTTagCompound entity=entities.getCompoundTagAt(e);
                    if(!corpse(entity)) continue;
                    uuid(entity); // An ambiguous identity cannot safely select a backup.
                    if(entity.hasKey("LootableBodiesDataVersion",3) && entity.getInteger("LootableBodiesDataVersion")>1)
                        throw new IOException("Corpse uses a newer preservation format");
                    NBTTagCompound snapshot=new NBTTagCompound();snapshot.setInteger("Format",FORMAT);snapshot.setString("Region",relative);snapshot.setString("RegionDigest",hash);
                    Path chunkFile=chunks.resolve(originalChunk+".bin");
                    if(!Files.exists(chunkFile)) atomic(chunkFile,payload,false);
                    else if(!Arrays.equals(payload,Files.readAllBytes(chunkFile))) throw new IOException("Original chunk checksum collision");
                    snapshot.setString("OriginalChunk",originalChunk);snapshot.setByte("OriginalCompression",(byte)compression);snapshot.setInteger("EntityIndex",e);
                    snapshot.setString("Dimension",world.relativize(file.getParent().getParent()).toString().replace('\\','/'));
                    snapshot.setInteger("ChunkX",x);snapshot.setInteger("ChunkZ",z);snapshot.setTag("Entity",entity.copy());
                    snapshot.setTag("SourceVersions",sourceVersions(chunk,worldVersions,entity));
                    byte[] original=compressed(snapshot);String filename=digest(original)+".nbt";Path destination=snapshotPath(filename);
                    if(Files.exists(destination)) {if(!Arrays.equals(original,Files.readAllBytes(destination))) throw new IOException("Recovery snapshot collision");}
                    else atomic(destination,original,false);
                    if(entity.hasKey("LootableBodiesUpgradePayload") || entity.hasKey("LootableBodiesInventorySource"))
                        throw new IOException("Unexpected intermediate corpse migration data on disk; original chunk has been preserved");
                    preserved.appendTag(new NBTTagString(filename));
                }
            }
        } catch(RuntimeException damaged) { throw new IOException("Cannot safely preserve "+relative,damaged); }
        NBTTagCompound record=new NBTTagCompound();record.setString("Path",relative);record.setString("Digest",hash);record.setTag("Snapshots",preserved);return record;
    }

    private NBTTagCompound readWorldVersions() throws IOException {
        NBTTagCompound context=new NBTTagCompound();
        Path level=world.resolve("level.dat");
        if(Files.isRegularFile(level)) {
            NBTTagCompound data=readCompressed(level).getCompoundTag("Data");
            if(data.hasKey("DataVersion")) context.setTag("WorldDataVersion",data.getTag("DataVersion").copy());
            if(data.hasKey("FML")) context.setTag("WorldFML",data.getTag("FML").copy());
        }
        return context;
    }

    private static NBTTagCompound sourceVersions(NBTTagCompound chunk,NBTTagCompound world,NBTTagCompound entity) {
        NBTTagCompound context=world.copy();
        if(chunk.hasKey("DataVersion")) context.setTag("ChunkDataVersion",chunk.getTag("DataVersion").copy());
        if(chunk.hasKey("ForgeDataVersion")) context.setTag("ForgeDataVersion",chunk.getTag("ForgeDataVersion").copy());
        int version=chunk.hasKey("DataVersion",3) ? chunk.getInteger("DataVersion") : -1;
        if(version<0 || version>922) version=world.hasKey("WorldDataVersion",3) ? world.getInteger("WorldDataVersion") : -1;
        // These identities only select the supported 1.10/1.11 source families.
        // Raw stamps are retained separately, even when damaged or unavailable.
        boolean legacy=entity.getString("id").equals("lootablebodies.Corpse");
        if(entity.hasKey("LootableBodiesDataVersion",3) && entity.getInteger("LootableBodiesDataVersion")==1) version=922;
        if(version<512 || version>922 || (legacy && version>512)) {version=legacy ? 512 : 922;context.setBoolean("Inferred",true);}
        context.setInteger("DataVersion",version);return context;
    }

    private Path snapshotPath(String filename) throws IOException {
        if(!filename.matches("[a-f0-9]{64}\\.nbt")) throw new IOException("Invalid recovery snapshot filename");
        return snapshots.resolve(filename);
    }
    private NBTTagCompound readSnapshot(Path path) throws IOException {
        if(!path.getFileName().toString().equals(digest(Files.readAllBytes(path))+".nbt")) throw new IOException("Corpse recovery checksum mismatch");
        NBTTagCompound nbt=readCompressed(path);
        if(!nbt.hasKey("Format",3) || nbt.getInteger("Format")!=FORMAT || !nbt.hasKey("Entity",10) || !nbt.hasKey("SourceVersions",10)
                || !nbt.hasKey("ChunkX",3) || !nbt.hasKey("ChunkZ",3) || !nbt.hasKey("Region",8) || !nbt.hasKey("RegionDigest",8)
                || !nbt.hasKey("Dimension",8) || !nbt.hasKey("OriginalChunk",8) || !nbt.hasKey("OriginalCompression",1) || !nbt.hasKey("EntityIndex",3)
                || !corpse(nbt.getCompoundTag("Entity"))) throw new IOException("Invalid original corpse snapshot");
        String original=nbt.getString("OriginalChunk");
        if(!original.matches("[a-f0-9]{64}") || (nbt.getByte("OriginalCompression")!=1 && nbt.getByte("OriginalCompression")!=2)
                || nbt.getInteger("EntityIndex")<0 || !original.equals(fileDigest(chunks.resolve(original+".bin"))))
            throw new IOException("Original compressed chunk checksum mismatch");
        uuid(nbt.getCompoundTag("Entity"));return nbt;
    }
    private static UUID uuid(NBTTagCompound entity) throws IOException {
        if(!entity.hasKey("UUIDMost",4) || !entity.hasKey("UUIDLeast",4)) throw new IOException("Corpse UUID is missing or malformed; original region has not been changed");
        return entity.getUniqueId("UUID");
    }
    private static boolean compoundList(NBTBase value) {return value instanceof NBTTagList && (((NBTTagList)value).tagCount()==0 || ((NBTTagList)value).getTagType()==10);}
    private static NBTTagCompound readCompressed(Path path) throws IOException {
        try(DataInputStream input=new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {return CompressedStreamTools.read(input,new NBTSizeTracker(MAX_NBT));}
        catch(RuntimeException damaged) {throw new IOException("Unreadable preservation data: "+path.getFileName(),damaged);}
    }
    private static byte[] compressed(NBTTagCompound nbt) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(bytes))) {CompressedStreamTools.write(nbt,out);}
        return bytes.toByteArray();
    }
    private static String digest(byte[] bytes) {
        try {StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String fileDigest(Path file) throws IOException {
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(file)) {byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1) sha.update(buffer,0,count);}
            StringBuilder hex=new StringBuilder();for(byte b:sha.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static void atomic(Path destination,byte[] bytes,boolean replace) throws IOException {
        Path temporary=Files.createTempFile(destination.getParent(),".pending-",".nbt");
        try(FileOutputStream output=new FileOutputStream(temporary.toFile())) {output.write(bytes);output.getChannel().force(true);}
        // A filesystem without atomic moves cannot satisfy the preservation gate.
        if(replace) Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        else Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);
    }
}
