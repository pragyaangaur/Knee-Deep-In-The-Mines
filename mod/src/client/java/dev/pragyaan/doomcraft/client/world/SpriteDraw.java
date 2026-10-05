package dev.pragyaan.doomcraft.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Draws Doom sprites as upright billboards that turn to face the camera, the
 * way Doom draws every thing. One sprite pixel is one Doom unit, so a sprite
 * is drawn at 1/32 of a block per pixel.
 */
public final class SpriteDraw {
	public static final int FULL_BRIGHT = 15728880;

	private SpriteDraw() {
	}

	/** Doom's sprite rotation (1-8) for a thing seen from the camera, as R_ProjectSprite works it out. */
	public static int rotation(double thingX, double thingZ, float thingYaw, double camX, double camZ) {
		double ang = Math.toDegrees(Math.atan2(-(thingZ - camZ), thingX - camX));
		double thingAngle = -90 - thingYaw;
		double a = ((ang - thingAngle + 202.5) % 360 + 360) % 360;
		return (int) (a / 45) + 1;
	}

	/**
	 * Draws a sprite with its origin (the thing's feet) at the pose's origin.
	 * {@code brightness} is 0-255, {@code alpha} 0-255.
	 */
	public static void draw(PoseStack pose, SubmitNodeCollector collector, float cameraYaw, String sprite, char frame, int rotation,
		int brightness, int alpha) {
		DoomGraphics graphics = DoomGraphics.get();
		DoomGraphics.SpriteView view = graphics.view(sprite, frame, rotation);
		if (view == null) {
			return;
		}
		DoomGraphics.Atlas atlas = graphics.sprites();
		DoomGraphics.Region r = atlas.region(view.lump());
		if (r == null) {
			return;
		}
		float left = -r.leftOffset() / 32f;
		float right = (r.width() - r.leftOffset()) / 32f;
		float top = r.topOffset() / 32f;
		float bottom = (r.topOffset() - r.height()) / 32f;
		// Things that would sink into the floor are lifted, as Doom clips them to it.
		if (bottom < -0.1f) {
			top -= bottom + 0.1f;
			bottom = -0.1f;
		}
		float u0 = r.x() / (float) atlas.width;
		float u1 = (r.x() + r.width()) / (float) atlas.width;
		float v0 = r.y() / (float) atlas.height;
		float v1 = (r.y() + r.height()) / (float) atlas.height;
		if (view.flipped()) {
			float t = u0;
			u0 = u1;
			u1 = t;
		}
		int b = Math.max(0, Math.min(255, brightness));
		int color = (Math.max(0, Math.min(255, alpha)) << 24) | b << 16 | b << 8 | b;
		final float fu0 = u0, fu1 = u1, fl = left, fr = right, ft = top, fb = bottom, fv0 = v0, fv1 = v1;

		pose.pushPose();
		pose.rotateDegrees(Axis.YP, 180 - cameraYaw);
		collector.submitCustomGeometry(pose, RenderTypes.text(atlas.id), (p, buffer) -> {
			buffer.addVertex(p, fl, fb, 0).setColor(color).setUv(fu0, fv1).setLight(FULL_BRIGHT);
			buffer.addVertex(p, fr, fb, 0).setColor(color).setUv(fu1, fv1).setLight(FULL_BRIGHT);
			buffer.addVertex(p, fr, ft, 0).setColor(color).setUv(fu1, fv0).setLight(FULL_BRIGHT);
			buffer.addVertex(p, fl, ft, 0).setColor(color).setUv(fu0, fv0).setLight(FULL_BRIGHT);
			// The back face too, so a sprite never vanishes to culling.
			buffer.addVertex(p, fl, ft, 0).setColor(color).setUv(fu0, fv0).setLight(FULL_BRIGHT);
			buffer.addVertex(p, fr, ft, 0).setColor(color).setUv(fu1, fv0).setLight(FULL_BRIGHT);
			buffer.addVertex(p, fr, fb, 0).setColor(color).setUv(fu1, fv1).setLight(FULL_BRIGHT);
			buffer.addVertex(p, fl, fb, 0).setColor(color).setUv(fu0, fv1).setLight(FULL_BRIGHT);
		});
		pose.popPose();
	}

	/**
	 * Doom's light, roughly: a sector's level, dimmed with distance more in dark
	 * rooms than bright ones. Returns 0-255.
	 */
	public static int shade(int light, double distanceBlocks) {
		double l = light / 255.0;
		double dim = distanceBlocks * 32 / 1024 * (1.25 - l);
		double b = Math.max(0.1, Math.min(1, l * 1.05 + 0.08 - dim));
		return (int) (b * 255);
	}
}
