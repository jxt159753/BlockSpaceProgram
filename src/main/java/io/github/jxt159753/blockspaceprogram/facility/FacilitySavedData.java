package io.github.jxt159753.blockspaceprogram.facility;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

final class FacilitySavedData extends SavedData {
    private static final Factory<FacilitySavedData> FACTORY = new Factory<>(FacilitySavedData::new, FacilitySavedData::load);
    private final Map<UUID, EnumMap<Facility, Integer>> byOwner = new HashMap<>();

    static FacilitySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "blockspaceprogram_facilities");
    }

    private static FacilitySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new FacilitySavedData();
        var owners = tag.getCompound("owners");
        for (String key : owners.getAllKeys()) {
            var entry = owners.getCompound(key);
            var tiers = new EnumMap<Facility, Integer>(Facility.class);
            for (Facility facility : Facility.values()) {
                int tier = entry.getInt(facility.id());
                if (tier >= 1) {
                    tiers.put(facility, tier);
                }
            }
            data.byOwner.put(UUID.fromString(key), tiers);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var owners = new CompoundTag();
        byOwner.forEach((id, tiers) -> {
            var entry = new CompoundTag();
            tiers.forEach((facility, tier) -> entry.putInt(facility.id(), tier));
            owners.put(id.toString(), entry);
        });
        tag.put("owners", owners);
        return tag;
    }

    int tier(UUID owner, Facility facility, int defaultTier) {
        var tiers = byOwner.get(owner);
        return tiers == null ? defaultTier : tiers.getOrDefault(facility, defaultTier);
    }

    void setTier(UUID owner, Facility facility, int tier) {
        var tiers = byOwner.computeIfAbsent(owner, id -> new EnumMap<>(Facility.class));
        if (!Objects.equals(tiers.put(facility, tier), tier)) {
            setDirty();
        }
    }
}
