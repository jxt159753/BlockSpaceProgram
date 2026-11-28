package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One graph per connected structure, including when some of its entities are unloaded. */
public final class WorldAssembly extends SavedData {
    private static final Factory<WorldAssembly> FACTORY = new Factory<>(WorldAssembly::new, WorldAssembly::load);
    private static final Codec<List<Group>> GROUPS = Group.CODEC.listOf();
    private final Map<UUID, Group> byMember = new HashMap<>();

    static WorldAssembly get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "blockspaceprogram_world_assembly");
    }

    private static WorldAssembly load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new WorldAssembly();
        for (Group group : GROUPS.parse(NbtOps.INSTANCE, tag.getList("groups", Tag.TAG_COMPOUND)).getOrThrow()) {
            for (Member member : group.members()) {
                if (data.byMember.putIfAbsent(member.id(), group) != null) {
                    throw new IllegalStateException("Placed part belongs to multiple groups: " + member.id());
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("groups", GROUPS.encodeStart(NbtOps.INSTANCE, byMember.values().stream().distinct().toList()).getOrThrow());
        return tag;
    }

    Group group(PlacedPartEntity part) {
        Group group = byMember.get(part.getUUID());
        return group == null ? new Group(List.of(Member.placed(part)), List.of(), 1) : group;
    }

    public static void onPartAdded(ServerLevel level, PlacedPartEntity part) {
        get(level).syncPart(part);
    }

    public static void onPartRemoved(ServerLevel level, PlacedPartEntity part) {
        var data = get(level);
        Group group = data.byMember.get(part.getUUID());
        if (group != null) {
            data.replace(level, group, group.splitAt(group.indexOf(part.getUUID())));
        }
    }

    void connect(ServerLevel level, Group group, PlacedPartEntity target, List<PlacedPartEntity> children,
            List<ShipDesign.Link> edges) {
        var members = new ArrayList<>(group.members());
        var links = new ArrayList<>(group.links());
        int parent = group.indexOf(target.getUUID());
        for (int i = 0; i < children.size(); i++) {
            links.add(edges.get(i).withNodes(parent, members.size()));
            members.add(Member.placed(children.get(i)));
        }
        replace(level, group, List.of(new Group(members, links, group.stageCount())));
    }

    void saveStages(ServerLevel level, Snapshot snapshot, ShipDesign design) {
        var members = new ArrayList<Member>();
        for (int i = 0; i < snapshot.parts().size(); i++) {
            members.add(new Member(snapshot.parts().get(i).getUUID(), design.nodes().get(i).engineStage()));
        }
        replace(level, snapshot.group(), List.of(new Group(members, design.links(), design.stageCount())));
    }

    private void replace(ServerLevel level, Group before, List<Group> after) {
        before.members().forEach(member -> byMember.remove(member.id()));
        for (Group group : after) {
            for (Member member : group.members()) {
                byMember.put(member.id(), group);
            }
            for (Member member : group.members()) {
                if (level.getEntity(member.id()) instanceof PlacedPartEntity part && part.isPickable()) {
                    syncPart(part);
                }
            }
        }
        setDirty();
    }

    private void syncPart(PlacedPartEntity part) {
        if (part.part() != null) {
            Group group = group(part);
            int node = group.indexOf(part.getUUID());
            int[] markers = group.links().stream()
                    .flatMap(link -> Stream.of(link.first(), link.second()))
                    .filter(endpoint -> endpoint.node() == node && endpoint.marker() >= 0)
                    .mapToInt(ShipDesign.Endpoint::marker).toArray();
            part.setGroupInfo(group.members().size(), markers);
        }
    }

    public static boolean canUse(Player player, PlacedPartEntity part) {
        return player.isAlive() && !player.isSpectator() && player.getAbilities().mayBuild
                && player.level() == part.level() && part.isPickable() && player.canInteractWithEntity(part, 0)
                && part.level().mayInteract(player, part.blockPosition());
    }

    public static void open(ServerPlayer player, PlacedPartEntity pod) {
        int maximumParts = (int) ShipFacilityLimits.parts(player).maximum();
        double maximumMass = ShipFacilityLimits.mass(player).maximum();
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new AssemblyMenu(id, inventory, pod),
                Component.translatable("assembly.blockspaceprogram.pod")), buffer -> {
                    buffer.writeVarInt(maximumParts);
                    buffer.writeDouble(maximumMass);
                });
    }

    @Nullable
    public static Component assemble(ServerPlayer player, PlacedPartEntity target) {
        if (!canUse(player, target)) {
            return Component.translatable("assembly.blockspaceprogram.world_permission");
        }
        ServerLevel level = player.serverLevel();
        WorldAssembly data = get(level);
        Inspection inspection = data.inspect(level, target);
        Snapshot snapshot = inspection.snapshot();
        if (snapshot == null) {
            return Component.translatable("assembly.blockspaceprogram." + inspection.error());
        }
        if (!snapshot.canEdit(player)) {
            return Component.translatable("assembly.blockspaceprogram.world_permission");
        }
        var rejection = ShipFacilityLimits.checkLaunch(player, snapshot.design());
        if (rejection != null) {
            return rejection;
        }
        Set<PlacedPartEntity> replaced = Set.copyOf(snapshot.parts());
        for (PlacedPartEntity part : snapshot.parts()) {
            String error = WorldPartPlacement.obstruction(level, part.getBoundingBox(), replaced);
            if (error != null) {
                return Component.translatable("assembly.blockspaceprogram." + error);
            }
        }

        ItemStack blueprint = new ItemStack(BlockSpaceProgram.SHIP_BLUEPRINT.get());
        blueprint.set(BlockSpaceProgram.SHIP_DESIGN, snapshot.design());
        AssembledShipEntity ship = AssembledShipEntity.spawn(level, blueprint, snapshot.origin(), snapshot.rotation(), player.getUUID());
        if (ship == null) {
            return Component.translatable("assembly.blockspaceprogram.world_spawn");
        }
        // Entity-join listeners run inline. Commit only if the placed structure is still the one we inspected.
        if (!snapshot.equals(data.inspect(level, target).snapshot())) {
            ship.discard();
            return Component.translatable("assembly.blockspaceprogram.world_group");
        }
        data.consume(level, Set.of(snapshot.group()), snapshot.parts());
        player.displayClientMessage(Component.translatable("assembly.blockspaceprogram.world_assembled"), true);
        return null;
    }

    void consume(ServerLevel level, Set<Group> groups, List<PlacedPartEntity> parts) {
        groups.forEach(group -> replace(level, group, List.of()));
        parts.forEach(PlacedPartEntity::discard);
    }

    Inspection inspect(ServerLevel level, PlacedPartEntity target) {
        if (!target.isPickable()) {
            return new Inspection(null, "world_group");
        }
        Group group = group(target);
        var parts = new ArrayList<PlacedPartEntity>();
        int rootIndex = -1;
        for (Member member : group.members()) {
            if (!(level.getEntity(member.id()) instanceof PlacedPartEntity part) || !part.isPickable()) {
                return new Inspection(null, "world_group");
            }
            if (rootIndex < 0 && part.isCommandPod()) {
                rootIndex = parts.size();
            }
            parts.add(part);
        }
        if (rootIndex < 0) {
            return new Inspection(null, "root");
        }
        PlacedPartEntity root = parts.get(rootIndex);
        OctahedralGroup inverse = root.rotation().inverse();
        var nodes = new ArrayList<ShipDesign.Node>();
        var orderedParts = new ArrayList<PlacedPartEntity>();
        int[] indices = new int[parts.size()];
        for (int order = 0; order < parts.size(); order++) {
            int oldIndex = order == 0 ? rootIndex : order <= rootIndex ? order - 1 : order;
            PlacedPartEntity part = parts.get(oldIndex);
            Vec3 origin = PartPose.rotate(inverse, part.position().subtract(root.position()));
            var quarters = PartPose.snap(origin);
            if (PartPose.fromQuarters(quarters).distanceToSqr(origin) != 0) {
                return new Inspection(null, "grid");
            }
            OctahedralGroup rotation = PartPose.align(Direction.UP, Direction.EAST,
                    inverse.rotate(part.rotation().rotate(Direction.UP)), inverse.rotate(part.rotation().rotate(Direction.EAST)));
            indices[oldIndex] = order;
            orderedParts.add(part);
            nodes.add(new ShipDesign.Node(part.part(), new PartPose(quarters, rotation), group.members().get(oldIndex).engineStage()));
        }
        var design = new ShipDesign(nodes, ShipDesign.remapLinks(group.links(), indices, Set.of()), group.stageCount());
        return design.isValid()
                ? new Inspection(new Snapshot(group, List.copyOf(orderedParts), design, root.position(), root.rotation()), "")
                : new Inspection(null, "world_group");
    }

    record Inspection(@Nullable Snapshot snapshot, String error) {}

    record Snapshot(Group group, List<PlacedPartEntity> parts, ShipDesign design, Vec3 origin, OctahedralGroup rotation) {
        boolean canEdit(ServerPlayer player) {
            return parts.stream().allMatch(part -> WorldPartPlacement.mayBuildIn(player, part.getBoundingBox()));
        }
    }

    record Member(UUID id, int engineStage) {
        static final Codec<Member> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("entity").forGetter(Member::id),
                ShipDesign.STAGE.fieldOf("engine_stage").forGetter(Member::engineStage)
        ).apply(instance, Member::new));

        static Member placed(PlacedPartEntity part) {
            return new Member(part.getUUID(), ShipDesign.Node.placed(part.part(), PartPose.ROOT).engineStage());
        }
    }

    record Group(List<Member> members, List<ShipDesign.Link> links, int stageCount) {
        static final Codec<Group> CODEC = RecordCodecBuilder.<Group>create(instance -> instance.group(
                Member.CODEC.listOf(1, ShipDesign.MAX_PARTS).fieldOf("members").forGetter(Group::members),
                ShipDesign.Link.CODEC.listOf(0, ShipDesign.MAX_LINKS).fieldOf("links").forGetter(Group::links),
                Codec.intRange(1, ShipDesign.MAX_STAGES).fieldOf("stages").forGetter(Group::stageCount)
        ).apply(instance, Group::new)).validate(group -> group.validTopology() ? DataResult.success(group)
                : DataResult.error(() -> "Invalid placed part graph"));

        Group {
            members = List.copyOf(members);
            links = List.copyOf(links);
        }

        int indexOf(UUID entity) {
            for (int index = 0; index < members.size(); index++) {
                if (members.get(index).id().equals(entity)) {
                    return index;
                }
            }
            return -1;
        }

        private boolean validTopology() {
            int count = members.size();
            return links.size() == count - 1
                    && members.stream().map(Member::id).distinct().count() == count
                    && links.stream().allMatch(link -> link.first().node() < count && link.second().node() < count)
                    && ShipDesign.components(count, links, Set.of(), -1).size() == 1;
        }

        List<Group> splitAt(int removed) {
            int count = members.size();
            var groups = new ArrayList<Group>();
            for (var component : ShipDesign.components(count, links, Set.of(), removed)) {
                int[] indices = new int[count];
                Arrays.fill(indices, -1);
                var remaining = new ArrayList<Member>();
                for (int node : component) {
                    indices[node] = remaining.size();
                    remaining.add(members.get(node));
                }
                groups.add(new Group(remaining, ShipDesign.remapLinks(links, indices, Set.of()), stageCount));
            }
            return groups;
        }
    }
}
