package io.github.jxt159753.blockspaceprogram;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(BlockSpaceProgram.MOD_ID)
public final class BlockSpaceProgram {
    public static final String MOD_ID = "blockspaceprogram";

    public BlockSpaceProgram(IEventBus modBus) {
        modBus.addListener(BlockPartProperties::register);
        LogUtils.getLogger().info("BlockSpaceProgram loaded");
    }
}
