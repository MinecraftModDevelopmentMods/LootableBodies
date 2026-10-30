package cyano.lootable.buildtest;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteArrayTag;

import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.util.*;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Real block collision and chunk-provider regressions; never shipped with the mod. */
final class WallEscapeChecks {
    private interface Check { void run() throws Exception; }
    private static ServerLevel world;
    private static int sequence, failures;
    private static final List<String> results = new ArrayList<>();

    static void run(MinecraftServer server, ServerLevel target, File folder) throws Exception {
        world = target; sequence = 0; failures = 0; results.clear();
        check("centred_body_never_uses_NaN_as_destination", WallEscapeChecks::centred);
        for (Direction direction : Direction.values())
            check("only_clear_exit_" + direction, () -> oneExit(direction));
        check("off_centre_destination_checks_entire_body", WallEscapeChecks::overlap);
        check("snow_layer_is_not_clear_air", WallEscapeChecks::snow);
        check("fence_below_exit_is_not_clear_air", WallEscapeChecks::fence);
        check("sealed_body_waits_without_random_drift", WallEscapeChecks::sealed);
        check("already_clear_body_does_not_teleport", WallEscapeChecks::alreadyClear);
        check("negative_coordinates_keep_finite_safe_exit", WallEscapeChecks::negative);
        check("suffocation_damage_uses_escape_and_keeps_default_health", WallEscapeChecks::damage);
        for (Direction direction : Direction.Plane.HORIZONTAL)
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
            world.setBlock(centre.offset(x,y,z), (solid || y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
    }

    private static EntityLootableBody body(BlockPos centre) {
        EntityLootableBody body = new EntityLootableBody(world);
        body.setPos(centre.getX()+.5, centre.getY()+.015625, centre.getZ()+.5);
        body.setNoGravity(true); body.setUserName("Wall Escape Fixture");
        for (int slot = 0; slot < 48; slot++) body.setItem(slot, RuntimeChecks.stack(slot));
        body.addItem(RuntimeChecks.stack(70)); body.addItem(RuntimeChecks.stack(71));
        RuntimeChecks.disableVacuum(body);
        CompoundTag saved = NameLifetimeChecks.saved(body);
        CompoundTag unresolved = new CompoundTag();
        unresolved.putString("Source", "FutureFixture"); unresolved.putString("Data", "retain verbatim");
        saved.put("LootableBodiesUnresolved", NbtRecoveryChecks.list(unresolved));
        CompoundTag invalid = new CompoundTag();
        invalid.putString("Source", "FutureMetadata"); invalid.put("Data", new ByteArrayTag(new byte[]{4,3,2,1}));
        saved.put("LootableBodiesInvalidFields", NbtRecoveryChecks.list(invalid));
        return (EntityLootableBody) FixtureApi.loadEntity(saved, world);
    }

    private static void unchanged(CompoundTag before, EntityLootableBody body) {
        CompoundTag after = NameLifetimeChecks.saved(body);
        before = before.copy(); before.remove("Pos"); after.remove("Pos");
        RuntimeChecks.require(TypedNbt.sameEntity(before, after), "Escape changed saved fields besides position");
        PositionMotionChecks.finite(body);
    }

    private static void clear(EntityLootableBody body) {
        PositionMotionChecks.finite(body);
        RuntimeChecks.require(world.noCollision(body,body.getBoundingBox()), "Escape left body intersecting terrain");
    }

    private static void centred() {
        BlockPos centre = site(false); terrain(centre, false); world.setBlock(centre, Blocks.STONE.defaultBlockState(), 2);
        // A zero-vector lookup previously sampled (0,0,0). Test it as air, too.
        net.minecraft.world.level.block.state.BlockState origin = world.getBlockState(BlockPos.ZERO);
        try {
            world.removeBlock(BlockPos.ZERO,false);
            EntityLootableBody body = body(centre); CompoundTag before = NameLifetimeChecks.saved(body);
            body.jumpOutOfWall(); clear(body); unchanged(before, body);
            RuntimeChecks.require(body.getY() == centre.getY()+1.015625, "Centred body missed the existing upward exit priority");
        } finally { world.setBlock(BlockPos.ZERO, origin, 2); }
    }

    private static void oneExit(Direction direction) {
        BlockPos centre = site(false); terrain(centre, true); BlockPos exit = centre.relative(direction); world.removeBlock(exit,false);
        EntityLootableBody body = body(centre); CompoundTag before = NameLifetimeChecks.saved(body);
        body.jumpOutOfWall(); clear(body); unchanged(before, body);
        RuntimeChecks.require(body.getX() == exit.getX()+.5 && body.getY() == exit.getY()+.015625 && body.getZ() == exit.getZ()+.5, "Missed sole adjacent exit");
    }

    private static void overlap() {
        BlockPos centre = site(false); terrain(centre, false); world.setBlock(centre, Blocks.STONE.defaultBlockState(), 2);
        EntityLootableBody body = body(centre); body.setPos(centre.getX()+.1, centre.getY()+.015625, centre.getZ()+.5);
        CompoundTag before = NameLifetimeChecks.saved(body); body.jumpOutOfWall(); clear(body); unchanged(before, body);
    }

    private static void snow() {
        BlockPos centre = site(false); terrain(centre, true); world.removeBlock(centre.north(),false);
        world.setBlock(centre.above(), Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 8), 2);
        EntityLootableBody body = body(centre); body.jumpOutOfWall(); clear(body);
        RuntimeChecks.require(body.blockPosition().equals(centre.north()), "Snow collision was ignored");
    }

    private static void fence() {
        BlockPos centre = site(false); terrain(centre, true); world.removeBlock(centre.north(),false); world.removeBlock(centre.east(),false);
        world.setBlock(centre.north().below(), Blocks.OAK_FENCE.defaultBlockState(), 2);
        EntityLootableBody body = body(centre); body.jumpOutOfWall(); clear(body);
        RuntimeChecks.require(body.blockPosition().equals(centre.east()), "Tall collision below destination was ignored");
    }

    private static void sealed() {
        BlockPos centre = site(false); terrain(centre, true); EntityLootableBody body = body(centre);
        CompoundTag before = NameLifetimeChecks.saved(body);
        for (int i = 0; i < 32; i++) body.jumpOutOfWall();
        RuntimeChecks.require(TypedNbt.sameEntity(before, NameLifetimeChecks.saved(body)), "Sealed corpse drifted or changed saved data");
        PositionMotionChecks.finite(body);
    }

    private static void alreadyClear() {
        BlockPos centre = site(false); terrain(centre, false); EntityLootableBody body = body(centre);
        CompoundTag before = NameLifetimeChecks.saved(body); body.jumpOutOfWall();
        RuntimeChecks.require(TypedNbt.sameEntity(before, NameLifetimeChecks.saved(body)), "Clear corpse teleported on stale suffocation damage");
    }

    private static void negative() {
        BlockPos centre = site(true); terrain(centre, true); world.removeBlock(centre.west(),false);
        EntityLootableBody body = body(centre); CompoundTag before = NameLifetimeChecks.saved(body);
        body.jumpOutOfWall(); clear(body); unchanged(before, body);
        RuntimeChecks.require(body.blockPosition().equals(centre.west()), "Negative coordinate exit was rounded incorrectly");
    }

    private static void damage() {
        BlockPos centre = site(false); terrain(centre, true); world.removeBlock(centre.above(),false);
        EntityLootableBody body = body(centre); float health = body.getHealth();
        body.hurt(DamageSource.IN_WALL, 1); clear(body);
        RuntimeChecks.require(body.isAlive() && body.getHealth() == health, "Default suffocation health rules changed");
    }

    private static void unloaded(Direction direction) throws Exception {
        BlockPos centre = site(false);
        int x = centre.getX() >> 4, z = centre.getZ() >> 4;
        centre = new BlockPos(x*16+(direction == Direction.WEST ? 0 : direction == Direction.EAST ? 15 : 8), 80,
                z*16+(direction == Direction.NORTH ? 0 : direction == Direction.SOUTH ? 15 : 8));
        terrain(centre, true); world.removeBlock(centre.relative(direction),false);
        int nx = x+direction.getStepX(), nz = z+direction.getStepZ();
        FixtureApi.unload(world,nx,nz);
        RuntimeChecks.require(!world.getChunkSource().hasChunk(nx,nz), "Fixture neighbour did not unload");
        EntityLootableBody body = body(centre); CompoundTag before = NameLifetimeChecks.saved(body);
        body.jumpOutOfWall();
        RuntimeChecks.require(!world.getChunkSource().hasChunk(nx,nz), "Escape forced an unloaded neighbour to load");
        RuntimeChecks.require(TypedNbt.sameEntity(before, NameLifetimeChecks.saved(body)), "Escape moved without loaded terrain");
        world.getChunk(nx,nz); body.jumpOutOfWall(); clear(body); unchanged(before, body);
    }

    private static void ticking() {
        BlockPos centre = site(false); terrain(centre, true); EntityLootableBody body = body(centre);
        CompoundTag before = NameLifetimeChecks.saved(body);
        for (int i = 0; i < 100; i++) body.tick();
        RuntimeChecks.require(body.getX() == centre.getX()+.5 && body.getY() == centre.getY()+.015625 && body.getZ() == centre.getZ()+.5 && body.isAlive(), "Ticking sealed corpse drifted or died");
        for (String key : new String[]{"Equipment","Aux","LootableBodiesUnresolved","LootableBodiesInvalidFields","DeathTime","DecayTime"})
            RuntimeChecks.require(NameLifetimeChecks.same(before.get(key),NameLifetimeChecks.saved(body).get(key)), "Ticking escape changed " + key);
        world.removeBlock(centre.above(),false);
        for (int i = 0; i < 30; i++) body.tick();
        clear(body); RuntimeChecks.require(body.blockPosition().equals(centre.above()), "Ticking corpse did not use newly opened exit");
    }

    private static void seed(File folder) throws Exception {
        for (boolean trapped : new boolean[]{false,true}) {
            BlockPos centre = site(false); terrain(centre, true);
            if (!trapped) world.removeBlock(centre.above(),false);
            EntityLootableBody body = body(centre);
            body.setUUID(UUID.nameUUIDFromBytes(("wall-escape-"+trapped).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            CompoundTag before = NameLifetimeChecks.saved(body); body.jumpOutOfWall(); unchanged(before,body);
            if (!trapped) clear(body);
            else RuntimeChecks.require(TypedNbt.sameEntity(before,NameLifetimeChecks.saved(body)), "Sealed disk fixture moved");
            RuntimeChecks.awaitChunk(world,body.blockPosition().getX()>>4,body.blockPosition().getZ()>>4);
            RuntimeChecks.require(world.addFreshEntity(body), "Could not save wall fixture");
            NbtIo.writeCompressed(NameLifetimeChecks.saved(body), new FileOutputStream(new File(folder,"wall-escape-"+trapped+".nbt")));
        }
    }

    static void reload(ServerLevel target, File folder) throws Exception {
        world = target;
        for (boolean trapped : new boolean[]{false,true}) {
            CompoundTag expected;
            try (InputStream in = new FileInputStream(new File(folder,"wall-escape-"+trapped+".nbt"))) { expected = NbtIo.readCompressed(in); }
            ListTag pos = expected.getList("Pos",6);
            BlockPos savedPosition=new BlockPos(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2));
            // Terrain admission completes before the native entity-storage inbox.
            RuntimeChecks.awaitChunk(world,savedPosition.getX()>>4,savedPosition.getZ()>>4);
            EntityLootableBody body = RuntimeChecks.find(world, expected.getUUID("UUID"));
            CompoundTag actual=NameLifetimeChecks.saved(body);
            if(!TypedNbt.sameEntity(expected,actual)) {
                try(OutputStream out=new FileOutputStream(new File(folder,"wall-reload-actual-"+trapped+".nbt"))){NbtIo.writeCompressed(actual,out);}
                for(String key:expected.getAllKeys())if(!NameLifetimeChecks.same(expected.get(key),actual.get(key)))
                    System.out.println("WALL_RELOAD_DIFF key="+key+" expected="+expected.get(key)+" actual="+actual.get(key));
                for(String key:actual.getAllKeys())if(!expected.contains(key))System.out.println("WALL_RELOAD_EXTRA key="+key+" value="+actual.get(key));
            }
            RuntimeChecks.require(TypedNbt.sameEntity(expected,NameLifetimeChecks.saved(body)), "Cold reload changed wall fixture NBT");
            body.jumpOutOfWall();
            RuntimeChecks.require(TypedNbt.sameEntity(expected,NameLifetimeChecks.saved(body)), "Repeated escape changed cold-loaded fixture");
            if (!trapped) clear(body);
        }
        System.out.println("LOOTABLE_BODIES_WALL_ESCAPE_RELOAD_PASS bodies=2");
    }

    /** The sealed 2.2.0 Nether fixture that originally exposed random wall drift. */
    static void historical(MinecraftServer server, File folder, boolean reload) throws Exception {
        world = server.getLevel(FixtureApi.dimensionKey(-1));
        for (int x = 7; x <= 9; x++) for (int z = 7; z <= 9; z++) world.getChunk(x,z);
        EntityLootableBody body = null;
        for (net.minecraft.world.entity.Entity entity : FixtureApi.entities(world))
            if (entity instanceof EntityLootableBody && Math.abs(entity.getX()-128.5)<.001 && Math.abs(entity.getZ()-128.5)<.001)
                body = (EntityLootableBody) entity;
        RuntimeChecks.require(body != null, "Original Nether corpse not found at its saved position");
        RuntimeChecks.require(!world.noCollision(body,body.getBoundingBox()), "Historical buried-body terrain was cleared");
        CompoundTag expected = NameLifetimeChecks.saved(body);
        File file = new File(folder,"wall-historical.nbt");
        if (reload) {
            try (InputStream in = new FileInputStream(file)) {
                RuntimeChecks.require(TypedNbt.sameEntity(NbtIo.readCompressed(in),expected), "Historical cold reload changed complete corpse NBT");
            }
        }
        for (int i = 0; i < 32; i++) body.jumpOutOfWall();
        RuntimeChecks.require(TypedNbt.sameEntity(expected,NameLifetimeChecks.saved(body)), "Historical sealed corpse drifted or lost saved data");
        PositionMotionChecks.finite(body);
        if (!reload) try (OutputStream out = new FileOutputStream(file)) { NbtIo.writeCompressed(expected,out); }
        System.out.println("LOOTABLE_BODIES_WALL_HISTORICAL_PASS reload="+reload+" slots="+body.getContainerSize());
    }
}
