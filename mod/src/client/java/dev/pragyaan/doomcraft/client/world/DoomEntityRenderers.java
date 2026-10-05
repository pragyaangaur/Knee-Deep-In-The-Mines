package dev.pragyaan.doomcraft.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.entity.DoomBall;
import dev.pragyaan.doomcraft.entity.DoomMonster;
import dev.pragyaan.doomcraft.entity.DoomPickup;
import dev.pragyaan.doomcraft.world.DoomMonsterKind;
import dev.pragyaan.doomcraft.world.DoomThings;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Draws monsters, fireballs and pickups with their sprites from the WAD. */
public final class DoomEntityRenderers {
	private DoomEntityRenderers() {
	}

	public static void register() {
		EntityRendererRegistry.register(DoomCraft.DOOM_MONSTER, MonsterRenderer::new);
		EntityRendererRegistry.register(DoomCraft.DOOM_BALL, BallRenderer::new);
		EntityRendererRegistry.register(DoomCraft.DOOM_PICKUP, PickupRenderer::new);
	}

	public static final class SpriteState extends EntityRenderState {
		String sprite = "POSS";
		char frame = 'A';
		boolean rotates = true;
		float yaw;
		int brightness = 255;
		int alpha = 255;
	}

	private static void submitSprite(SpriteState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		int rotation = state.rotates ? SpriteDraw.rotation(state.x, state.z, state.yaw, camera.pos.x, camera.pos.z) : 1;
		SpriteDraw.draw(pose, collector, camera.yRot, state.sprite, state.frame, rotation, state.brightness, state.alpha);
	}

	static final class MonsterRenderer extends EntityRenderer<DoomMonster, SpriteState> {
		MonsterRenderer(EntityRendererProvider.Context context) {
			super(context);
			shadowRadius = 0.4f;
		}

		@Override
		public SpriteState createRenderState() {
			return new SpriteState();
		}

		@Override
		public void extractRenderState(DoomMonster entity, SpriteState state, float partialTicks) {
			super.extractRenderState(entity, state, partialTicks);
			DoomMonsterKind kind = entity.kind();
			state.sprite = kind.sprite;
			state.yaw = entity.getYRot();
			state.rotates = true;
			boolean bright = false;
			if (entity.deathTime > 0 || !entity.isAlive()) {
				String frames = kind.deathFrames;
				state.frame = frames.charAt(Math.min(frames.length() - 1, entity.deathTime / 3));
				state.rotates = false;
				if (kind.isBarrel()) {
					state.sprite = "BEXP";
					state.frame = "ABCDE".charAt(Math.min(4, entity.deathTime / 3));
					bright = true;
				}
			} else if (entity.state() == DoomMonster.STATE_PAIN) {
				state.frame = kind.painFrames.charAt(0);
			} else if (entity.state() == DoomMonster.STATE_ATTACK) {
				String frames = kind.attackFrames;
				int age = entity.tickCount - entity.stateStartTick;
				int i = Math.min(frames.length() - 1, age * frames.length() / DoomMonster.ATTACK_TICKS);
				state.frame = frames.charAt(i);
				// The zombiemen's middle frame is the muzzle flash, and lost souls burn.
				bright = (kind.ranged == DoomMonsterKind.Ranged.BULLET || kind.ranged == DoomMonsterKind.Ranged.SHOTGUN) && i == 1;
			} else {
				String frames = kind.walkFrames;
				boolean moving = entity.getDeltaMovement().horizontalDistanceSqr() > 1e-5 || entity.xo != entity.getX() || entity.zo != entity.getZ();
				state.frame = moving || kind.flying ? frames.charAt((entity.tickCount / 4) % frames.length()) : frames.charAt(0);
			}
			bright |= kind == DoomMonsterKind.LOST_SOUL;
			state.brightness = bright ? 255 : DoomClientWorld.lightAt(entity.getX(), entity.getY() + 1, entity.getZ());
			state.alpha = kind == DoomMonsterKind.SPECTRE ? 90 : 255;
		}

		@Override
		public void submit(SpriteState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
			submitSprite(state, pose, collector, camera);
		}
	}

	static final class BallRenderer extends EntityRenderer<DoomBall, SpriteState> {
		BallRenderer(EntityRendererProvider.Context context) {
			super(context);
		}

		@Override
		public SpriteState createRenderState() {
			return new SpriteState();
		}

		@Override
		public void extractRenderState(DoomBall entity, SpriteState state, float partialTicks) {
			super.extractRenderState(entity, state, partialTicks);
			DoomBall.Kind kind = entity.kind();
			int burst = entity.burstAge();
			state.yaw = (float) Math.toDegrees(Math.atan2(-entity.getDeltaMovement().x, entity.getDeltaMovement().z));
			state.rotates = kind == DoomBall.Kind.ROCKET && burst < 0;
			state.brightness = 255;
			if (burst < 0) {
				state.sprite = kind.sprite;
				state.frame = kind == DoomBall.Kind.ROCKET ? 'A' : "AB".charAt((entity.tickCount / 3) % 2);
			} else if (kind == DoomBall.Kind.PLASMA) {
				state.sprite = "PLSE";
				state.frame = "ABCDE".charAt(Math.min(4, burst * 5 / DoomBall.BURST_TICKS));
			} else if (kind == DoomBall.Kind.ROCKET) {
				state.sprite = "MISL";
				state.frame = "BCD".charAt(Math.min(2, burst * 3 / DoomBall.BURST_TICKS));
			} else {
				state.sprite = kind.sprite;
				state.frame = "CDE".charAt(Math.min(2, burst * 3 / DoomBall.BURST_TICKS));
			}
		}

		@Override
		public void submit(SpriteState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
			// Fireball sprites are centred on the ball rather than standing on it.
			pose.pushPose();
			pose.translate(0, -0.3, 0);
			submitSprite(state, pose, collector, camera);
			pose.popPose();
		}
	}

	static final class PickupRenderer extends EntityRenderer<DoomPickup, SpriteState> {
		PickupRenderer(EntityRendererProvider.Context context) {
			super(context);
		}

		@Override
		public SpriteState createRenderState() {
			return new SpriteState();
		}

		@Override
		public void extractRenderState(DoomPickup entity, SpriteState state, float partialTicks) {
			super.extractRenderState(entity, state, partialTicks);
			DoomThings.Info info = DoomThings.get(entity.doomType());
			if (info == null) {
				state.sprite = "";
				return;
			}
			state.sprite = info.sprite();
			state.frame = info.frames().charAt((entity.tickCount / 6) % info.frames().length());
			state.rotates = false;
			state.brightness = info.bright() ? 255 : DoomClientWorld.lightAt(entity.getX(), entity.getY() + 0.5, entity.getZ());
		}

		@Override
		public void submit(SpriteState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
			if (!state.sprite.isEmpty()) {
				submitSprite(state, pose, collector, camera);
			}
		}
	}
}
