package dev.pragyaan.doomcraft.world;

import java.util.HashMap;
import java.util.Map;

/**
 * Doom's thing types, from info.c. Monsters and pickups become Minecraft
 * entities. Decorations are drawn by the client and, when solid, get a
 * column of collision.
 */
public final class DoomThings {
	public enum Category { PLAYER_START, MONSTER, PICKUP, DECORATION, TELEPORT_DEST }

	/**
	 * A thing type. {@code frames} is the animation as sprite frame letters,
	 * {@code radius} and {@code height} are in Doom units.
	 */
	public record Info(int type, Category category, String sprite, String frames, boolean bright, int radius, int height,
		boolean solid, boolean hanging) {
	}

	private static final Map<Integer, Info> BY_TYPE = new HashMap<>();

	private static void add(int type, Category category, String sprite, String frames, boolean bright, int radius, int height, boolean solid) {
		BY_TYPE.put(type, new Info(type, category, sprite, frames, bright, radius, height, solid, false));
	}

	private static void hang(int type, String sprite, String frames, int height, boolean solid) {
		BY_TYPE.put(type, new Info(type, Category.DECORATION, sprite, frames, false, 16, height, solid, true));
	}

	private static void deco(int type, String sprite, String frames, boolean bright, int radius, int height, boolean solid) {
		add(type, Category.DECORATION, sprite, frames, bright, radius, height, solid);
	}

	private static void pickup(int type, String sprite, String frames, boolean bright) {
		add(type, Category.PICKUP, sprite, frames, bright, 20, 16, false);
	}

	static {
		add(1, Category.PLAYER_START, "PLAY", "A", false, 16, 56, false);
		add(14, Category.TELEPORT_DEST, "", "", false, 20, 16, false);

		for (DoomMonsterKind kind : DoomMonsterKind.values()) {
			add(kind.doomType, Category.MONSTER, kind.sprite, "A", false, kind.radius, kind.height, true);
		}

		pickup(2011, "STIM", "A", false);
		pickup(2012, "MEDI", "A", false);
		pickup(2014, "BON1", "ABCDCB", false);
		pickup(2015, "BON2", "ABCDCB", false);
		pickup(2018, "ARM1", "AB", true);
		pickup(2019, "ARM2", "AB", true);
		pickup(2007, "CLIP", "A", false);
		pickup(2048, "AMMO", "A", false);
		pickup(2008, "SHEL", "A", false);
		pickup(2049, "SBOX", "A", false);
		pickup(2010, "ROCK", "A", false);
		pickup(2046, "BROK", "A", false);
		pickup(2047, "CELL", "A", false);
		pickup(17, "CELP", "A", false);
		pickup(8, "BPAK", "A", false);
		pickup(2001, "SHOT", "A", false);
		pickup(2002, "MGUN", "A", false);
		pickup(2003, "LAUN", "A", false);
		pickup(2004, "PLAS", "A", false);
		pickup(2005, "CSAW", "A", false);
		pickup(2006, "BFUG", "A", false);
		pickup(2013, "SOUL", "ABCDCB", true);
		pickup(2022, "PINV", "ABCD", true);
		pickup(2023, "PSTR", "A", true);
		pickup(2024, "PINS", "ABCD", true);
		pickup(2025, "SUIT", "A", true);
		pickup(2026, "PMAP", "ABCDCB", true);
		pickup(2045, "PVIS", "AB", true);
		pickup(5, "BKEY", "AB", true);
		pickup(6, "YKEY", "AB", true);
		pickup(13, "RKEY", "AB", true);
		pickup(38, "RSKU", "AB", true);
		pickup(39, "YSKU", "AB", true);
		pickup(40, "BSKU", "AB", true);

		deco(2028, "COLU", "A", true, 16, 48, true);
		deco(30, "COL1", "A", false, 16, 52, true);
		deco(31, "COL2", "A", false, 16, 40, true);
		deco(32, "COL3", "A", false, 16, 52, true);
		deco(33, "COL4", "A", false, 16, 40, true);
		deco(36, "COL5", "AB", false, 16, 40, true);
		deco(37, "COL6", "A", false, 16, 40, true);
		deco(41, "CEYE", "ABCB", true, 16, 54, true);
		deco(42, "FSKU", "ABC", true, 16, 40, true);
		deco(43, "TRE1", "A", false, 16, 56, true);
		deco(44, "TBLU", "ABCD", true, 16, 68, true);
		deco(45, "TGRN", "ABCD", true, 16, 68, true);
		deco(46, "TRED", "ABCD", true, 16, 68, true);
		deco(55, "SMBT", "ABCD", true, 16, 50, true);
		deco(56, "SMGT", "ABCD", true, 16, 50, true);
		deco(57, "SMRT", "ABCD", true, 16, 50, true);
		deco(47, "SMIT", "A", false, 16, 40, true);
		deco(48, "ELEC", "A", false, 16, 128, true);
		deco(54, "TRE2", "A", false, 32, 108, true);
		deco(34, "CAND", "A", true, 20, 16, false);
		deco(35, "CBRA", "A", true, 16, 60, true);
		deco(85, "TLMP", "ABCD", true, 16, 80, true);
		deco(86, "TLP2", "ABCD", true, 16, 60, true);
		deco(10, "PLAY", "W", false, 20, 16, false);
		deco(12, "PLAY", "W", false, 20, 16, false);
		deco(15, "PLAY", "N", false, 20, 16, false);
		deco(18, "POSS", "L", false, 20, 16, false);
		deco(19, "SPOS", "L", false, 20, 16, false);
		deco(20, "TROO", "M", false, 20, 16, false);
		deco(21, "SARG", "N", false, 20, 16, false);
		deco(22, "HEAD", "L", false, 20, 16, false);
		deco(23, "SKUL", "K", false, 20, 16, false);
		deco(24, "POL5", "A", false, 20, 16, false);
		deco(25, "POL1", "A", false, 16, 64, true);
		deco(26, "POL6", "AB", false, 16, 64, true);
		deco(27, "POL4", "A", false, 16, 64, true);
		deco(28, "POL2", "A", false, 16, 64, true);
		deco(29, "POL3", "AB", true, 16, 64, true);
		deco(79, "POB1", "A", false, 20, 16, false);
		deco(80, "POB2", "A", false, 20, 16, false);
		deco(81, "BRS1", "A", false, 20, 16, false);
		hang(49, "GOR1", "ABCB", 68, true);
		hang(50, "GOR2", "A", 84, true);
		hang(51, "GOR3", "A", 84, true);
		hang(52, "GOR4", "A", 68, true);
		hang(53, "GOR5", "A", 52, true);
		hang(59, "GOR2", "A", 84, false);
		hang(60, "GOR4", "A", 68, false);
		hang(61, "GOR3", "A", 52, false);
		hang(62, "GOR5", "A", 52, false);
		hang(63, "GOR1", "ABCB", 68, false);
	}

	private DoomThings() {
	}

	public static Info get(int type) {
		return BY_TYPE.get(type);
	}

	/** Whether a thing is placed on the medium skill ("Hurt me plenty") in single player. */
	public static boolean onMediumSkill(DoomMap.Thing thing) {
		return (thing.flags() & 2) != 0 && (thing.flags() & 16) == 0;
	}
}
