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
import io.github.jxt159753.blockspaceprogram.client.mixin.LevelRendererSkyAccessor;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.orbit.PlanetFrame;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class PlanetSkyClient {
    private static final int BACKGROUND = 0;
    private static final int ROCKY_BODY = 1;
    private static final int ATMOSPHERIC_BODY = 2;
    private static final int STAR = 3;
    private static final int LOCAL_ATMOSPHERIC_GLOBE = 4;
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
        Vec3 sun = surfaceSun(level, partialTick);
        return sun == null ? level.dimensionType().timeOfDay(level.dayTime()) : PlanetSkyProjection.solarAngle(sun);
    }

    public static long shaderDayTime(ClientLevel level) {
        long dayTime = level.dayTime();
        if (!BlockSpaceProgram.isIrisShaderPackInUse()) {
            return dayTime;
        }
        Vec3 sun = surfaceSun(level, 0);
        return sun == null ? dayTime : PlanetSkyProjection.vanillaDayTime(dayTime, sun);
    }

    @Nullable
    private static Vec3 surfaceSun(ClientLevel level, float partialTick) {
        var dimension = level.dimension().location();
        var properties = PlanetFlightProperties.clientIn(dimension);
        var system = ClientOrbitalState.system();
        var body = system.findBody(dimension);
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (properties == null || body == null || !ClientOrbitalState.hasClock()
                || !camera.isInitialized() || camera.getEntity().level() != level) {
            return null;
        }
        var observer = PlanetSkyProjection.onSurface(system, body, properties, camera.getPosition(),
                ClientOrbitalState.orbitSeconds(partialTick));
        return observer.toLocal(PlanetSkyProjection.SUN_DIRECTION);
    }

    public static boolean render(ClientLevel level, Camera camera, float partialTick, Matrix4f view,
                                 Matrix4f projection, VertexBuffer stars, boolean isFoggy) {
        return !BlockSpaceProgram.isIrisShaderPackInUse()
                && render(level, camera, partialTick, view, projection, stars, isFoggy, false);
    }

    @SubscribeEvent
    public static void renderAfterShaderPack(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !BlockSpaceProgram.isIrisShaderPackInUse()) {
            return;
        }
        var game = Minecraft.getInstance();
        var level = game.level;
        var camera = event.getCamera();
        var position = camera.getBlockPosition();
        boolean isFoggy = level.effects().isFoggyAt(position.getX(), position.getY())
                || game.gui.getBossOverlay().shouldCreateWorldFog();
        var stars = ((LevelRendererSkyAccessor) event.getLevelRenderer()).blockspaceprogram$getStarBuffer();
        render(level, camera, event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getModelViewMatrix(),
                event.getProjectionMatrix(), stars, isFoggy, true);
    }

    private static boolean render(ClientLevel level, Camera camera, float partialTick, Matrix4f view,
                                  Matrix4f projection, VertexBuffer stars, boolean isFoggy, boolean afterShaderPack) {
        var dimension = level.dimension().location();
        var planet = PlanetFlightProperties.clientIn(dimension);
        var region = orbitalViewIn(level);
        if (shader == null || planet == null && region == null
                || camera.getFluidInCamera() != FogType.NONE || skyBlocked(camera)) {
            return false;
        }

        Vec3 position = camera.getPosition();
        var system = ClientOrbitalState.system();
        var home = planet == null ? null : system.findBody(dimension);
        double seconds = ClientOrbitalState.orbitSeconds(partialTick);
        PlanetSkyProjection.Observer observer = null;
        if (ClientOrbitalState.hasClock()) {
            if (region != null) {
                observer = PlanetSkyProjection.inOrbit(system, region, position, seconds);
            } else if (home != null) {
                observer = PlanetSkyProjection.onSurface(system, home, planet, position, seconds);
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
        float daylight = Mth.clamp(((float) sun.y + 0.18F) / 0.33F, 0, 1);
        float starBrightness = 0.8F * (1 - density * daylight) * (1 - density * weather);
        shader.safeGetUniform("ClipToWorld").set(new Matrix4f(view).invert().mul(new Matrix4f(projection).invert()));
        shader.safeGetUniform("Density").set(density);
        shader.safeGetUniform("Weather").set(weather);
        shader.safeGetUniform("StarBrightness").set(starBrightness);
        shader.safeGetUniform("Horizon").set(horizon);
        shader.safeGetUniform("LightDirection").set(sun.toVector3f());
        shader.safeGetUniform("BodyPole").set(pole.toVector3f());
        if (afterShaderPack) {
            // Shader packs can replace sky colors during composition. The main target still has world depth.
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            GL11.glDepthRange(1, 1);
        }
        RenderSystem.depthMask(false);
        try {
            draw(BACKGROUND, Vec3.ZERO, 0, Vec3.ZERO, 0);
            if (stars != null && starBrightness > 0) {
                var starView = observer == null ? view : new Matrix4f(view).mul(observer.starsToLocal());
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
                RenderSystem.setShaderColor(starBrightness, starBrightness, starBrightness, 1);
                stars.bind();
                stars.drawWithShader(starView, projection, GameRenderer.getPositionShader());
                VertexBuffer.unbind();
                RenderSystem.setShaderColor(1, 1, 1, 1);
            }
            // Disks cover stars, and the halo adds light outside the disk with zero opacity.
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            draw(STAR, sun, SUN_RADIUS, Vec3.ZERO, 0);
            if (observer != null) {
                renderBodies(system, observer, seconds, region == null ? dimension : null, showGlobe);
            } else if (showGlobe) {
                draw(planet.hasAtmosphere() ? LOCAL_ATMOSPHERIC_GLOBE : ROCKY_BODY, NADIR, ratio, new Vec3(0, 1, 0),
                        PlanetSkyProjection.surfaceSeed(dimension.toString()));
            }
        } finally {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            if (afterShaderPack) {
                GL11.glDepthRange(0, 1);
            }
        }
        return true;
    }

    private static double relativeRadius(double radius, double altitude) {
        return radius / (radius + Math.max(altitude, 0));
    }

    private static void renderBodies(OrbitalSystem system, PlanetSkyProjection.Observer observer, double seconds,
                                     @Nullable ResourceLocation localDimension, boolean showGlobe) {
        var visible = new ArrayList<Disc>();
        for (var body : system.bodies()) {
            boolean local = body.id().equals(localDimension);
            if (local && !showGlobe) {
                continue;
            }
            Vec3 displacement = system.bodyState(body.id(), seconds).position().subtract(observer.position());
            double distance = displacement.length();
            if (distance > body.radius()) {
                var properties = PlanetFlightProperties.clientIn(body.id());
                boolean atmospheric = properties == null ? body.id().equals(OrbitalSystem.MOTHER_PLANET) : properties.hasAtmosphere();
                int mode = atmospheric ? (local ? LOCAL_ATMOSPHERIC_GLOBE : ATMOSPHERIC_BODY) : ROCKY_BODY;
                int seed = atmospheric ? 0 : PlanetSkyProjection.surfaceSeed(body.id().toString());
                Vec3 direction = observer.toLocal(displacement.scale(1 / distance));
                Vec3 primeMeridian = observer.toLocal(PlanetFrame.primeMeridian(system, body, seconds));
                visible.add(new Disc(mode, direction, body.radius() / distance, primeMeridian, distance, seed));
            }
        }
        visible.sort(Comparator.comparingDouble(Disc::distance).reversed());
        for (var disc : visible) {
            draw(disc.mode(), disc.direction(), disc.relativeRadius(), disc.primeMeridian(), disc.seed());
        }
    }

    private record Disc(int mode, Vec3 direction, double relativeRadius, Vec3 primeMeridian, double distance, int seed) {}

    private static void draw(int mode, Vec3 direction, double relativeRadius, Vec3 primeMeridian, int seed) {
        shader.safeGetUniform("Mode").set(mode);
        shader.safeGetUniform("BodyDirection").set(direction.toVector3f());
        shader.safeGetUniform("Radius").set((float) relativeRadius);
        shader.safeGetUniform("PrimeMeridian").set(primeMeridian.toVector3f());
        shader.safeGetUniform("SurfaceSeed").set(seed);
        RenderSystem.setShader(() -> shader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(-1, -1, 0.9999F);
        buffer.addVertex(1, -1, 0.9999F);
        buffer.addVertex(1, 1, 0.9999F);
        buffer.addVertex(-1, 1, 0.9999F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
