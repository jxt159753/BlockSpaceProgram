package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.sky.SkyNetworking.OrbitalView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class PlanetSkyClient {
    private static final int BACKGROUND = 0;
    private static final int ROCKY_BODY = 1;
    private static final int ATMOSPHERIC_BODY = 2;
    private static final int STAR = 3;
    private static final double SUN_RADIUS = Math.sin(Math.toRadians(1.5));
    private static final Vec3 NADIR = new Vec3(0, -1, 0);
    private static ClientLevel orbitalLevel;
    private static OrbitalView orbitalView;
    private static ShaderInstance shader;

    private PlanetSkyClient() {}

    @SubscribeEvent
    public static void registerShader(RegisterShadersEvent event) throws IOException {
        var id = ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "planet_sky");
        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.POSITION),
                loaded -> shader = loaded);
    }

    public static void receiveAtmospheres(Map<ResourceLocation, PlanetFlightProperties> received) {
        PlanetFlightProperties.setClientProperties(received);
    }

    public static void receiveOrbitalView(OrbitalView received) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.dimension().location().equals(received.dimension())) {
            orbitalView = received.reference().isPresent() ? received : null;
            orbitalLevel = orbitalView == null ? null : level;
        }
    }

    @SubscribeEvent
    public static void disconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        PlanetFlightProperties.setClientProperties(Map.of());
        orbitalView = null;
        orbitalLevel = null;
    }

    public static boolean vacuum(ClientLevel level, double cameraY) {
        var planet = PlanetFlightProperties.clientIn(level.dimension().location());
        return orbitalViewIn(level) != null || planet != null && planet.relativeDensityAt(cameraY) == 0;
    }

    @Nullable
    static OrbitalView orbitalViewIn(ClientLevel level) {
        if (orbitalLevel != level) {
            orbitalLevel = null;
            orbitalView = null;
        }
        return orbitalView;
    }

    @SubscribeEvent
    public static void renderFog(ViewportEvent.RenderFog event) {
        if (clearAirInVacuum(event.getCamera())) {
            event.setNearPlaneDistance(Float.MAX_VALUE);
            event.setFarPlaneDistance(Float.MAX_VALUE);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void fogColor(ViewportEvent.ComputeFogColor event) {
        if (clearAirInVacuum(event.getCamera())) {
            event.setRed(0);
            event.setGreen(0);
            event.setBlue(0);
        }
    }

    private static boolean clearAirInVacuum(Camera camera) {
        return camera.isInitialized() && camera.getFluidInCamera() == FogType.NONE && !skyBlocked(camera)
                && camera.getEntity().level() instanceof ClientLevel level && vacuum(level, camera.getPosition().y);
    }

    private static boolean skyBlocked(Camera camera) {
        return camera.getEntity() instanceof LivingEntity entity
                && (entity.hasEffect(MobEffects.BLINDNESS) || entity.hasEffect(MobEffects.DARKNESS));
    }

    public static float solarTime(ClientLevel level, float partialTick) {
        var dimension = level.dimension().location();
        var properties = PlanetFlightProperties.clientIn(dimension);
        var body = PlanetSkyProjection.bodyForDimension(dimension);
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (properties == null || body == null || !ClientOrbitalState.hasClock()
                || !camera.isInitialized() || camera.getEntity().level() != level) {
            return level.dimensionType().timeOfDay(level.dayTime());
        }
        var observer = PlanetSkyProjection.onSurface(body, properties, camera.getPosition(),
                ClientOrbitalState.orbitSeconds(partialTick));
        Vec3 sun = observer.toLocal(PlanetSkyProjection.SUN_DIRECTION);
        float angle = (float) (Math.acos(Mth.clamp(sun.y, -1, 1)) / (2 * Math.PI));
        return sun.x > 0 ? 1 - angle : angle;
    }

    public static boolean render(ClientLevel level, Camera camera, float partialTick, Matrix4f view,
                                 Matrix4f projection, VertexBuffer stars, boolean isFoggy) {
        var dimension = level.dimension().location();
        var planet = PlanetFlightProperties.clientIn(dimension);
        var region = orbitalViewIn(level);
        if (shader == null || planet == null && region == null
                || camera.getFluidInCamera() != FogType.NONE || skyBlocked(camera)) {
            return false;
        }

        Vec3 position = camera.getPosition();
        var home = planet == null ? null : PlanetSkyProjection.bodyForDimension(dimension);
        double seconds = ClientOrbitalState.orbitSeconds(partialTick);
        PlanetSkyProjection.Observer observer = null;
        if (ClientOrbitalState.hasClock()) {
            if (region != null) {
                observer = PlanetSkyProjection.inOrbit(region, position, seconds);
            } else if (home != null) {
                observer = PlanetSkyProjection.onSurface(home, planet, position, seconds);
            }
        }
        float density = region == null ? (float) Math.sqrt(planet.relativeDensityAt(position.y)) : 0;
        if (density > 0 && (isFoggy || observer == null)) {
            return false;
        }
        double altitude = planet == null ? 0 : position.y - planet.seaLevel();
        boolean showGlobe = planet != null && altitude > 0 && position.y >= level.getMaxBuildHeight();
        double ratio = planet == null ? 0 : relativeRadius(home == null ? planet.radius() : home.radius(), altitude);
        float horizon = region != null || showGlobe ? -1.0F : (float) -Math.sqrt(1 - ratio * ratio);
        Vec3 sun;
        if (observer == null) {
            double angle = level.getSunAngle(partialTick);
            sun = new Vec3(-Math.sin(angle), Math.cos(angle), 0);
        } else {
            sun = observer.toLocal(PlanetSkyProjection.SUN_DIRECTION);
        }
        Vec3 pole = observer == null ? new Vec3(0, 0, -1) : observer.toLocal(PlanetSkyProjection.NORTH_POLE);
        float weather = level.getRainLevel(partialTick);
        shader.safeGetUniform("ClipToWorld").set(new Matrix4f(view).invert().mul(new Matrix4f(projection).invert()));
        shader.safeGetUniform("Density").set(density);
        shader.safeGetUniform("Weather").set(weather);
        shader.safeGetUniform("Horizon").set(horizon);
        shader.safeGetUniform("LightDirection").set(sun.toVector3f());
        shader.safeGetUniform("BodyPole").set(pole.toVector3f());
        RenderSystem.depthMask(false);
        try {
            draw(BACKGROUND, Vec3.ZERO, 0, Vec3.ZERO);
            float daylight = Mth.clamp(((float) sun.y + 0.18F) / 0.33F, 0, 1);
            float starBrightness = 0.8F * (1 - density * daylight) * (1 - density * weather);
            if (stars != null && starBrightness > 0) {
                var starView = observer == null ? view : new Matrix4f(view).mul(observer.starsToLocal());
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
                RenderSystem.setShaderColor(starBrightness, starBrightness, starBrightness, 1);
                stars.bind();
                stars.drawWithShader(starView, projection, GameRenderer.getPositionShader());
                VertexBuffer.unbind();
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }
            draw(STAR, sun, SUN_RADIUS, Vec3.ZERO);
            if (observer != null) {
                renderBodies(observer, seconds, showGlobe || region != null ? null : dimension);
            } else if (showGlobe) {
                draw(planet.hasAtmosphere() ? ATMOSPHERIC_BODY : ROCKY_BODY, NADIR, ratio, new Vec3(0, 1, 0));
            }
        } finally {
            RenderSystem.depthMask(true);
        }
        return true;
    }

    private static double relativeRadius(double radius, double altitude) {
        return radius / (radius + Math.max(altitude, 0));
    }

    private static void renderBodies(PlanetSkyProjection.Observer observer, double seconds, @Nullable ResourceLocation hidden) {
        var visible = new ArrayList<Disc>();
        for (var body : OrbitalSystem.M3.bodies()) {
            if (body.id().equals(hidden)) {
                continue;
            }
            Vec3 displacement = OrbitalSystem.M3.bodyState(body.id(), seconds).position().subtract(observer.position());
            double distance = displacement.length();
            if (distance > body.radius()) {
                var properties = PlanetFlightProperties.clientIn(body.id());
                boolean atmospheric = properties == null ? body.id().equals(OrbitalSystem.MOTHER_PLANET) : properties.hasAtmosphere();
                Vec3 direction = observer.toLocal(displacement.scale(1 / distance));
                Vec3 primeMeridian = observer.toLocal(PlanetSkyProjection.primeMeridian(body, seconds));
                visible.add(new Disc(atmospheric ? ATMOSPHERIC_BODY : ROCKY_BODY, direction,
                        body.radius() / distance, primeMeridian, distance));
            }
        }
        visible.sort(Comparator.comparingDouble(Disc::distance).reversed());
        for (var disc : visible) {
            draw(disc.mode(), disc.direction(), disc.relativeRadius(), disc.primeMeridian());
        }
    }

    private record Disc(int mode, Vec3 direction, double relativeRadius, Vec3 primeMeridian, double distance) {}

    private static void draw(int mode, Vec3 direction, double relativeRadius, Vec3 primeMeridian) {
        shader.safeGetUniform("Mode").set(mode);
        shader.safeGetUniform("BodyDirection").set(direction.toVector3f());
        shader.safeGetUniform("Radius").set((float) relativeRadius);
        shader.safeGetUniform("PrimeMeridian").set(primeMeridian.toVector3f());
        RenderSystem.setShader(() -> shader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1, -1, 0.9999F);
        buffer.addVertex(1, -1, 0.9999F);
        buffer.addVertex(1, 1, 0.9999F);
        buffer.addVertex(-1, 1, 0.9999F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
