package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** A burn scheduled on the shared orbital clock, with a target delta-v in m/s. */
public record ManeuverNode(double seconds, Vec3 deltaV) {
    public static final double MAX_LEAD_SECONDS = 4 * 3600;
    public static final double MAX_COMPONENT = 10_000;
    public static final Codec<ManeuverNode> CODEC = RecordCodecBuilder.<ManeuverNode>create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("seconds").forGetter(ManeuverNode::seconds),
            Vec3.CODEC.fieldOf("delta_v").forGetter(ManeuverNode::deltaV)
    ).apply(instance, ManeuverNode::new)).validate(node -> node.valid() ? DataResult.success(node)
            : DataResult.error(() -> "Invalid maneuver node"));
    public static final StreamCodec<ByteBuf, ManeuverNode> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, ManeuverNode::seconds,
            ByteBufCodecs.DOUBLE, node -> node.deltaV.x,
            ByteBufCodecs.DOUBLE, node -> node.deltaV.y,
            ByteBufCodecs.DOUBLE, node -> node.deltaV.z,
            (seconds, prograde, normal, radial) -> new ManeuverNode(seconds, new Vec3(prograde, normal, radial)));

    public boolean valid() {
        return seconds >= 0 && Double.isFinite(seconds)
                && Math.abs(deltaV.x) <= MAX_COMPONENT && Math.abs(deltaV.y) <= MAX_COMPONENT
                && Math.abs(deltaV.z) <= MAX_COMPONENT;
    }

    public Orbit preview(OrbitalSystem system, Orbit orbit, double fromSeconds) {
        return apply(system, system.advance(orbit, fromSeconds, seconds));
    }

    /** Apply to the conic already valid at the node time. X is prograde, Y is normal, Z is radial-out. */
    Orbit apply(OrbitalSystem system, Orbit orbit) {
        return apply(system, orbit, seconds, 1);
    }

    Orbit apply(OrbitalSystem system, Orbit orbit, double seconds, double fraction) {
        var before = system.relativeState(orbit, seconds);
        Vec3 motion = before.velocity();
        Vec3 momentum = before.position().cross(motion);
        Vec3 prograde = motion.scale(1 / motion.length());
        Vec3 normal = momentum.scale(1 / momentum.length());
        Vec3 radialOut = prograde.cross(normal);
        Vec3 velocity = motion.add(prograde.scale(deltaV.x * fraction)).add(normal.scale(deltaV.y * fraction))
                .add(radialOut.scale(deltaV.z * fraction));
        double mu = system.body(orbit.center()).gravitationalParameter();
        return new Orbit(orbit.center(), OrbitElements.fromState(mu, new OrbitalState(before.position(), velocity), seconds));
    }
}
