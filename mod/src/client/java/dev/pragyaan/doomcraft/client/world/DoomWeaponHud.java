package dev.pragyaan.doomcraft.client.world;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.item.DoomWeapon;
import dev.pragyaan.doomcraft.item.DoomWeaponItem;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the Doom weapon you are holding the way Doom does, in the middle of
 * the bottom of the screen, bobbing as you walk and flashing when it fires.
 * The held item itself is hidden in first person by its item model.
 */
public final class DoomWeaponHud {
	public static long lastFireTime;

	private DoomWeaponHud() {
	}

	public static void register() {
		HudElementRegistry.addFirst(DoomCraft.id("weapon"), DoomWeaponHud::draw);
	}

	private static String fireFrames(DoomWeapon weapon) {
		return switch (weapon) {
			case PISTOL -> "ABCB";
			case SHOTGUN -> "ABCDCB";
			case CHAINGUN, ROCKET_LAUNCHER, CHAINSAW -> "AB";
			default -> "A";
		};
	}

	private static String flashFrames(DoomWeapon weapon) {
		return switch (weapon) {
			case PISTOL -> "A";
			case SHOTGUN, CHAINGUN, PLASMA_RIFLE -> "AB";
			case ROCKET_LAUNCHER -> "ABCD";
			default -> "";
		};
	}

	private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) {
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		if (!(stack.getItem() instanceof DoomWeaponItem item)) {
			return;
		}
		DoomWeapon weapon = item.weapon;
		DoomGraphics.Atlas atlas = DoomGraphics.get().sprites();

		long since = System.currentTimeMillis() - lastFireTime;
		long duration = weapon.cooldown * 50L;
		boolean firing = since < duration;
		String frames = firing ? fireFrames(weapon) : weapon.hudFrames;
		int index = firing ? (int) (since * frames.length() / duration)
			: (int) ((mc.level.getGameTime() / 4) % frames.length());
		String lump = weapon.hudSprite + frames.charAt(Math.min(frames.length() - 1, index)) + "0";

		// Doom's bob: a figure of eight that grows with walking speed.
		double speed = Math.min(1, mc.player.getDeltaMovement().horizontalDistance() * 6);
		double t = (mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(true)) / 20.0 * Math.PI * 2 * 0.7;
		double bobX = firing ? 0 : Math.cos(t) * 12 * speed;
		double bobY = firing ? 0 : Math.abs(Math.sin(t)) * 10 * speed;

		float scale = graphics.guiHeight() / 200f;
		float originX = (graphics.guiWidth() - 320 * scale) / 2;
		drawPsprite(graphics, atlas, lump, originX, scale, bobX, bobY);

		String flashes = flashFrames(weapon);
		if (firing && weapon.flashSprite != null && !flashes.isEmpty() && since < duration / 2) {
			int f = (int) (since * flashes.length() * 2 / duration);
			drawPsprite(graphics, atlas, weapon.flashSprite + flashes.charAt(Math.min(flashes.length() - 1, f)) + "0", originX, scale, bobX, bobY);
		}
	}

	/** Places a weapon sprite like R_DrawPSprite: x = 1 - leftoffset, y = 32 - topoffset, on a 320x200 screen. */
	private static void drawPsprite(GuiGraphicsExtractor graphics, DoomGraphics.Atlas atlas, String lump, float originX, float scale,
		double bobX, double bobY) {
		DoomGraphics.Region r = atlas.region(lump);
		if (r == null) {
			return;
		}
		double x = 1 - r.leftOffset() + bobX;
		double y = 32 - r.topOffset() + bobY;
		int sx = Math.round(originX + (float) x * scale);
		int sy = Math.round((float) y * scale);
		int w = Math.round(r.width() * scale);
		int h = Math.round(r.height() * scale);
		graphics.blit(RenderPipelines.GUI_TEXTURED, atlas.id, sx, sy, r.x(), r.y(), w, h, r.width(), r.height(), atlas.width, atlas.height);
	}
}
