package io.github.jxt159753.blockspaceprogram.facility;

import io.github.jxt159753.blockspaceprogram.facility.FacilityOwnerResolver.Owner;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

public final class FacilityLevels {
    private final FacilityConfig config;
    private final FacilityOwnerResolver resolver;

    public FacilityLevels(FacilityConfig config, FacilityOwnerResolver resolver) {
        this.config = config;
        this.resolver = resolver;
    }

    public FacilityLimit limit(ServerPlayer player, Facility facility) {
        return limit(player, owner(player), facility);
    }

    @Nullable
    Owner owner(ServerPlayer player) {
        return player instanceof FakePlayer ? null : resolver.resolve(player);
    }

    FacilityLimit limit(ServerPlayer player, @Nullable Owner owner, Facility facility) {
        int tier = config.defaultTier();
        if (owner != null) {
            tier = FacilitySavedData.get(player.serverLevel().getServer()).tier(owner.id(), facility, tier);
        }
        return FacilityLimit.resolve(config.limits(facility), tier);
    }

    Component ownerName(@Nullable Owner owner) {
        return owner == null ? Component.translatable("facility.blockspaceprogram.owner_default") : owner.name();
    }

    int tierCount(Facility facility) {
        return config.limits(facility).size();
    }

    void setTier(ServerPlayer player, Owner owner, Facility facility, int tier) {
        FacilitySavedData.get(player.serverLevel().getServer()).setTier(owner.id(), facility, tier);
    }
}
