package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyNetworking;
import io.github.jxt159753.blockspaceprogram.assembly.PlacedPartEntity;
import io.github.jxt159753.blockspaceprogram.assembly.WorldPartPlacement;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class WorldPartPlacementClient {
    private static final KeyMapping SURFACE = key("placement_surface", GLFW.GLFW_KEY_G);
    private static final KeyMapping SOURCE = key("placement_source", GLFW.GLFW_KEY_H);
    private static final KeyMapping TURN = key("placement_turn", GLFW.GLFW_KEY_B);
    private static final KeyMapping SYMMETRY = key("placement_symmetry", GLFW.GLFW_KEY_N);
    @Nullable
    private static BakedPart lastPart;
    @Nullable
    private static String previewError;
    private static boolean surface;
    private static int source;
    private static int turns;
    private static int symmetry = 1;

    private static KeyMapping key(String name, int defaultKey) {
        return new KeyMapping("key.blockspaceprogram." + name, InputConstants.Type.KEYSYM,
                defaultKey, "key.categories.blockspaceprogram");
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SURFACE);
        event.register(SOURCE);
        event.register(TURN);
        event.register(SYMMETRY);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        BakedPart part = game.player == null ? null : heldPart(game.player.getItemInHand(placementHand(game.player)));
        if (!Objects.equals(part, lastPart)) {
            lastPart = part;
            surface = false;
            source = 0;
            turns = 0;
            symmetry = 1;
        }
        boolean canAdjust = part != null && game.screen == null;
        while (SURFACE.consumeClick()) {
            if (canAdjust) {
                surface = !surface;
                source = 0;
            }
        }
        while (SOURCE.consumeClick()) {
            if (canAdjust) {
                source++;
            }
        }
        while (TURN.consumeClick()) {
            if (canAdjust) {
                turns = (turns + 1) % 4;
            }
        }
        while (SYMMETRY.consumeClick()) {
            if (canAdjust) {
                symmetry = symmetry == 4 ? 1 : symmetry * 2;
                game.player.displayClientMessage(Component.translatable(
                        "assembly.blockspaceprogram.world_symmetry", symmetry), true);
            }
        }
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft game = Minecraft.getInstance();
        if (!event.isUseItem() || game.player == null || game.player.isSecondaryUseActive()
                || !(game.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof PlacedPartEntity target)) {
            return;
        }
        InteractionHand hand = placementHand(game.player);
        BakedPart part = heldPart(game.player.getItemInHand(hand));
        if (part == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        var options = options();
        var preview = WorldPartPlacement.attached(game.player, target, part, hit.getLocation(), options);
        if (preview.error() == null) {
            // The custom interaction bypasses vanilla's held-slot synchronization.
            if (hand == InteractionHand.MAIN_HAND) {
                game.getConnection().send(new ServerboundSetCarriedItemPacket(game.player.getInventory().selected));
            }
            PacketDistributor.sendToServer(new AssemblyNetworking.WorldPlace(target.getId(), hand, hit.getLocation(), options));
            game.player.swing(hand);
        }
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        previewError = null;
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.screen != null || game.options.hideGui || game.player.isSecondaryUseActive()) {
            return;
        }
        ItemStack stack = game.player.getItemInHand(placementHand(game.player));
        BakedPart part = heldPart(stack);
        if (part == null) {
            return;
        }
        var preview = switch (game.hitResult) {
            case EntityHitResult hit when hit.getEntity() instanceof PlacedPartEntity target ->
                WorldPartPlacement.attached(game.player, target, part, hit.getLocation(), options());
            case BlockHitResult hit when hit.getType() == HitResult.Type.BLOCK ->
                WorldPartPlacement.onBlock(game.player, stack, part, hit.getBlockPos(), hit.getDirection());
            case null, default -> null;
        };
        if (preview == null) {
            return;
        }
        previewError = preview.error();
        if (!preview.placements().isEmpty()) {
            var buffers = game.renderBuffers().bufferSource();
            boolean valid = previewError == null;
            var offset = event.getCamera().getPosition().reverse();
            for (var placement : preview.placements()) {
                var box = placement.bounds().move(offset).inflate(0.004);
                LevelRenderer.renderLineBox(event.getPoseStack(), buffers.getBuffer(RenderType.lines()), box,
                        valid ? 0.3F : 1.0F, valid ? 1.0F : 0.25F, 0.3F, 1.0F);
            }
            buffers.endBatch(RenderType.lines());
        }
    }

    @SubscribeEvent
    public static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "placement_error"),
                (graphics, tick) -> {
                    var game = Minecraft.getInstance();
                    if (previewError != null && game.screen == null && !game.options.hideGui) {
                        graphics.drawCenteredString(game.font, Component.translatable("assembly.blockspaceprogram." + previewError),
                                graphics.guiWidth() / 2, graphics.guiHeight() - 75, 0xFF7777);
                    }
                });
    }

    private static InteractionHand placementHand(Player player) {
        return player.getMainHandItem().isEmpty() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    @Nullable
    private static BakedPart heldPart(ItemStack stack) {
        return stack.is(BlockSpaceProgram.BAKED_PART.get()) ? stack.get(BlockSpaceProgram.PART_DATA) : null;
    }

    private static WorldPartPlacement.Options options() {
        return new WorldPartPlacement.Options(surface, source, turns, symmetry);
    }
}
