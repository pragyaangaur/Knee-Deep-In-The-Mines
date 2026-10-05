package dev.pragyaan.doomcraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The invisible body of a Doom level. Minecraft collides with these, while the
 * client draws Doom's own walls and floors over them. A floor block stands up
 * from the bottom in eighths of a block, a ceiling block hangs from the top.
 * Using one is Doom's "use" key, so it opens doors and presses switches.
 */
public class DoomSolidBlock extends Block {
	public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;
	public static final BooleanProperty HANGING = BooleanProperty.create("hanging");

	private static final VoxelShape[] FLOOR = new VoxelShape[9];
	private static final VoxelShape[] CEILING = new VoxelShape[9];

	static {
		for (int i = 1; i <= 8; i++) {
			FLOOR[i] = Block.box(0, 0, 0, 16, i * 2, 16);
			CEILING[i] = Block.box(0, 16 - i * 2, 0, 16, 16, 16);
		}
	}

	public DoomSolidBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(LAYERS, 8).setValue(HANGING, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LAYERS, HANGING);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		int layers = state.getValue(LAYERS);
		return state.getValue(HANGING) ? CEILING[layers] : FLOOR[layers];
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (!level.isClientSide() && level.getServer() != null) {
			DoomWorld.get(level.getServer()).playerUse(player, hitResult.getLocation());
		}
		return InteractionResult.SUCCESS;
	}
}
