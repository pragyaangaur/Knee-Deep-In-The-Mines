package dev.pragyaan.doomcraft.world;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One Doom level, read from the WAD exactly as the original engine stores it.
 * Floor and ceiling shapes are recovered from the BSP tree: each subsector is
 * the convex region left after clipping by every partition line above it and
 * by its own segs.
 */
public final class DoomMap {
	public static final int ML_BLOCKING = 1;
	public static final int ML_TWOSIDED = 4;
	public static final int ML_DONTPEGTOP = 8;
	public static final int ML_DONTPEGBOTTOM = 16;

	public record Vertex(float x, float y) {
	}

	public record Line(int v1, int v2, int flags, int special, int tag, int front, int back) {
		public boolean twoSided() {
			return back >= 0;
		}
	}

	public record Side(int xOffset, int yOffset, String upper, String lower, String middle, int sector) {
	}

	public record Thing(float x, float y, int angle, int type, int flags) {
	}

	private record Seg(int v1, int v2, int line, int direction) {
	}

	private record Node(float x, float y, float dx, float dy, int front, int back) {
	}

	/** Sector heights change at run time when doors and lifts move. */
	public static final class Sector {
		public float floor;
		public float ceiling;
		public final float startFloor;
		public final float startCeiling;
		public final String floorPic;
		public final String ceilingPic;
		public int light;
		public final int startLight;
		public final int special;
		public final int tag;

		Sector(float floor, float ceiling, String floorPic, String ceilingPic, int light, int special, int tag) {
			this.floor = this.startFloor = floor;
			this.ceiling = this.startCeiling = ceiling;
			this.floorPic = floorPic;
			this.ceilingPic = ceilingPic;
			this.light = this.startLight = light;
			this.special = special;
			this.tag = tag;
		}

		public boolean skyCeiling() {
			return ceilingPic.equals("F_SKY1");
		}
	}

	/** A convex floor polygon in Doom units, belonging to one sector. */
	public record Subsector(int sector, float[] xs, float[] ys) {
	}

	public final String name;
	public final List<Vertex> vertices = new ArrayList<>();
	public final List<Line> lines = new ArrayList<>();
	public final List<Side> sides = new ArrayList<>();
	public final List<Sector> sectors = new ArrayList<>();
	public final List<Thing> things = new ArrayList<>();
	public final List<Subsector> subsectors = new ArrayList<>();
	private final List<Seg> segs = new ArrayList<>();
	private final List<int[]> ssectors = new ArrayList<>();
	private final List<Node> nodes = new ArrayList<>();
	public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
	public float lowestFloor = Float.MAX_VALUE, highestCeiling = -Float.MAX_VALUE;

	public static boolean exists(WadFile wad, String name) {
		return wad.has(name);
	}

	public DoomMap(WadFile wad, String name) {
		this.name = name;
		int marker = wad.indexOf(name);
		if (marker < 0) {
			throw new IllegalArgumentException("No map called " + name);
		}

		ByteBuffer b = wad.lump(marker + 4); // VERTEXES
		for (int i = 0; i + 4 <= b.limit(); i += 4) {
			Vertex v = new Vertex(b.getShort(i), b.getShort(i + 2));
			vertices.add(v);
			minX = Math.min(minX, v.x);
			maxX = Math.max(maxX, v.x);
			minY = Math.min(minY, v.y);
			maxY = Math.max(maxY, v.y);
		}

		b = wad.lump(marker + 8); // SECTORS
		for (int i = 0; i + 26 <= b.limit(); i += 26) {
			Sector s = new Sector(b.getShort(i), b.getShort(i + 2), WadFile.name8(b, i + 4), WadFile.name8(b, i + 12),
				b.getShort(i + 20) & 0xFF, b.getShort(i + 22), b.getShort(i + 24));
			sectors.add(s);
			lowestFloor = Math.min(lowestFloor, s.floor);
			highestCeiling = Math.max(highestCeiling, s.ceiling);
		}

		b = wad.lump(marker + 3); // SIDEDEFS
		for (int i = 0; i + 30 <= b.limit(); i += 30) {
			sides.add(new Side(b.getShort(i), b.getShort(i + 2), WadFile.name8(b, i + 4), WadFile.name8(b, i + 12),
				WadFile.name8(b, i + 20), b.getShort(i + 28) & 0xFFFF));
		}

		b = wad.lump(marker + 2); // LINEDEFS
		for (int i = 0; i + 14 <= b.limit(); i += 14) {
			int back = b.getShort(i + 12) & 0xFFFF;
			lines.add(new Line(b.getShort(i) & 0xFFFF, b.getShort(i + 2) & 0xFFFF, b.getShort(i + 4) & 0xFFFF,
				b.getShort(i + 6) & 0xFFFF, b.getShort(i + 8) & 0xFFFF, b.getShort(i + 10) & 0xFFFF, back == 0xFFFF ? -1 : back));
		}

		b = wad.lump(marker + 1); // THINGS
		for (int i = 0; i + 10 <= b.limit(); i += 10) {
			things.add(new Thing(b.getShort(i), b.getShort(i + 2), b.getShort(i + 4), b.getShort(i + 6) & 0xFFFF, b.getShort(i + 8) & 0xFFFF));
		}

		b = wad.lump(marker + 5); // SEGS
		for (int i = 0; i + 12 <= b.limit(); i += 12) {
			segs.add(new Seg(b.getShort(i) & 0xFFFF, b.getShort(i + 2) & 0xFFFF, b.getShort(i + 6) & 0xFFFF, b.getShort(i + 8)));
		}

		b = wad.lump(marker + 6); // SSECTORS
		for (int i = 0; i + 4 <= b.limit(); i += 4) {
			ssectors.add(new int[] {b.getShort(i) & 0xFFFF, b.getShort(i + 2) & 0xFFFF});
		}

		b = wad.lump(marker + 7); // NODES
		for (int i = 0; i + 28 <= b.limit(); i += 28) {
			nodes.add(new Node(b.getShort(i), b.getShort(i + 2), b.getShort(i + 4), b.getShort(i + 6),
				b.getShort(i + 24) & 0xFFFF, b.getShort(i + 26) & 0xFFFF));
		}

		buildSubsectors();
	}

