package io.github.jxt159753.blockspaceprogram;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;

@Mod(BlockSpaceProgram.MOD_ID)
public final class BlockSpaceProgram {
    public static final String MOD_ID = "blockspaceprogram";

    public BlockSpaceProgram() {
        LogUtils.getLogger().info("BlockSpaceProgram loaded");
    }
}
