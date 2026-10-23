package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.block.BlockSnow;
import net.minecraft.entity.EntityType;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Real block collision and chunk-provider regressions; never shipped with the mod. */
final class WallEscapeChecks {
    private interface Check { void run() throws Exception; }
    private static WorldServer world;
    private static int sequence, failures;
    private static final List<String> results = new ArrayList<>();

    static void run(MinecraftServer server, WorldServer target, File folder) throws Exception {
        world = target; sequence = 0; failures = 0; results.clear();
        check("centred_body_never_uses_NaN_as_destination", WallEscapeChecks::centred);
        for (EnumFacing direction : EnumFacing.values())
            check("only_clear_exit_" + direction, () -> oneExit(direction));
        check("off_centre_destination_checks_entire_body", WallEscapeChecks::overlap);
        check("snow_layer_is_not_clear_air", WallEscapeChecks::snow);
        check("fence_below_exit_is_not_clear_air", WallEscapeChecks::fence);
        check("sealed_body_waits_without_random_drift", WallEscapeChecks::sealed);
        check("already_clear_body_does_not_teleport", WallEscapeChecks::alreadyClear);
        check("negative_coordinates_keep_finite_safe_exit", WallEscapeChecks::negative);
        check("suffocation_damage_uses_escape_and_keeps_default_health", WallEscapeChecks::damage);
        for (EnumFacing direction : EnumFacing.Plane.HORIZONTAL)
            check("unloaded_exit_is_not_generated_" + direction, () -> unloaded(direction));
        check("ticking_sealed_body_waits_then_escapes_opened_wall", WallEscapeChecks::ticking);
        check("escaped_and_sealed_bodies_saved_for_cold_reload", () -> seed(folder));
        try (PrintWriter out = new PrintWriter(new File(folder, "wall-escape-checks.txt"), "UTF-8")) {
            for (String result : results) out.println(result);
            out.println("checks=" + results.size() + " failures=" + failures);
        }
        RuntimeChecks.require(failures == 0, "Wall escape regressions: " + failures + " / " + results.size());
        System.out.println("LOOTABLE_BODIES_WALL_ESCAPE_PASS checks=" + results.size());
    }

    private static void check(String name, Check test) {
        try { test.run(); results.add("PASS " + name); System.out.println("WALL_ESCAPE_PASS " + name); }
        catch (Throwable error) {
            failures++; results.add("FAIL " + name + ": " + error);
            System.out.println("WALL_ESCAPE_FAIL " + name + ": " + error); error.printStackTrace();
        }
    }

    private static BlockPos site(boolean negative) {
        int x = (200 + sequence++ * 4) * 16 + 8;
        return new BlockPos(negative ? -x : x, 80, 3208);
    }