	public Sector sectorOf(Side side) {
		return sectors.get(side.sector);
	}

	public Side front(Line line) {
		return sides.get(line.front);
	}

	public Side back(Line line) {
		return line.back < 0 ? null : sides.get(line.back);
	}

	/** The sector containing a point, found by walking the BSP tree like R_PointInSubsector. */
	public int sectorAt(float x, float y) {
		if (nodes.isEmpty()) {
			return subsectors.isEmpty() ? 0 : subsectors.get(0).sector;
		}
		int child = nodes.size() - 1;
		while ((child & 0x8000) == 0) {
			Node n = nodes.get(child);
			child = frontSide(n, x, y) ? n.front : n.back;
		}
		return subsectorSector(child & 0x7FFF);
	}

	private int subsectorSector(int ss) {
		Seg seg = segs.get(ssectors.get(ss)[1]);
		Line line = lines.get(seg.line);
		return sides.get(seg.direction == 0 ? line.front : line.back).sector;
	}

	private static boolean frontSide(Node n, float x, float y) {
		return n.dy * (x - n.x) - n.dx * (y - n.y) > 0;
	}

	private void buildSubsectors() {
		float pad = 64;
		float[] xs = {minX - pad, maxX + pad, maxX + pad, minX - pad};
		float[] ys = {minY - pad, minY - pad, maxY + pad, maxY + pad};
		if (nodes.isEmpty()) {
			leaf(0, xs, ys);
		} else {
			walk(nodes.size() - 1, xs, ys);
		}
	}

	private void walk(int child, float[] xs, float[] ys) {
		if (xs.length < 3) {
			return;
		}
		if ((child & 0x8000) != 0) {
			leaf(child & 0x7FFF, xs, ys);
			return;
		}
		Node n = nodes.get(child);
		// Front keeps dy*(x-nx) - dx*(y-ny) >= 0, back keeps the opposite.
		float[][] front = clip(xs, ys, n.x, n.y, n.dy, -n.dx);
		float[][] back = clip(xs, ys, n.x, n.y, -n.dy, n.dx);
		walk(n.front, front[0], front[1]);
		walk(n.back, back[0], back[1]);
	}

	private void leaf(int ss, float[] xs, float[] ys) {
		int[] s = ssectors.get(ss);
		for (int i = 0; i < s[0]; i++) {
			Seg seg = segs.get(s[1] + i);
			Vertex a = vertices.get(seg.v1);
			Vertex b = vertices.get(seg.v2);
			// The subsector lies on the right of each of its segs.
			float dx = b.x - a.x;
			float dy = b.y - a.y;
			float[][] c = clip(xs, ys, a.x, a.y, dy, -dx);
			xs = c[0];
			ys = c[1];
			if (xs.length < 3) {
				return;
			}
		}
		subsectors.add(new Subsector(subsectorSector(ss), xs, ys));
	}

	/** Keeps the part of a convex polygon where nx*(x-px) + ny*(y-py) >= 0. */
	static float[][] clip(float[] xs, float[] ys, float px, float py, float nx, float ny) {
		int n = xs.length;
		float[] ox = new float[n * 2];
		float[] oy = new float[n * 2];
		int count = 0;
		float eps = 0.01f * (float) Math.sqrt(nx * nx + ny * ny);
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			float di = nx * (xs[i] - px) + ny * (ys[i] - py);
			float dj = nx * (xs[j] - px) + ny * (ys[j] - py);
			boolean inI = di >= -eps;
			boolean inJ = dj >= -eps;
			if (inI) {
				ox[count] = xs[i];
				oy[count++] = ys[i];
			}
			if (inI != inJ && Math.abs(di - dj) > 1e-9) {
				float t = di / (di - dj);
				ox[count] = xs[i] + t * (xs[j] - xs[i]);
				oy[count++] = ys[i] + t * (ys[j] - ys[i]);
			}
		}
		return new float[][] {java.util.Arrays.copyOf(ox, count), java.util.Arrays.copyOf(oy, count)};
	}
}
