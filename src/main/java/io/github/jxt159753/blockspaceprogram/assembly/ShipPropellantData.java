package io.github.jxt159753.blockspaceprogram.assembly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Remaining kg and J in blueprint node order, shared by the flight entity and the orbital store. */
record ShipPropellantData(ListTag fuel, ListTag electric) {
    static final ShipPropellantData EMPTY = new ShipPropellantData(new ListTag(), new ListTag());

    static ShipPropellantData read(CompoundTag tag) {
        return new ShipPropellantData(tag.getList("Fuel", Tag.TAG_DOUBLE), tag.getList("Electric", Tag.TAG_DOUBLE));
    }

    void write(CompoundTag tag) {
        tag.put("Fuel", fuel);
        tag.put("Electric", electric);
    }

    static ListTag encode(double[] amounts) {
        var tag = new ListTag();
        for (double amount : amounts) {
            tag.add(DoubleTag.valueOf(amount));
        }
        return tag;
    }

    static double initialAmount(ListTag saved, int count, int index, double capacity, boolean damaged) {
        double remaining = saved.size() == count ? saved.getDouble(index) : capacity;
        return !damaged && Double.isFinite(remaining) ? Math.clamp(remaining, 0, capacity) : 0;
    }
}
