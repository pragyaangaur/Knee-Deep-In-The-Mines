package dev.pragyaan.doomcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** A cabinet whose front face shows the running game. Using it sits you down to play, and sneaking as you use it takes you into Doom's world. */
public class DoomArcadeBlock extends HorizontalDirectionalBlock implements EntityBlock {
	public DoomArcadeBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		// Sneaking jumps through the screen into Doom's world. Otherwise you sit down and play.
		if (player.isShiftKeyDown()) {
			if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
				dev.pragyaan.doomcraft.world.DoomWorld.get(serverPlayer.level().getServer()).enter(serverPlayer, "E1M1");
			}
		} else if (level.isClientSide()) {
			DoomCraft.openDoomScreen.run();
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new DoomArcadeBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (!level.isClientSide() || type != DoomCraft.ARCADE_BLOCK_ENTITY) {
			return null;
		}
		return (tickLevel, pos, tickState, blockEntity) -> DoomCraft.arcadeClientTick.accept(pos);
	}
}
