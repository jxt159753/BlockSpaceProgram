package io.github.jxt159753.blockspaceprogram.webpreview;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/** Visibility belongs to the ship identity and the owner who chose to publish it. */
final class WebPreviewVisibility extends SavedData {
    private static final String STORAGE_ID = "blockspaceprogram_web_preview";
    private static final String MESSAGE = "web_preview.blockspaceprogram.visibility.";
    private static final Factory<WebPreviewVisibility> FACTORY =
            new Factory<>(WebPreviewVisibility::new, WebPreviewVisibility::load);

    private final Map<UUID, UUID> published = new HashMap<>();

    static WebPreviewVisibility get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_ID);
    }

    boolean isPublic(UUID ship, @Nullable UUID owner) {
        return owner != null && owner.equals(published.get(ship));
    }

    private static WebPreviewVisibility load(CompoundTag tag, HolderLookup.Provider registries) {
        var visibility = new WebPreviewVisibility();
        for (String key : tag.getAllKeys()) {
            visibility.published.put(UUID.fromString(key), tag.getUUID(key));
        }
        return visibility;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        published.forEach((ship, owner) -> tag.putUUID(ship.toString(), owner));
        return tag;
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsp_web_preview")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("ship", UuidArgument.uuid())
                        .suggests((context, builder) -> {
                            var source = context.getSource();
                            var server = source.getServer();
                            var owner = source.getPlayerOrException().getUUID();
                            var ids = new HashSet<UUID>();
                            for (var ship : OrbitalSavedData.get(server).ships()) {
                                if (owner.equals(ship.ownerId())) {
                                    ids.add(ship.id());
                                }
                            }
                            for (var level : server.getAllLevels()) {
                                for (var ship : level.getEntities(BlockSpaceProgram.ASSEMBLED_SHIP.get(),
                                        entity -> entity.isAlive() && owner.equals(entity.owner()))) {
                                    ids.add(ship.getUUID());
                                }
                            }
                            return SharedSuggestionProvider.suggest(ids.stream().map(UUID::toString), builder);
                        })
                        .then(Commands.literal("public").executes(context -> setVisibility(
                                context.getSource().getPlayerOrException(), UuidArgument.getUuid(context, "ship"), true)))
                        .then(Commands.literal("private").executes(context -> setVisibility(
                                context.getSource().getPlayerOrException(), UuidArgument.getUuid(context, "ship"), false)))));
    }

    private static int setVisibility(ServerPlayer player, UUID id, boolean visible) {
        var server = player.serverLevel().getServer();
        var owner = player.getUUID();
        var orbital = OrbitalSavedData.get(server).ship(id);
        boolean ownsShip = orbital != null && owner.equals(orbital.ownerId());
        if (orbital == null) {
            for (var level : server.getAllLevels()) {
                if (level.getEntity(id) instanceof AssembledShipEntity ship && ship.isAlive()
                        && owner.equals(ship.owner())) {
                    ownsShip = true;
                    break;
                }
            }
        }
        if (!ownsShip) {
            player.sendSystemMessage(Component.translatable(MESSAGE + "not_owned"));
            return 0;
        }
        var marks = get(server);
        UUID previous = visible ? marks.published.put(id, owner) : marks.published.remove(id);
        if (visible ? !owner.equals(previous) : previous != null) {
            marks.setDirty();
            WebPreview.visibilityChanged(server, id);
        }
        player.sendSystemMessage(Component.translatable(MESSAGE + (visible ? "public" : "private"), id.toString()));
        return 1;
    }
}
