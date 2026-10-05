package dev.pragyaan.doomcraft.client.world;

import java.util.Locale;
import java.util.Random;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.item.DoomWeaponItem;
import dev.pragyaan.doomcraft.world.DoomNet;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

/**
 * The parts of Doom's status bar that sit alongside Minecraft's own HUD in the
 * Doom dimension: the marine's face beside the hotbar, reacting to Steve's
 * hearts, the ammo count in Doom's red digits, the keycards, and Doom's
 * message line in its own font.
 */
public final class DoomStatusHud {
	private static final Random RANDOM = new Random();

	private static String message = "";
	private static long messageUntil;
	private static int keys;
	private static long grinUntil;
	private static long ouchUntil;
	private static float lastHealth = -1;
	private static int lookDirection = 1;
	private static long nextLook;
	private static long firingSince = -1;

	private DoomStatusHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, DoomCraft.id("status"), DoomStatusHud::draw);
		ClientPlayNetworking.registerGlobalReceiver(DoomNet.HudPayload.TYPE, (payload, context) -> {
			keys = payload.keys();
			if (!payload.message().isEmpty()) {
				message = payload.message();
				messageUntil = System.currentTimeMillis() + 4000;
				// The marine grins when he picks up a new weapon.
				if (message.startsWith("You got") || message.startsWith("A chainsaw")) {
					grinUntil = System.currentTimeMillis() + 1500;
				}
			}
		});
	}

	private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		DoomGraphics.Atlas atlas = DoomGraphics.get().sprites();
		long now = System.currentTimeMillis();

		if (now < messageUntil) {
			drawText(graphics, atlas, message, 4, 4);
		}
		if (mc.level.dimension() != DoomWorld.DIMENSION) {
			return;
		}

		int cx = graphics.guiWidth() / 2;
		int bottom = graphics.guiHeight();

		// The face, to the left of the hotbar.
		graphics.fill(cx - 91 - 31, bottom - 32, cx - 91 - 3, bottom - 1, 0xA0101010);
		drawPatch(graphics, atlas, face(mc, now), cx - 91 - 29, bottom - 31);

		// Ammo for the weapon in hand, in the big red status bar digits, right of the hotbar.
		ItemStack held = mc.player.getMainHandItem();
		int x = cx + 91 + 6;
		if (held.getItem() instanceof DoomWeaponItem weapon && weapon.weapon.ammo != null) {
			int ammo = mc.player.isCreative() ? 999 : Math.min(999, weapon.countAmmo(mc.player));
			x = drawNumber(graphics, atlas, ammo, x, bottom - 19) + 4;
		}
		// Keycards, stacked like the status bar's key boxes.
		for (int k = 0; k < 3; k++) {
			if ((keys & (1 << k)) != 0) {
				drawPatch(graphics, atlas, "STKEYS" + k, x, bottom - 21 + k * 7);
			}
		}
	}

	/** Which face to show, following ST_updateFaceWidget's priorities. */
	private static String face(Minecraft mc, long now) {
		float health = mc.player.getHealth();
		if (health <= 0 || mc.player.isDeadOrDying()) {
			return "STFDEAD0";
		}
		MobEffectInstance resistance = mc.player.getEffect(MobEffects.RESISTANCE);
		if (resistance != null && resistance.getAmplifier() >= 4) {
			return "STFGOD0";
		}
		int doomHealth = Math.round(Math.min(1, health / mc.player.getMaxHealth()) * 100);
		int pain = Math.min(4, (100 - doomHealth) * 5 / 101);

		if (lastHealth >= 0 && health < lastHealth) {
			// A big hit gets the "ouch" face, a small one makes him flinch.
			ouchUntil = now + (lastHealth - health >= 4 ? 800 : 400);
			lookDirection = lastHealth - health >= 4 ? -1 : RANDOM.nextBoolean() ? 3 : 4;
		}
		lastHealth = health;
		if (now < ouchUntil) {
			return switch (lookDirection) {
				case -1 -> "STFOUCH" + pain;
				case 3 -> "STFTL" + pain + "0";
				default -> "STFTR" + pain + "0";
			};
		}
		if (now < grinUntil) {
			return "STFEVL" + pain;
		}
		// Holding the trigger down for a couple of seconds brings out the rampage face.
		boolean firing = now - DoomWeaponHud.lastFireTime < 400;
		if (firing && firingSince < 0) {
			firingSince = now;
		} else if (!firing) {
			firingSince = -1;
		}
		if (firingSince >= 0 && now - firingSince > 2000) {
			return "STFKILL" + pain;
		}
		if (now > nextLook || lookDirection < 0 || lookDirection > 2) {
			lookDirection = RANDOM.nextInt(3);
			nextLook = now + 500 + RANDOM.nextInt(1000);
		}
		return "STFST" + pain + lookDirection;
	}

	private static int drawNumber(GuiGraphicsExtractor graphics, DoomGraphics.Atlas atlas, int value, int x, int y) {
		for (char c : Integer.toString(value).toCharArray()) {
			DoomGraphics.Region r = atlas.region("STTNUM" + c);
			if (r != null) {
				drawRegion(graphics, atlas, r, x, y);
				x += r.width();
			}
		}
		return x;
	}

	/** Draws text in Doom's small font (STCFN), which only has upper case. */
	private static void drawText(GuiGraphicsExtractor graphics, DoomGraphics.Atlas atlas, String text, int x, int y) {
		for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
			DoomGraphics.Region r = c > ' ' && c <= '_' ? atlas.region(String.format("STCFN%03d", (int) c)) : null;
			if (r == null) {
				x += 4;
				continue;
			}
			drawRegion(graphics, atlas, r, x, y);
			x += r.width();
		}
	}

	private static void drawPatch(GuiGraphicsExtractor graphics, DoomGraphics.Atlas atlas, String lump, int x, int y) {
		DoomGraphics.Region r = atlas.region(lump);
		if (r != null) {
			drawRegion(graphics, atlas, r, x, y);
		}
	}

	private static void drawRegion(GuiGraphicsExtractor graphics, DoomGraphics.Atlas atlas, DoomGraphics.Region r, int x, int y) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, atlas.id, x, y, r.x(), r.y(), r.width(), r.height(), r.width(), r.height(),
			atlas.width, atlas.height);
	}
}
