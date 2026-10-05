package dev.pragyaan.doomcraft.world;

/**
 * How Doom space maps onto Minecraft space. One block is 32 Doom units, which
 * makes Steve (1.8 blocks) as tall as the Doom marine (56 units). Each map gets
 * its own strip of the Doom dimension so they never overlap.
 */
public final class DoomGeometry {
	public static final float UNITS_PER_BLOCK = 32f;
	public static final int BASE_Y = 64;
	public static final int MAP_SPACING = 1024;

	/** Minecraft ticks to Doom tics. Doom runs at 35 a second, Minecraft at 20. */
	public static final float TICS_PER_TICK = 35f / 20f;

	private DoomGeometry() {
	}

	public static int originX(int mapIndex) {
		return mapIndex * MAP_SPACING;
	}

	public static int mapIndexAt(double worldX) {
		return (int) Math.floor((worldX + MAP_SPACING / 2.0) / MAP_SPACING);
	}

	public static double worldX(int mapIndex, double doomX) {
		return originX(mapIndex) + doomX / UNITS_PER_BLOCK;
	}

	public static double worldZ(double doomY) {
		return -doomY / UNITS_PER_BLOCK;
	}

	public static double worldY(double doomHeight) {
		return BASE_Y + doomHeight / UNITS_PER_BLOCK;
	}

	public static double doomX(int mapIndex, double worldX) {
		return (worldX - originX(mapIndex)) * UNITS_PER_BLOCK;
	}

	public static double doomY(double worldZ) {
		return -worldZ * UNITS_PER_BLOCK;
	}

	public static double doomHeight(double worldY) {
		return (worldY - BASE_Y) * UNITS_PER_BLOCK;
	}

	/** Doom angles count anticlockwise from east. Minecraft yaw counts clockwise from south. */
	public static float yaw(double doomAngleDegrees) {
		return (float) (-90 - doomAngleDegrees);
	}
}
