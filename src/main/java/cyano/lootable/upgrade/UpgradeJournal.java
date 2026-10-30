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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Read-only Anvil inspection and immutable, checksummed corpse originals. */
public final class UpgradeJournal {
    private static final int FORMAT=2;
    private static final long MAX_NBT=64L*1024*1024;
    private static final Pattern REGION=Pattern.compile("r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca");
    private final Path world, directory, snapshots, chunks;
    private final Map<UUID,Path> active=new HashMap<>();
    private CompoundTag worldContext=new CompoundTag();

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
        for(Path original:journal.active.values()) {
            CompoundTag corpse=journal.readSnapshot(original).getCompound("Entity");
            if(corpse.contains(UpgradePreservation.VERSION,3) && corpse.getInt(UpgradePreservation.VERSION)>UpgradePreservation.DATA_VERSION)
                throw new IOException("This world contains a newer Lootable Bodies corpse format; originals have been preserved");
        }
        return journal;
    }

    public int size() { return active.size(); }
    CompoundTag worldVersions(){return worldContext.copy();}

    /** Validate immutable copies before native async entity reads can become PENDING. */
    void validateEntityChunks(com.mojang.datafixers.DataFixer fixer) throws IOException {
        Set<String> checked=new HashSet<>();
        List<Path> originals=new ArrayList<>(active.values());originals.sort(Comparator.comparing(Path::toString));
        for(Path original:originals) {
            CompoundTag snapshot=readSnapshot(original);
            Path relative=Paths.get(snapshot.getString("Region"));
            if(relative.getParent()==null || !relative.getParent().getFileName().toString().equals("entities"))continue;
            String key=relative.toString()+":"+snapshot.getString("OriginalChunk");
            if(!checked.add(key))continue;
            Path region=world.resolve(relative).normalize();
            if(relative.isAbsolute() || !region.startsWith(world))throw new IOException("Entity region is outside the world");
            String expected=snapshot.getString("RegionDigest");
            if(!expected.equals(fileDigest(region)))throw new IOException("Entity region changed before conversion validation: "+relative);
            byte[] bytes=Files.readAllBytes(chunks.resolve(snapshot.getString("OriginalChunk")+".bin"));
            InputStream stream=snapshot.getByte("OriginalCompression")==1?new GZIPInputStream(new ByteArrayInputStream(bytes)):
                    new InflaterInputStream(new ByteArrayInputStream(bytes));
            CompoundTag root;
            try(DataInputStream input=new DataInputStream(stream)){root=NbtIo.read(input,new NbtAccounter(MAX_NBT));}
            try {
                NativeNbt.update(fixer,net.minecraft.util.datafix.fixes.References.ENTITY_CHUNK,root,
                        root.contains("DataVersion",3)?root.getInt("DataVersion"):-1,UpgradePreservation.MINECRAFT_VERSION);
            } catch(RuntimeException unsafe) {
                throw new IOException("Cannot safely convert preserved entity chunk before world loading: "+relative,unsafe);
            }
            if(!expected.equals(fileDigest(region)))throw new IOException("Entity region changed during conversion validation: "+relative);
        }
    }

    /** Only an entity already present in this chunk may consume its original. */
    public CompoundTag originalFor(UUID id,int chunkX,int chunkZ) throws IOException {
        Path path=active.get(id);
        if(path==null) return null;
        CompoundTag original=readSnapshot(path);
        return original.getInt("ChunkX")==chunkX && original.getInt("ChunkZ")==chunkZ ? original : null;
    }

    CompoundTag originalFor(UUID id) throws IOException {
        Path path=active.get(id);
        return path==null ? null : readSnapshot(path);
    }

    static boolean corpse(CompoundTag entity) {
        String id=entity.getString("id");
        return id.equals("lootablebodies:corpse") || id.equals("lootablebodies.Corpse");
    }

    static final class LocatedCorpse {
        final CompoundTag entity;final int root;final int[] passengers;
        LocatedCorpse(CompoundTag entity,int root,int[] passengers){this.entity=entity;this.root=root;this.passengers=passengers;}
    }
    static List<LocatedCorpse> corpses(ListTag roots) {
        List<LocatedCorpse> result=new ArrayList<>();
        for(int root=0;root<roots.size();root++)collect(roots.getCompound(root),root,new int[0],result);
        return result;
    }
    private static void collect(CompoundTag entity,int root,int[] path,List<LocatedCorpse> result) {
        if(path.length>512)throw new IllegalArgumentException("Corpse passenger tree is too deep");
        if(corpse(entity))result.add(new LocatedCorpse(entity,root,path));
        ListTag passengers=entity.getList("Passengers",10);
        for(int i=0;i<passengers.size();i++){int[] nested=Arrays.copyOf(path,path.length+1);nested[path.length]=i;collect(passengers.getCompound(i),root,nested,result);}
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
                if(Files.isRegularFile(path) && (path.getParent().getFileName().toString().equals("region")
                        || path.getParent().getFileName().toString().equals("entities"))
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
        Map<String,CompoundTag> previous=readIndex();
        CompoundTag worldVersions=readWorldVersions();worldContext=worldVersions.copy();
        ListTag updated=new ListTag();
        for(Path region:regions) {
            String relative=world.relativize(region).toString().replace('\\','/');
            String hash=fileDigest(region);
            CompoundTag record=previous.get(relative);
            if(record==null || !hash.equals(record.getString("Digest")))
                record=scanRegion(region,relative,hash,worldVersions,record);
            else record=record.copy();
            ListTag originals=record.getList("Snapshots",8);
            for(int i=0;i<originals.size();i++) {
                Path path=snapshotPath(originals.getString(i));
                CompoundTag original=readSnapshot(path);
                if(!relative.equals(original.getString("Region")) || !hash.equals(original.getString("RegionDigest")))
                    throw new IOException("Recovery index does not match its source region");
                CompoundTag entity=original.getCompound("Entity");
                UUID uuid=uuid(entity);
                if(active.put(uuid,path)!=null) throw new IOException("Duplicate corpse UUID across saved chunks: "+uuid);
            }
            // Detect another writer during inspection, before trusting the scan.
            if(!hash.equals(fileDigest(region))) throw new IOException("Region changed during corpse preservation: "+relative);
            updated.add(record);
        }
        CompoundTag index=new CompoundTag();index.putInt("Format",FORMAT);index.put("Regions",updated);
        byte[] payload=compressed(index);
        CompoundTag envelope=new CompoundTag();envelope.putInt("Format",FORMAT);
        envelope.putString("Digest",digest(payload));envelope.putByteArray("Payload",payload);
        atomic(directory.resolve("index.nbt"),compressed(envelope),true);
    }

    private Map<String,CompoundTag> readIndex() throws IOException {
        Map<String,CompoundTag> records=new HashMap<>();
        Path index=directory.resolve("index.nbt");
        if(!Files.exists(index)) return records;
        CompoundTag envelope=readCompressed(index);
        byte[] payload=envelope.getByteArray("Payload");
        if(!envelope.contains("Format",3) || envelope.getInt("Format")!=FORMAT
                || !envelope.contains("Digest",8) || !envelope.contains("Payload",7)
                || !digest(payload).equals(envelope.getString("Digest")))
            throw new IOException("Lootable Bodies upgrade index checksum mismatch");
        CompoundTag nbt;
        try(DataInputStream input=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            nbt=NbtIo.read(input,new NbtAccounter(MAX_NBT));
        }
        if(!nbt.contains("Format",3) || nbt.getInt("Format")!=FORMAT || !compoundList(nbt.get("Regions")))
            throw new IOException("Invalid Lootable Bodies upgrade index");
        ListTag regions=nbt.getList("Regions",10);
        for(int i=0;i<regions.size();i++) {
            CompoundTag record=regions.getCompound(i);
            if(!record.contains("Path",8) || !record.contains("Digest",8) || !record.getString("Digest").matches("[a-f0-9]{64}")
                    || !(record.get("Snapshots") instanceof ListTag)) throw new IOException("Invalid recovery region record");
            ListTag list=(ListTag)record.get("Snapshots");
            if(list.size()>0 && list.getElementType()!=8) throw new IOException("Invalid recovery snapshot list");
            if(records.put(record.getString("Path"),record)!=null) throw new IOException("Duplicate recovery region record");
            for(int j=0;j<list.size();j++) readSnapshot(snapshotPath(list.getString(j)));
        }
        return records;
    }

    private CompoundTag scanRegion(Path file,String relative,String hash,CompoundTag worldVersions,CompoundTag previous) throws IOException {
        boolean entityRegion=file.getParent().getFileName().toString().equals("entities");
        Matcher name=REGION.matcher(file.getFileName().toString());
        if(!name.matches()) throw new IOException("Invalid region name");
        long rx=Long.parseLong(name.group(1)),rz=Long.parseLong(name.group(2));
        if(rx<Integer.MIN_VALUE/32 || rx>Integer.MAX_VALUE/32 || rz<Integer.MIN_VALUE/32 || rz>Integer.MAX_VALUE/32)
            throw new IOException("Region coordinates out of range");
        ListTag preserved=new ListTag();
        Map<Long,CompoundTag> prior=new HashMap<>();
        Map<String,CompoundTag> priorLevels=new HashMap<>();
        Map<Long,Boolean> matchingChunks=new HashMap<>();
        if(previous!=null) {
            ListTag originals=previous.getList("Snapshots",8);
            for(int i=0;i<originals.size();i++) {
                CompoundTag original=readSnapshot(snapshotPath(originals.getString(i))),versions=original.getCompound("SourceVersions");
                if(versions.getBoolean("Ambiguous"))continue;
                long key=chunkKey(original.getInt("ChunkX"),original.getInt("ChunkZ"));
                CompoundTag selected=prior.get(key);
                if(selected==null || UpgradePreservation.chunkSourceVersion(versions)>UpgradePreservation.chunkSourceVersion(selected.getCompound("SourceVersions")))prior.put(key,original);
            }
        }
        try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")) {
            long length=input.length();
            // Native16 leaves a zero-byte placeholder when a region is opened
            // only to look up an absent chunk. It contains no allocated data.
            if(length==0)return regionRecord(relative,hash,preserved);
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
                CompoundTag chunk;
                try(DataInputStream nbt=new DataInputStream(stream)) { chunk=NbtIo.read(nbt,new NbtAccounter(MAX_NBT)); }
                catch(RuntimeException damaged) { throw new IOException("Unreadable chunk in "+relative,damaged); }
                int x=(int)rx*32+(i%32),z=(int)rz*32+(i/32);
                CompoundTag level;
                if(entityRegion) {
                    int[] position=chunk.getIntArray("Position");
                    if(!chunk.contains("Position",11) || position.length!=2 || position[0]!=x || position[1]!=z)
                        throw new IOException("Entity chunk Position mismatch in "+relative);
                    level=chunk;
                } else {
                    if(!chunk.contains("Level",10))throw new IOException("Missing chunk Level: "+relative);
                    level=chunk.getCompound("Level");
                    if(!level.contains("xPos",3) || !level.contains("zPos",3) || level.getInt("xPos")!=x || level.getInt("zPos")!=z)
                        throw new IOException("Chunk coordinate mismatch in "+relative);
                }
                if(entityRegion && !level.contains("Entities"))throw new IOException("Missing native entity list in "+relative);
                if(!level.contains("Entities")) continue;
                if(!compoundList(level.get("Entities"))) throw new IOException("Unreadable entity list in "+relative);
                ListTag entities=level.getList("Entities",10);
                String originalChunk=digest(payload);
                for(LocatedCorpse located:corpses(entities)) {
                    CompoundTag entity=located.entity;int e=located.root;
                    uuid(entity); // An ambiguous identity cannot safely select a backup.
                    CompoundTag snapshot=new CompoundTag();snapshot.putInt("Format",FORMAT);snapshot.putString("Region",relative);snapshot.putString("RegionDigest",hash);
                    Path chunkFile=chunks.resolve(originalChunk+".bin");
                    if(!Files.exists(chunkFile)) atomic(chunkFile,payload,false);
                    else if(!Arrays.equals(payload,Files.readAllBytes(chunkFile))) throw new IOException("Original chunk checksum collision");
                    snapshot.putString("OriginalChunk",originalChunk);snapshot.putByte("OriginalCompression",(byte)compression);snapshot.putInt("EntityIndex",e);
                    if(located.passengers.length>0)snapshot.putIntArray("PassengerPath",located.passengers);
                    snapshot.putString("Dimension",world.relativize(file.getParent().getParent()).toString().replace('\\','/'));
                    snapshot.putInt("ChunkX",x);snapshot.putInt("ChunkZ",z);snapshot.put("Entity",entity.copy());
                    CompoundTag versions=sourceVersions(chunk,worldVersions,entity),priorSnapshot=prior.get(chunkKey(x,z));
                    if(priorSnapshot!=null) {
                        String savedChunk=priorSnapshot.getString("OriginalChunk");
                        CompoundTag oldLevel=priorLevels.get(savedChunk);
                        if(oldLevel==null) {
                            byte[] originalBytes=Files.readAllBytes(chunks.resolve(savedChunk+".bin"));
                            InputStream originalStream=priorSnapshot.getByte("OriginalCompression")==1?new GZIPInputStream(new ByteArrayInputStream(originalBytes)):new InflaterInputStream(new ByteArrayInputStream(originalBytes));
                            try(DataInputStream originalInput=new DataInputStream(originalStream)){oldLevel=comparisonPayload(NbtIo.read(originalInput,new NbtAccounter(MAX_NBT)),entityRegion);}
                            priorLevels.put(savedChunk,oldLevel);
                        }
                        // Only the outer version stamp may differ for entity chunks.
                        // Never substitute old entities, Position or unknown payloads.
                        long key=chunkKey(x,z);Boolean same=matchingChunks.get(key);
                        if(same==null){same=sameNbt(oldLevel,comparisonPayload(chunk,entityRegion));matchingChunks.put(key,same);}
                        if(same)versions.putInt("VerifiedChunkDataVersion",UpgradePreservation.chunkSourceVersion(priorSnapshot.getCompound("SourceVersions")));
                    }
                    snapshot.put("SourceVersions",versions);
                    byte[] original=compressed(snapshot);String filename=digest(original)+".nbt";Path destination=snapshotPath(filename);
                    if(Files.exists(destination)) {if(!Arrays.equals(original,Files.readAllBytes(destination))) throw new IOException("Recovery snapshot collision");}
                    else atomic(destination,original,false);
                    if(entity.contains("LootableBodiesUpgradePayload") || entity.contains("LootableBodiesInventorySource"))
                        throw new IOException("Unexpected intermediate corpse migration data on disk; original chunk has been preserved");
                    preserved.add(StringTag.valueOf(filename));
                }
            }
        } catch(RuntimeException damaged) { throw new IOException("Cannot safely preserve "+relative,damaged); }
        return regionRecord(relative,hash,preserved);
    }
    private static CompoundTag regionRecord(String relative,String hash,ListTag preserved) {
        CompoundTag record=new CompoundTag();record.putString("Path",relative);record.putString("Digest",hash);record.put("Snapshots",preserved);return record;
    }
    private static CompoundTag comparisonPayload(CompoundTag chunk,boolean entityRegion) {
        if(!entityRegion)return chunk.getCompound("Level");
        CompoundTag payload=chunk.copy();payload.remove("DataVersion");return payload;
    }

    private CompoundTag readWorldVersions() throws IOException {
        CompoundTag context=new CompoundTag();
        Path level=world.resolve("level.dat");
        if(Files.isRegularFile(level)) {
            CompoundTag root=readCompressed(level),data=root.getCompound("Data");
            if(data.contains("DataVersion")) context.put("WorldDataVersion",data.get("DataVersion").copy());
            if(data.contains("Version"))context.put("WorldVersion",data.get("Version").copy());
            if(root.contains("FML")) context.put("WorldFML",root.get("FML").copy());
            else if(data.contains("FML")) context.put("WorldFML",data.get("FML").copy());
            else if(root.contains("fml")) context.put("WorldFML",root.get("fml").copy());
            else if(data.contains("fml")) context.put("WorldFML",data.get("fml").copy());
        }
        return context;
    }

    private static CompoundTag sourceVersions(CompoundTag chunk,CompoundTag world,CompoundTag entity) {
        CompoundTag context=world.copy();
        if(chunk.contains("DataVersion")) context.put("ChunkDataVersion",chunk.get("DataVersion").copy());
        if(chunk.contains("ForgeDataVersion")) context.put("ForgeDataVersion",chunk.get("ForgeDataVersion").copy());
        int version=chunk.contains("DataVersion",3) ? chunk.getInt("DataVersion") : -1;
        if(version<512 || version>UpgradePreservation.MINECRAFT_VERSION) version=world.contains("WorldDataVersion",3) ? world.getInt("WorldDataVersion") : -1;
        // Dotted IDs and our own per-corpse markers identify known source families.
        // A lowercase ID alone cannot distinguish original 1.11 and 1.12 saves.
        boolean legacy=entity.getString("id").equals("lootablebodies.Corpse");
        int marker=entity.contains(UpgradePreservation.VERSION,3) ? entity.getInt(UpgradePreservation.VERSION) : 0;
        if(marker==1) version=922;
        else if(marker==2)version=1343;
        else if(marker==3)version=1631;
        else if(marker==4)version=1976;
        else if(marker==5)version=2230;
        else if(marker==6)version=2586;
        else if(marker==7)version=2730;
        if(legacy) {if(version!=512)context.putBoolean("Inferred",true);version=512;}
        if(legacy || (marker>=1 && marker<=UpgradePreservation.DATA_VERSION))
            context.putInt("CorpseWriterDataVersion",version);
        int family=-1;
        ListTag mods=UpgradePreservation.modList(world.getCompound("WorldFML"));
        for(int i=0;i<mods.size();i++) {
            CompoundTag mod=mods.getCompound(i);if(!mod.getString("ModId").equals("lootablebodies"))continue;
            String release=mod.getString("ModVersion");
            if(release.endsWith(".117011"))family=2730;
            else if(release.endsWith(".116051"))family=2586;
            else if(release.endsWith(".115021"))family=2230;
            else if(release.endsWith(".114041"))family=1976;
            else if(release.endsWith(".113021"))family=1631;
            else if(release.endsWith(".112021") || release.equals("2.4.0"))family=1139;
            else if(release.endsWith(".111021") || release.equals("2.3.0"))family=922;
            else if(release.endsWith(".110021") || release.equals("2.2.0"))family=512;
        }
        if(version<512 || version>UpgradePreservation.MINECRAFT_VERSION) {
            version=family;context.putBoolean("Inferred",true);
        } else if(marker==0 && !legacy && family>=512 && ((family<1125 && version>family) || (family<1451 && version>=1451))) {
            // An original older writer cannot have emitted a newer item format.
            // Its retained FML metadata disambiguates a falsely current stamp.
            version=family;context.putBoolean("Inferred",true);
        }
        if(version<512 || version>UpgradePreservation.MINECRAFT_VERSION) {
            // Keep ambiguous records retriable without guessing a bed colour or
            // discarding known items whose conversion agrees in both families.
            context.putBoolean("Ambiguous",true);context.putIntArray("CandidateDataVersions",new int[]{922,1343,1631,1976,2230,2586});version=922;
        }
        context.putInt("DataVersion",version);return context;
    }

    private static long chunkKey(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static boolean sameNbt(Tag a,Tag b) {
        if(a==null || b==null)return a==b;
        if(a.getId()!=b.getId())return false;
        if(a instanceof DoubleTag)return Double.doubleToRawLongBits(((DoubleTag)a).getAsDouble())==Double.doubleToRawLongBits(((DoubleTag)b).getAsDouble());
        if(a instanceof FloatTag)return Float.floatToRawIntBits(((FloatTag)a).getAsFloat())==Float.floatToRawIntBits(((FloatTag)b).getAsFloat());
        if(a instanceof CompoundTag) {
            CompoundTag x=(CompoundTag)a,y=(CompoundTag)b;
            if(!x.getAllKeys().equals(y.getAllKeys()))return false;
            for(String key:x.getAllKeys())if(!sameNbt(x.get(key),y.get(key)))return false;
            return true;
        }
        if(a instanceof ListTag) {
            ListTag x=(ListTag)a,y=(ListTag)b;
            if(x.getElementType()!=y.getElementType() || x.size()!=y.size())return false;
            for(int i=0;i<x.size();i++)if(!sameNbt(x.get(i),y.get(i)))return false;
            return true;
        }
        return a.equals(b);
    }

    private Path snapshotPath(String filename) throws IOException {
        if(!filename.matches("[a-f0-9]{64}\\.nbt")) throw new IOException("Invalid recovery snapshot filename");
        return snapshots.resolve(filename);
    }
    private CompoundTag readSnapshot(Path path) throws IOException {
        if(!path.getFileName().toString().equals(digest(Files.readAllBytes(path))+".nbt")) throw new IOException("Corpse recovery checksum mismatch");
        CompoundTag nbt=readCompressed(path);
        if(!nbt.contains("Format",3) || nbt.getInt("Format")!=FORMAT || !nbt.contains("Entity",10) || !nbt.contains("SourceVersions",10)
                || !nbt.contains("ChunkX",3) || !nbt.contains("ChunkZ",3) || !nbt.contains("Region",8) || !nbt.contains("RegionDigest",8)
                || !nbt.contains("Dimension",8) || !nbt.contains("OriginalChunk",8) || !nbt.contains("OriginalCompression",1) || !nbt.contains("EntityIndex",3)
                || !corpse(nbt.getCompound("Entity"))) throw new IOException("Invalid original corpse snapshot");
        String original=nbt.getString("OriginalChunk");
        if(!original.matches("[a-f0-9]{64}") || (nbt.getByte("OriginalCompression")!=1 && nbt.getByte("OriginalCompression")!=2)
                || nbt.getInt("EntityIndex")<0 || !original.equals(fileDigest(chunks.resolve(original+".bin"))))
            throw new IOException("Original compressed chunk checksum mismatch");
        uuid(nbt.getCompound("Entity"));return nbt;
    }
    private static UUID uuid(CompoundTag entity) throws IOException {
        try {return CorpseIdentity.read(entity);}
        catch(IllegalArgumentException invalid) {
            throw new IOException("Corpse UUID is missing, conflicting or malformed; original region has not been changed",invalid);
        }
    }
    private static boolean compoundList(Tag value) {return value instanceof ListTag && (((ListTag)value).size()==0 || ((ListTag)value).getElementType()==10);}
    private static CompoundTag readCompressed(Path path) throws IOException {
        try(DataInputStream input=new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {return NbtIo.read(input,new NbtAccounter(MAX_NBT));}
        catch(RuntimeException damaged) {throw new IOException("Unreadable preservation data: "+path.getFileName(),damaged);}
    }
    private static byte[] compressed(CompoundTag nbt) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(bytes))) {NbtIo.write(nbt,out);}
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
