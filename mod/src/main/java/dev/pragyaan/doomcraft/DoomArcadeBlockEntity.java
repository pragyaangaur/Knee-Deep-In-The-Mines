package dev.pragyaan.doomcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds no data. It exists so the client can draw the game onto the cabinet. */
public class DoomArcadeBlockEntity extends BlockEntity {
	public DoomArcadeBlockEntity(BlockPos pos, BlockState state) {
		super(DoomCraft.ARCADE_BLOCK_ENTITY, pos, state);
	}
}
