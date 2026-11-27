package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.PartPose;
import io.github.jxt159753.blockspaceprogram.assembly.PlacedPartEntity;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class BenchmarkScene {
    static final int RENDER_OBJECTS = 20;
    static final String SHIP_LAYOUT = "two_columns_bottom_up";
    private static final Vec3 GROUND_ORIGIN = new Vec3(0, -60, 0);
    private static final BenchmarkReport.Camera RENDER_CAMERA =
            new BenchmarkReport.Camera(GROUND_ORIGIN.add(0.5, 7, -19.5), 0, 14);
    private static final TicketType<ChunkPos> TERRAIN_TICKET =
            TicketType.create("blockspaceprogram_benchmark", Comparator.comparingLong(ChunkPos::toLong));
    private static final int SAMPLE_WIDTH = 7;
    private static final int SAMPLE_HEIGHT = 6;
    private static final int SHIP_SPACING = 8;
    private static final Vec3 SPIN = new Vec3(0, 0.2, 0);

    private final ServerLevel level;
    private final Vec3 origin;
    private final BenchmarkReport.Camera camera;
    private final boolean sable;
    private final boolean drifting;
    private final int shipCount;
    private final int columns;
    @Nullable private final ShipDesign design;
    private final List<Entity> entities = new ArrayList<>();
    private final Set<ChunkPos> terrain = new HashSet<>();

    private BenchmarkScene(ServerLevel level, boolean sable, boolean drifting, int shipCount, int partsPerShip) {
        this.level = level;
        this.sable = sable;
        this.drifting = drifting;
        this.shipCount = shipCount;
        columns = (int) Math.ceil(Math.sqrt(shipCount));
        origin = drifting ? OrbitalRegion.ORIGIN : GROUND_ORIGIN;
        design = shipCount == 0 ? null : shipDesign(partsPerShip);
        if (design == null) {
            camera = RENDER_CAMERA;
        } else {
            double height = design.bounds().getYsize();
            double distance = Math.max(22, Math.max(height * 0.9, columns * 5));
            camera = new BenchmarkReport.Camera(origin.add(0.5, Math.max(2, height / 2), 0.5 - distance), 0, 0);
        }
    }

    static BenchmarkScene render(ServerLevel level) {
        return new BenchmarkScene(level, false, false, 0, 0);
    }

    static BenchmarkScene ships(ServerLevel level, boolean sable, boolean drifting, int shipCount, int partsPerShip) {
        return new BenchmarkScene(level, sable, drifting, shipCount, partsPerShip);
    }

    ServerLevel level() {
        return level;
    }

    Vec3 origin() {
        return origin;
    }

    BenchmarkReport.Camera camera() {
        return camera;
    }

    boolean drifting() {
        return drifting;
    }

    ArmorStand cameraEntity() {
        var marker = new CompoundTag();
        marker.putBoolean("Marker", true);
        var stand = stand(camera.position());
        stand.readAdditionalSaveData(marker);
        stand.setInvisible(true);
        stand.setYRot(camera.yaw());
        stand.setXRot(camera.pitch());
        stand.setOldPosAndRot();
        return stand;
    }

    void prepareTerrain() throws CommandSyntaxException {
        if (drifting) {
            return;
        }
        if (design == null) {
            // Load the same terrain for all three render tiers.
            loadTerrain(new AABB(-7, origin.y(), -1, 8, origin.y() + 2, 14));
        } else {
            var bounds = design.bounds();
            for (int index = 0; index < shipCount; index++) {
                var footprint = bounds.move(shipOrigin(index, bounds));
                loadTerrain(footprint);
                var bottom = BlockPos.containing(footprint.minX, footprint.minY - 1, footprint.minZ);
                var opposite = BlockPos.containing(Math.nextDown(footprint.maxX), footprint.minY - 1,
                        Math.nextDown(footprint.maxZ));
                for (BlockPos pos : BlockPos.betweenClosed(bottom, opposite)) {
                    if (!level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) {
                        throw BenchmarkCommands.failure("flat_ground");
                    }
                }
            }
        }
    }

    private void loadTerrain(AABB bounds) throws CommandSyntaxException {
        if (!level.getWorldBorder().isWithinBounds(bounds)) {
            throw BenchmarkCommands.failure("blocked");
        }
        var first = new ChunkPos(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ));
        var last = new ChunkPos(BlockPos.containing(bounds.maxX, bounds.minY, bounds.maxZ));
        ChunkPos.rangeClosed(first, last).filter(terrain::add).forEach(chunk -> {
            level.getChunkSource().addRegionTicket(TERRAIN_TICKET, chunk, 2, chunk);
            level.getChunk(chunk.x, chunk.z);
        });
        if (level.getBlockCollisions(null, bounds).iterator().hasNext()) {
            throw BenchmarkCommands.failure("blocked");
        }
    }

    void populate(Kind kind) throws CommandSyntaxException {
        switch (kind) {
            case EMPTY -> {}
            case ARMOR_STANDS, PARTS -> populateRender(kind);
            case LANDED, DRIFTING -> populateShips();
        }
    }

    private void populateRender(Kind kind) throws CommandSyntaxException {
        var part = kind == Kind.PARTS ? sample() : null;
        var stack = new ItemStack(BlockSpaceProgram.BAKED_PART.get());
        if (part != null) {
            stack.set(BlockSpaceProgram.PART_DATA, part);
        }
        for (int index = 0; index < RENDER_OBJECTS; index++) {
            Vec3 center = origin.add(-5.5 + 3 * (index % 5), 0, 0.5 + 4 * (index / 5));
            Entity entity;
            if (part == null) {
                entity = stand(center);
            } else {
                var box = part.localBounds();
                entity = PlacedPartEntity.create(level, stack,
                        center.add(-box.getXsize() / 2, 0, -box.getZsize() / 2), OctahedralGroup.IDENTITY);
            }
            if (!level.addFreshEntity(entity)) {
                throw BenchmarkCommands.failure("spawn_failed");
            }
            entities.add(entity);
        }
    }

    private ArmorStand stand(Vec3 position) {
        var stand = new ArmorStand(level, position.x(), position.y(), position.z());
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        return stand;
    }

    private void populateShips() throws CommandSyntaxException {
        var bounds = design.bounds();
        for (int index = 0; index < shipCount; index++) {
            var ship = AssembledShipEntity.spawnBenchmark(level, design, shipOrigin(index, bounds));
            if (ship == null) {
                throw BenchmarkCommands.failure("spawn_failed");
            }
            entities.add(ship);
            if (drifting) {
                var flight = ship.activeFlight();
                if (flight == null) {
                    throw BenchmarkCommands.failure("body_removed", ship.getStringUUID());
                }
                var motion = flight.motion();
                flight.setMotion(new SableShipFlight.Motion(motion.center(), Vec3.ZERO, motion.orientation(), SPIN));
                OrbitalRegion.get(level.getServer()).addBenchmarkShip(ship);
            }
        }
    }

    private Vec3 shipOrigin(int index, AABB bounds) {
        double centerX = 0.5 + (index % columns - (columns - 1) / 2.0) * SHIP_SPACING;
        double centerZ = 0.5 + (index / columns) * SHIP_SPACING;
        return origin.add(centerX - bounds.getCenter().x(), 0, centerZ - bounds.getCenter().z());
    }

    void checkObjects() throws CommandSyntaxException {
        for (Entity entity : entities) {
            if (entity.isRemoved()) {
                throw BenchmarkCommands.failure("object_removed", entity.getStringUUID());
            }
            if (sable && entity instanceof AssembledShipEntity ship) {
                if (ship.activeFlight() == null) {
                    throw BenchmarkCommands.failure("body_removed", ship.getStringUUID());
                }
                if (drifting && !OrbitalRegion.get(level.getServer()).hasBenchmarkShip(ship)) {
                    throw BenchmarkCommands.failure("left_region", ship.getStringUUID());
                }
            }
        }
    }

    void clearObjects() {
        for (Entity entity : entities) {
            entity.discard();
            if (entity instanceof AssembledShipEntity) {
                OrbitalSavedData.get(level.getServer()).remove(entity.getUUID());
            }
        }
        entities.clear();
    }

    void close() {
        clearObjects();
        for (ChunkPos chunk : terrain) {
            level.getChunkSource().removeRegionTicket(TERRAIN_TICKET, chunk, 2, chunk);
        }
        terrain.clear();
    }

    private static BakedPart sample() {
        var blocks = new HashMap<BlockPos, BlockState>();
        for (int y = 0; y < SAMPLE_HEIGHT; y++) {
            var state = (y == 1 || y == 4 ? Blocks.CUT_COPPER : Blocks.SMOOTH_STONE).defaultBlockState();
            for (int z = 0; z < SAMPLE_WIDTH; z++) {
                for (int x = 0; x < SAMPLE_WIDTH; x++) {
                    blocks.put(new BlockPos(x, y, z), state);
                }
            }
        }
        return BakedPart.bake(blocks).orElseThrow().withScale(0.25F);
    }

    private static ShipDesign shipDesign(int parts) {
        var sample = sample();
        var pod = new BakedPart(sample.geometry(), sample.properties(), sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD), sample.scale());
        var center = sample.geometry().size().scale(0.5);
        var nodes = new ArrayList<ShipDesign.Node>();
        var links = new ArrayList<ShipDesign.Link>();
        for (int index = 0; index < parts; index++) {
            int column = index % 2;
            var offset = new BlockPos(column * SAMPLE_WIDTH, (index / 2) * SAMPLE_HEIGHT, 0);
            nodes.add(new ShipDesign.Node(index == 0 ? pod : sample, new PartPose(offset, OctahedralGroup.IDENTITY), -1));
            if (index > 0) {
                int parent = column == 0 ? index - 2 : index - 1;
                Direction face = column == 0 ? Direction.UP : Direction.EAST;
                double halfSide = column == 0 ? center.y() : center.x();
                links.add(new ShipDesign.Link(
                        new ShipDesign.Endpoint(parent, -1, face, center.relative(face, halfSide)),
                        new ShipDesign.Endpoint(index, -1, face.getOpposite(), center.relative(face, -halfSide)),
                        true, false, -1));
            }
        }
        return new ShipDesign(nodes, links, 1);
    }

    enum Kind {
        EMPTY, ARMOR_STANDS, PARTS, LANDED, DRIFTING;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
