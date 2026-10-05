package dev.pragyaan.doomcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.pragyaan.doomcraft.DoomArcadeBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Draws the live Doom picture onto the cabinet's front face. */
public class DoomArcadeRenderer implements BlockEntityRenderer<DoomArcadeBlockEntity, DoomArcadeRenderer.State> {
	private static final int FULL_BRIGHT = 15728880;

	// The screen sits inside the bezel painted on the front texture, in 1/16ths of a block.
	private static final float LEFT = 2 / 16f - 0.5f;
	private static final float RIGHT = 14 / 16f - 0.5f;
	private static final float TOP = 14 / 16f - 0.5f;
	private static final float BOTTOM = 5 / 16f - 0.5f;
	private static final float FRONT = 0.5f + 0.002f;

	public static class State extends BlockEntityRenderState {
		Direction facing = Direction.NORTH;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(DoomArcadeBlockEntity blockEntity, State state, float partialTicks, Vec3 cameraPosition,
		ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
		state.facing = blockEntity.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		DoomEngine.get().updateTexture();

		poseStack.pushPose();
		poseStack.translate(0.5f, 0.5f, 0.5f);
		poseStack.rotateDegrees(Axis.YP, -state.facing.toYRot());
		collector.submitCustomGeometry(poseStack, RenderTypes.text(DoomEngine.TEXTURE), (pose, buffer) -> {
			buffer.addVertex(pose, LEFT, BOTTOM, FRONT).setColor(-1).setUv(0, 1).setLight(FULL_BRIGHT);
			buffer.addVertex(pose, RIGHT, BOTTOM, FRONT).setColor(-1).setUv(1, 1).setLight(FULL_BRIGHT);
			buffer.addVertex(pose, RIGHT, TOP, FRONT).setColor(-1).setUv(1, 0).setLight(FULL_BRIGHT);
			buffer.addVertex(pose, LEFT, TOP, FRONT).setColor(-1).setUv(0, 0).setLight(FULL_BRIGHT);
		});
		poseStack.popPose();
	}
}
