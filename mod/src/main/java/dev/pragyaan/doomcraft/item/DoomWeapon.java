package dev.pragyaan.doomcraft.item;

/**
 * Doom's weapons. Cooldowns are Doom's refire times converted to Minecraft
 * ticks. The sprites are the first-person view drawn over the HUD.
 */
public enum DoomWeapon {
	CHAINSAW("chainsaw", null, 0, 4, "DSSAWFUL", "SAWG", "CD", null, "CSAW"),
	PISTOL("pistol", "bullets", 1, 8, "DSPISTOL", "PISG", "A", "PISF", "PIST"),
	SHOTGUN("shotgun", "shells", 1, 21, "DSSHOTGN", "SHTG", "A", "SHTF", "SHOT"),
	CHAINGUN("chaingun", "bullets", 1, 4, "DSPISTOL", "CHGG", "A", "CHGF", "MGUN"),
	ROCKET_LAUNCHER("rocket_launcher", "rockets", 1, 11, "DSRLAUNC", "MISG", "A", "MISF", "LAUN"),
	PLASMA_RIFLE("plasma_rifle", "cells", 1, 2, "DSPLASMA", "PLSG", "A", "PLSF", "PLAS");

	public final String id;
	public final String ammo;
	public final int ammoPerShot;
	public final int cooldown;
	public final String sound;
	public final String hudSprite;
	public final String hudFrames;
	public final String flashSprite;
	public final String pickupSprite;

	DoomWeapon(String id, String ammo, int ammoPerShot, int cooldown, String sound, String hudSprite, String hudFrames,
		String flashSprite, String pickupSprite) {
		this.id = id;
		this.ammo = ammo;
		this.ammoPerShot = ammoPerShot;
		this.cooldown = cooldown;
		this.sound = sound;
		this.hudSprite = hudSprite;
		this.hudFrames = hudFrames;
		this.flashSprite = flashSprite;
		this.pickupSprite = pickupSprite;
	}
}
