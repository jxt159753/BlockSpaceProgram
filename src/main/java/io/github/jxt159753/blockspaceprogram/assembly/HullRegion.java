package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A sparse block region on the ship's block grid, with material masses captured at assembly. */
public final class HullRegion {
    private static final Codec<LongList> POSITIONS = Codec.LONG_STREAM.xmap(
            stream -> LongList.of(stream.toArray()), LongList::longStream);
    private static final Codec<IntList> INDICES = Codec.INT_STREAM.xmap(
            stream -> IntList.of(stream.toArray()), IntList::intStream);
    private static final Codec<Storage> STORAGE = RecordCodecBuilder.create(instance -> instance.group(
            Material.CODEC.listOf().fieldOf("palette").forGetter(Storage::palette),
            POSITIONS.fieldOf("positions").forGetter(Storage::positions),
            INDICES.fieldOf("indices").forGetter(Storage::indices)
    ).apply(instance, Storage::new));
    static final Codec<HullRegion> CODEC = STORAGE.comapFlatMap(HullRegion::decode, region -> region.storage);

    private final Storage storage;
    private final AABB bounds;
    private final double mass;

    private HullRegion(Storage storage, AABB bounds, double mass) {
        this.storage = storage;
        this.bounds = bounds;
        this.mass = mass;
    }

    static HullRegion capture(Map<BlockPos, BlockState> structure, BlockPos origin) {
        var palette = new ArrayList<Material>();
        var lookup = new HashMap<BlockState, Integer>();
        var positions = new long[structure.size()];
        var indices = new int[structure.size()];
        int index = 0;
        for (var entry : structure.entrySet()) {
            BlockState state = entry.getValue();
            positions[index] = entry.getKey().subtract(origin).asLong();
            indices[index] = lookup.computeIfAbsent(state, block -> {
                int material = palette.size();
                palette.add(new Material(block, BlockPartProperties.get(block).mass()));
                return material;
            });
            index++;
        }
        return decode(new Storage(palette, LongList.of(positions), IntList.of(indices))).getOrThrow();
    }

    private static DataResult<HullRegion> decode(Storage storage) {
        var palette = storage.palette();
        var positions = storage.positions();
        var indices = storage.indices();
        if (positions.isEmpty() || positions.size() != indices.size() || palette.isEmpty()
                || palette.stream().anyMatch(material -> material.state().isAir())) {
            return DataResult.error(() -> "Invalid hull block palette or region length");
        }
        var seen = new LongOpenHashSet(positions.size());
        AABB box = null;
        double total = 0;
        for (int i = 0; i < positions.size(); i++) {
            int material = indices.getInt(i);
            long position = positions.getLong(i);
            if (material < 0 || material >= palette.size() || !seen.add(position)) {
                return DataResult.error(() -> "Invalid hull block index or duplicate position");
            }
            total += palette.get(material).mass();
            var cell = new AABB(BlockPos.of(position));
            box = box == null ? cell : box.minmax(cell);
        }
        if (!Double.isFinite(total)) {
            return DataResult.error(() -> "A hull needs finite mass");
        }
        return DataResult.success(new HullRegion(storage, box, total));
    }

    public int size() {
        return storage.positions().size();
    }

    public AABB bounds() {
        return bounds;
    }

    double mass() {
        return mass;
    }

    boolean contains(BlockPos position) {
        return storage.positions().contains(position.asLong());
    }

    HullRegion rotate(OctahedralGroup axes) {
        if (axes == PartPose.ROOT.rotation()) {
            return this;
        }
        var rotation = switch (axes.rotate(Direction.NORTH)) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> throw new IllegalArgumentException("Expected a hull yaw rotation");
        };
        // Rotating the center accounts for the lower corner changing on reversed axes.
        BlockPos shift = BlockPos.containing(PartPose.rotate(axes, new Vec3(0.5, 0.5, 0.5)));
        var positions = storage.positions().longStream()
                .map(pos -> BlockPos.of(pos).rotate(rotation).offset(shift).asLong()).toArray();
        var palette = storage.palette().stream()
                .map(material -> new Material(material.state().rotate(rotation), material.mass())).toList();
        return new HullRegion(new Storage(palette, LongList.of(positions), storage.indices()),
                PartPose.rotateBounds(axes, bounds), mass);
    }

    void forEach(BiConsumer<BlockPos, Material> consumer) {
        for (int i = 0; i < size(); i++) {
            consumer.accept(BlockPos.of(storage.positions().getLong(i)), storage.palette().get(storage.indices().getInt(i)));
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HullRegion region && storage.equals(region.storage);
    }

    @Override
    public int hashCode() {
        return storage.hashCode();
    }

    private record Storage(List<Material> palette, LongList positions, IntList indices) {
        private Storage {
            palette = List.copyOf(palette);
        }
    }

    record Material(BlockState state, double mass) {
        private static final Codec<Material> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(Material::state),
                Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("mass").forGetter(Material::mass)
        ).apply(instance, Material::new));
    }
}
