package io.github.jxt159753.blockspaceprogram.facility;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public enum Facility {
    BAKING_TABLE("baking_table", "blocks"),
    ASSEMBLY_TABLE("assembly_table", "parts"),
    LAUNCH_PAD("launch_pad", "mass");

    private final String id;
    private final String unit;

    Facility(String id, String unit) {
        this.id = id;
        this.unit = unit;
    }

    public String id() {
        return id;
    }

    Component displayName() {
        return Component.translatable("block.blockspaceprogram." + id);
    }

    public Component quantity(double amount) {
        Object number = amount;
        if (amount < Long.MAX_VALUE && amount == Math.rint(amount)) {
            number = (long) amount;
        }
        return Component.translatable("facility.blockspaceprogram." + unit, number);
    }

    public Component maximum(double amount) {
        return amount == 0 ? Component.translatable("facility.blockspaceprogram.unlimited") : quantity(amount);
    }

    @Nullable
    public Component rejection(FacilityLimit limit, double actual) {
        if (!limit.exceededBy(actual)) {
            return null;
        }
        return Component.translatable("facility.blockspaceprogram.exceeded",
                displayName(), limit.tier(), quantity(actual), quantity(limit.maximum()));
    }
}