    private static void terrain(BlockPos centre, boolean solid) {
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 3; y++) for (int z = -2; z <= 2; z++)
            world.setBlockState(centre.add(x,y,z), (solid || y == -1 ? Blocks.STONE : Blocks.AIR).getDefaultState(), 2);
    }

    private static EntityLootableBody body(BlockPos centre) {
        EntityLootableBody body = new EntityLootableBody(world);
        body.setPosition(centre.getX()+.5, centre.getY()+.015625, centre.getZ()+.5);
        body.setNoGravity(true); body.setUserName("Wall Escape Fixture");
        for (int slot = 0; slot < 48; slot++) body.setInventorySlotContents(slot, RuntimeChecks.stack(slot));
        body.addItem(RuntimeChecks.stack(70)); body.addItem(RuntimeChecks.stack(71));
        RuntimeChecks.disableVacuum(body);
        NBTTagCompound saved = NameLifetimeChecks.saved(body);
        NBTTagCompound unresolved = new NBTTagCompound();
        unresolved.setString("Source", "FutureFixture"); unresolved.setString("Data", "retain verbatim");
        saved.setTag("LootableBodiesUnresolved", NbtRecoveryChecks.list(unresolved));
        NBTTagCompound invalid = new NBTTagCompound();
        invalid.setString("Source", "FutureMetadata"); invalid.setTag("Data", new NBTTagByteArray(new byte[]{4,3,2,1}));
        saved.setTag("LootableBodiesInvalidFields", NbtRecoveryChecks.list(invalid));
        return (EntityLootableBody) EntityType.create(saved, world);
    }

    private static void unchanged(NBTTagCompound before, EntityLootableBody body) {
        NBTTagCompound after = NameLifetimeChecks.saved(body);
        before = before.copy(); before.removeTag("Pos"); after.removeTag("Pos");
        RuntimeChecks.require(NameLifetimeChecks.same(before, after), "Escape changed saved fields besides position");
        PositionMotionChecks.finite(body);
    }

    private static void clear(EntityLootableBody body) {
        PositionMotionChecks.finite(body);
        RuntimeChecks.require(world.isCollisionBoxesEmpty(body,body.getBoundingBox()), "Escape left body intersecting terrain");
    }

    private static void centred() {
        BlockPos centre = site(false); terrain(centre, false); world.setBlockState(centre, Blocks.STONE.getDefaultState(), 2);
        // A zero-vector lookup previously sampled (0,0,0). Test it as air, too.
        net.minecraft.block.state.IBlockState origin = world.getBlockState(BlockPos.ORIGIN);
        try {
            world.removeBlock(BlockPos.ORIGIN);
            EntityLootableBody body = body(centre); NBTTagCompound before = NameLifetimeChecks.saved(body);
            body.jumpOutOfWall(); clear(body); unchanged(before, body);
            RuntimeChecks.require(body.posY == centre.getY()+1.015625, "Centred body missed the existing upward exit priority");
        } finally { world.setBlockState(BlockPos.ORIGIN, origin, 2); }
    }

    private static void oneExit(EnumFacing direction) {
        BlockPos centre = site(false); terrain(centre, true); BlockPos exit = centre.offset(direction); world.removeBlock(exit);
        EntityLootableBody body = body(centre); NBTTagCompound before = NameLifetimeChecks.saved(body);
        body.jumpOutOfWall(); clear(body); unchanged(before, body);
        RuntimeChecks.require(body.posX == exit.getX()+.5 && body.posY == exit.getY()+.015625 && body.posZ == exit.getZ()+.5, "Missed sole adjacent exit");
    }

    private static void overlap() {
        BlockPos centre = site(false); terrain(centre, false); world.setBlockState(centre, Blocks.STONE.getDefaultState(), 2);
        EntityLootableBody body = body(centre); body.setPosition(centre.getX()+.1, centre.getY()+.015625, centre.getZ()+.5);
        NBTTagCompound before = NameLifetimeChecks.saved(body); body.jumpOutOfWall(); clear(body); unchanged(before, body);
    }

    private static void snow() {
        BlockPos centre = site(false); terrain(centre, true); world.removeBlock(centre.north());
        world.setBlockState(centre.up(), Blocks.SNOW.getDefaultState().with(net.minecraft.block.BlockSnowLayer.LAYERS, 8), 2);
        EntityLootableBody body = body(centre); body.jumpOutOfWall(); clear(body);
        RuntimeChecks.require(body.getPosition().equals(centre.north()), "Snow collision was ignored");
    }

    private static void fence() {
        BlockPos centre = site(false); terrain(centre, true); world.removeBlock(centre.north()); world.removeBlock(centre.east());
        world.setBlockState(centre.north().down(), Blocks.OAK_FENCE.getDefaultState(), 2);
        EntityLootableBody body = body(centre); body.jumpOutOfWall(); clear(body);
        RuntimeChecks.require(body.getPosition().equals(centre.east()), "Tall collision below destination was ignored");
    }

    private static void sealed() {
        BlockPos centre = site(false); terrain(centre, true); EntityLootableBody body = body(centre);
        NBTTagCompound before = NameLifetimeChecks.saved(body);
        for (int i = 0; i < 32; i++) body.jumpOutOfWall();
        RuntimeChecks.require(NameLifetimeChecks.same(before, NameLifetimeChecks.saved(body)), "Sealed corpse drifted or changed saved data");
        PositionMotionChecks.finite(body);
    }

    private static void alreadyClear() {
        BlockPos centre = site(false); terrain(centre, false); EntityLootableBody body = body(centre);
        NBTTagCompound before = NameLifetimeChecks.saved(body); body.jumpOutOfWall();
        RuntimeChecks.require(NameLifetimeChecks.same(before, NameLifetimeChecks.saved(body)), "Clear corpse teleported on stale suffocation damage");
    }

    private static void negative() {
        BlockPos centre = site(true); terrain(centre, true); world.removeBlock(centre.west());
        EntityLootableBody body = body(centre); NBTTagCompound before = NameLifetimeChecks.saved(body);
        body.jumpOutOfWall(); clear(body); unchanged(before, body);
        RuntimeChecks.require(body.getPosition().equals(centre.west()), "Negative coordinate exit was rounded incorrectly");
    }

    private static void damage() {
        BlockPos centre = site(false); terrain(centre, true); world.removeBlock(centre.up());
        EntityLootableBody body = body(centre); float health = body.getHealth();
        body.attackEntityFrom(DamageSource.IN_WALL, 1); clear(body);
        RuntimeChecks.require(body.isAlive() && body.getHealth() == health, "Default suffocation health rules changed");
    }

    private static void unloaded(EnumFacing direction) throws Exception {
        BlockPos centre = site(false);
        int x = centre.getX() >> 4, z = centre.getZ() >> 4;
        centre = new BlockPos(x*16+(direction == EnumFacing.WEST ? 0 : direction == EnumFacing.EAST ? 15 : 8), 80,
                z*16+(direction == EnumFacing.NORTH ? 0 : direction == EnumFacing.SOUTH ? 15 : 8));
        terrain(centre, true); world.removeBlock(centre.offset(direction));
        int nx = x+direction.getXOffset(), nz = z+direction.getZOffset();
        world.getChunkProvider().queueUnload(world.getChunk(nx,nz));
        int attempts = world.getChunkProvider().getLoadedChunks().size()/100+2;
        for (int i = 0; i < attempts && world.getChunkProvider().chunkExists(nx,nz); i++) world.getChunkProvider().tick(()->true);
        net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        RuntimeChecks.require(!world.getChunkProvider().chunkExists(nx,nz), "Fixture neighbour did not unload");
        EntityLootableBody body = body(centre); NBTTagCompound before = NameLifetimeChecks.saved(body);
        body.jumpOutOfWall();
        RuntimeChecks.require(!world.getChunkProvider().chunkExists(nx,nz), "Escape forced an unloaded neighbour to load");
        RuntimeChecks.require(NameLifetimeChecks.same(before, NameLifetimeChecks.saved(body)), "Escape moved without loaded terrain");
        world.getChunk(nx,nz); body.jumpOutOfWall(); clear(body); unchanged(before, body);
    }

    private static void ticking() {
        BlockPos centre = site(false); terrain(centre, true); EntityLootableBody body = body(centre);
        NBTTagCompound before = NameLifetimeChecks.saved(body);
        for (int i = 0; i < 100; i++) body.tick();
        RuntimeChecks.require(body.posX == centre.getX()+.5 && body.posY == centre.getY()+.015625 && body.posZ == centre.getZ()+.5 && body.isAlive(), "Ticking sealed corpse drifted or died");
        for (String key : new String[]{"Equipment","Aux","LootableBodiesUnresolved","LootableBodiesInvalidFields","DeathTime","DecayTime"})
            RuntimeChecks.require(NameLifetimeChecks.same(before.getTag(key),NameLifetimeChecks.saved(body).getTag(key)), "Ticking escape changed " + key);
        world.removeBlock(centre.up());
        for (int i = 0; i < 30; i++) body.tick();
        clear(body); RuntimeChecks.require(body.getPosition().equals(centre.up()), "Ticking corpse did not use newly opened exit");
    }

    private static void seed(File folder) throws Exception {
        for (boolean trapped : new boolean[]{false,true}) {
            BlockPos centre = site(false); terrain(centre, true);
            if (!trapped) world.removeBlock(centre.up());
            EntityLootableBody body = body(centre);
            body.setUniqueId(UUID.nameUUIDFromBytes(("wall-escape-"+trapped).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            NBTTagCompound before = NameLifetimeChecks.saved(body); body.jumpOutOfWall(); unchanged(before,body);
            if (!trapped) clear(body);
            else RuntimeChecks.require(NameLifetimeChecks.same(before,NameLifetimeChecks.saved(body)), "Sealed disk fixture moved");
            RuntimeChecks.awaitChunk(world,body.getPosition().getX()>>4,body.getPosition().getZ()>>4);
            RuntimeChecks.require(world.spawnEntity(body), "Could not save wall fixture");
            CompressedStreamTools.writeCompressed(NameLifetimeChecks.saved(body), new FileOutputStream(new File(folder,"wall-escape-"+trapped+".nbt")));
        }
    }

    static void reload(WorldServer target, File folder) throws Exception {
        world = target;
        for (boolean trapped : new boolean[]{false,true}) {
            NBTTagCompound expected;
            try (InputStream in = new FileInputStream(new File(folder,"wall-escape-"+trapped+".nbt"))) { expected = CompressedStreamTools.readCompressed(in); }
            NBTTagList pos = expected.getList("Pos",6);
            world.getChunk(new BlockPos(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2)));
            EntityLootableBody body = RuntimeChecks.find(world, expected.getUniqueId("UUID"));
            RuntimeChecks.require(NameLifetimeChecks.same(expected,NameLifetimeChecks.saved(body)), "Cold reload changed wall fixture NBT");
            body.jumpOutOfWall();
            RuntimeChecks.require(NameLifetimeChecks.same(expected,NameLifetimeChecks.saved(body)), "Repeated escape changed cold-loaded fixture");
            if (!trapped) clear(body);
        }
        System.out.println("LOOTABLE_BODIES_WALL_ESCAPE_RELOAD_PASS bodies=2");
    }

    /** The sealed 2.2.0 Nether fixture that originally exposed random wall drift. */
    static void historical(MinecraftServer server, File folder, boolean reload) throws Exception {
        world = server.getWorld(net.minecraft.world.dimension.DimensionType.getById(-1));
        for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) world.getChunk(x,z);
        EntityLootableBody body = null;
        for (net.minecraft.entity.Entity entity : world.loadedEntityList)
            if (entity instanceof EntityLootableBody && Math.abs(entity.posX-128.5)<.001 && Math.abs(entity.posZ-128.5)<.001)
                body = (EntityLootableBody) entity;
        RuntimeChecks.require(body != null, "Original Nether corpse not found at its saved position");
        RuntimeChecks.require(!world.isCollisionBoxesEmpty(body,body.getBoundingBox()), "Historical buried-body terrain was cleared");
        NBTTagCompound expected = NameLifetimeChecks.saved(body);
        File file = new File(folder,"wall-historical.nbt");
        if (reload) {
            try (InputStream in = new FileInputStream(file)) {
                RuntimeChecks.require(NameLifetimeChecks.same(CompressedStreamTools.readCompressed(in),expected), "Historical cold reload changed complete corpse NBT");
            }
        }
        for (int i = 0; i < 32; i++) body.jumpOutOfWall();
        RuntimeChecks.require(NameLifetimeChecks.same(expected,NameLifetimeChecks.saved(body)), "Historical sealed corpse drifted or lost saved data");
        PositionMotionChecks.finite(body);
        if (!reload) try (OutputStream out = new FileOutputStream(file)) { CompressedStreamTools.writeCompressed(expected,out); }
        System.out.println("LOOTABLE_BODIES_WALL_HISTORICAL_PASS reload="+reload+" slots="+body.getSizeInventory());
    }
}
