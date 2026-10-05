package dev.pragyaan.doomcraft.world;

/**
 * Doom's monsters with their numbers from info.c. Health and damage are in
 * Doom points. One Minecraft health point (half a heart) is worth five Doom
 * points, so a 100% Doom marine and a full-health Steve can take the same hits.
 */
public enum DoomMonsterKind {
	//           type  sprite  hp   radius height speed  ranged       melee  flying  walk    attack  pain  death     painChance
	ZOMBIEMAN(   3004, "POSS",   20, 20, 56,  8,  Ranged.BULLET,      0,  false, "ABCD", "EFE",  "G", "HIJKL",   200,
		"DSPOSIT1", "DSPOSACT", "DSPOPAIN", "DSPODTH1", "DSPISTOL"),
	SHOTGUN_GUY( 9,    "SPOS",   30, 20, 56,  8,  Ranged.SHOTGUN,     0,  false, "ABCD", "EFE",  "G", "HIJKL",   170,
		"DSPOSIT2", "DSPOSACT", "DSPOPAIN", "DSPODTH2", "DSSHOTGN"),
	IMP(         3001, "TROO",   60, 20, 56,  8,  Ranged.IMP_BALL,   24,  false, "ABCD", "EFG",  "H", "IJKLM",   200,
		"DSBGSIT1", "DSBGACT",  "DSPOPAIN", "DSBGDTH1", "DSFIRSHT"),
	DEMON(       3002, "SARG",  150, 30, 56, 10,  Ranged.NONE,       40,  false, "ABCD", "EFG",  "H", "IJKLMN",  180,
		"DSSGTSIT", "DSDMACT",  "DSDMPAIN", "DSSGTDTH", "DSSGTATK"),
	SPECTRE(     58,   "SARG",  150, 30, 56, 10,  Ranged.NONE,       40,  false, "ABCD", "EFG",  "H", "IJKLMN",  180,
		"DSSGTSIT", "DSDMACT",  "DSDMPAIN", "DSSGTDTH", "DSSGTATK"),
	LOST_SOUL(   3006, "SKUL",  100, 16, 56,  8,  Ranged.NONE,       24,  true,  "AB",   "CD",   "E", "FGHIJK",  256,
		"",         "DSDMACT",  "DSDMPAIN", "DSFIRXPL", "DSSKLATK"),
	CACODEMON(   3005, "HEAD",  400, 31, 56,  8,  Ranged.CACO_BALL,  60,  true,  "A",    "BCD",  "E", "GHIJKL",  128,
		"DSCACSIT", "DSDMACT",  "DSDMPAIN", "DSCACDTH", "DSFIRSHT"),
	BARON(       3003, "BOSS", 1000, 24, 64,  8,  Ranged.BARON_BALL, 80,  false, "ABCD", "EFG",  "H", "IJKLMNO",  50,
		"DSBRSSIT", "DSDMACT",  "DSDMPAIN", "DSBRSDTH", "DSFIRSHT"),
	BARREL(      2035, "BAR1",   20, 10, 42,  0,  Ranged.NONE,        0,  false, "AB",   "AB",   "A", "ABCDE",     0,
		"",         "",         "",         "DSBAREXP", "");

	public enum Ranged { NONE, BULLET, SHOTGUN, IMP_BALL, CACO_BALL, BARON_BALL }

	public final int doomType;
	public final String sprite;
	public final int health;
	public final int radius;
	public final int height;
	public final int speed;
	public final Ranged ranged;
	public final int meleeDamage;
	public final boolean flying;
	public final String walkFrames;
	public final String attackFrames;
	public final String painFrames;
	public final String deathFrames;
	public final int painChance;
	public final String sightSound;
	public final String activeSound;
	public final String painSound;
	public final String deathSound;
	public final String attackSound;

	DoomMonsterKind(int doomType, String sprite, int health, int radius, int height, int speed, Ranged ranged, int meleeDamage,
		boolean flying, String walkFrames, String attackFrames, String painFrames, String deathFrames, int painChance,
		String sightSound, String activeSound, String painSound, String deathSound, String attackSound) {
		this.doomType = doomType;
		this.sprite = sprite;
		this.health = health;
		this.radius = radius;
		this.height = height;
		this.speed = speed;
		this.ranged = ranged;
		this.meleeDamage = meleeDamage;
		this.flying = flying;
		this.walkFrames = walkFrames;
		this.attackFrames = attackFrames;
		this.painFrames = painFrames;
		this.deathFrames = deathFrames;
		this.painChance = painChance;
		this.sightSound = sightSound;
		this.activeSound = activeSound;
		this.painSound = painSound;
		this.deathSound = deathSound;
		this.attackSound = attackSound;
	}

	public static DoomMonsterKind byType(int doomType) {
		for (DoomMonsterKind kind : values()) {
			if (kind.doomType == doomType) {
				return kind;
			}
		}
		return null;
	}

	/** Barrels are monsters only so that they can be shot. They never move or attack. */
	public boolean isBarrel() {
		return this == BARREL;
	}
}
