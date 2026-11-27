package io.github.jxt159753.blockspaceprogram.facility;

import java.util.List;

public record FacilityLimit(int tier, double maximum) {
    static FacilityLimit resolve(List<? extends Number> limits, int tier) {
        int effectiveTier = Math.min(tier, limits.size());
        return new FacilityLimit(effectiveTier, limits.get(effectiveTier - 1).doubleValue());
    }

    public boolean exceededBy(double amount) {
        return maximum > 0 && amount > maximum;
    }
}
