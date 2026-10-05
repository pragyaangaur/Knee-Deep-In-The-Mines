package dev.pragyaan.doomcraft.client.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.pragyaan.doomcraft.world.DoomGeometry;
import dev.pragyaan.doomcraft.world.DoomMap;
import dev.pragyaan.doomcraft.world.DoomThings;
import dev.pragyaan.doomcraft.world.WadFile;

/**
 * The client's copy of one Doom map, turned into textured quads. Walls follow
 * Doom's pegging rules, floors come from the BSP subsectors, and every quad is
 * cut at texture edges so it can be drawn from one atlas without repeating.
 */
public final class ClientMap {
	public final int index;
	public final DoomMap map;
	private final DoomGraphics.Atlas atlas;
	private final Set<Integer> switchesOn = new HashSet<>();
	private final List<Set<Integer>> neighbours = new ArrayList<>();
	private boolean dirty = true;

	// Quads, rebuilt when a sector moves: 4 corners of (x, y, z, u, v) in world
	// blocks and texture-local pixels, plus the texture and the sector for light.
	private float[] verts = new float[0];
	private int[] quadTexture = new int[0];
	private int[] quadSector = new int[0];
	private int[] quadContrast = new int[0];
	private int quadCount;
	private int staticQuads;
	private long scrolledAt = -1;
	private int scroll;

	private final List<String> textureNames = new ArrayList<>();
	private final Map<String, Integer> textureIds = new HashMap<>();
	public final List<Decoration> decorations = new ArrayList<>();

	public record Decoration(double x, double y, double z, DoomThings.Info info, int sector) {
	}

	/** Doom's animated textures from animdefs in p_spec.c. Each group cycles every 8 tics. */
	private static final String[][] ANIMATIONS = {
		{"F:NUKAGE1", "F:NUKAGE2", "F:NUKAGE3"},
		{"F:FWATER1", "F:FWATER2", "F:FWATER3", "F:FWATER4"},
		{"F:SWATER1", "F:SWATER2", "F:SWATER3", "F:SWATER4"},
		{"F:LAVA1", "F:LAVA2", "F:LAVA3", "F:LAVA4"},
		{"F:BLOOD1", "F:BLOOD2", "F:BLOOD3"},
		{"F:RROCK05", "F:RROCK06", "F:RROCK07", "F:RROCK08"},
		{"F:SLIME01", "F:SLIME02", "F:SLIME03", "F:SLIME04"},
		{"F:SLIME05", "F:SLIME06", "F:SLIME07", "F:SLIME08"},
		{"F:SLIME09", "F:SLIME10", "F:SLIME11", "F:SLIME12"},
		{"T:BLODGR1", "T:BLODGR2", "T:BLODGR3", "T:BLODGR4"},
		{"T:SLADRIP1", "T:SLADRIP2", "T:SLADRIP3"},
		{"T:BLODRIP1", "T:BLODRIP2", "T:BLODRIP3", "T:BLODRIP4"},
		{"T:FIREWALA", "T:FIREWALB", "T:FIREWALL"},
		{"T:GSTFONT1", "T:GSTFONT2", "T:GSTFONT3"},
		{"T:FIRELAVA", "T:FIRELAV3"},
		{"T:FIREMAG1", "T:FIREMAG2", "T:FIREMAG3"},
		{"T:FIREBLU1", "T:FIREBLU2"},
		{"T:ROCKRED1", "T:ROCKRED2", "T:ROCKRED3"},
		{"T:BFALL1", "T:BFALL2", "T:BFALL3", "T:BFALL4"},
		{"T:SFALL1", "T:SFALL2", "T:SFALL3", "T:SFALL4"},
		{"T:WFALL1", "T:WFALL2", "T:WFALL3", "T:WFALL4"},
		{"T:DBRAIN1", "T:DBRAIN2", "T:DBRAIN3", "T:DBRAIN4"},
	};
	private final Map<Integer, int[]> animationOf = new HashMap<>();

	public ClientMap(int index, String name) {
		this.index = index;
		this.map = new DoomMap(WadFile.shared(), name);
		DoomGraphics g = DoomGraphics.get();

		Set<String> walls = new LinkedHashSet<>();
		Set<String> flats = new LinkedHashSet<>();
		for (DoomMap.Side side : map.sides) {
			for (String t : new String[] {side.upper(), side.middle(), side.lower()}) {
				if (!t.equals("-") && g.hasTexture(t)) {
					walls.add(t);
					String other = t.startsWith("SW1") ? "SW2" + t.substring(3) : t.startsWith("SW2") ? "SW1" + t.substring(3) : null;
					if (other != null && g.hasTexture(other)) {
						walls.add(other);
					}
				}
			}
		}
		for (DoomMap.Sector s : map.sectors) {
			flats.add(s.floorPic);
			flats.add(s.ceilingPic);
		}
		// Bring in every frame of any animation the map uses.
		for (String[] group : ANIMATIONS) {
			boolean used = false;
			for (String member : group) {
				used |= member.startsWith("T:") ? walls.contains(member.substring(2)) : flats.contains(member.substring(2));
			}
			if (used) {
				for (String member : group) {
					String bare = member.substring(2);
					if (member.startsWith("T:") && g.hasTexture(bare)) {
						walls.add(bare);
					} else if (member.startsWith("F:") && g.hasFlat(bare)) {
						flats.add(bare);
					}
				}
			}
		}
		flats.removeIf(f -> !g.hasFlat(f));
		atlas = g.levelAtlas(name, walls, flats);

		for (String t : walls) {
			textureId("T:" + t);
		}
		for (String f : flats) {
			textureId("F:" + f);
		}
		for (String[] group : ANIMATIONS) {
			int[] ids = new int[group.length];
			boolean complete = true;
			for (int i = 0; i < group.length; i++) {
				Integer id = textureIds.get(group[i]);
				if (id == null) {
					complete = false;
					break;
				}
				ids[i] = id;
			}
			if (complete) {
				// Stored as {position in group, ids...}.
				for (int i = 0; i < ids.length; i++) {
					int[] entry = new int[ids.length + 1];
					entry[0] = i;
					System.arraycopy(ids, 0, entry, 1, ids.length);
					animationOf.put(ids[i], entry);
				}
			}
		}

		for (int s = 0; s < map.sectors.size(); s++) {
			neighbours.add(new HashSet<>());
		}
		for (DoomMap.Line line : map.lines) {
			if (line.twoSided()) {
				int a = map.front(line).sector();
				int b = map.back(line).sector();
				neighbours.get(a).add(b);
				neighbours.get(b).add(a);
			}
		}

		for (DoomMap.Thing thing : map.things) {
			DoomThings.Info info = DoomThings.get(thing.type());
			if (info == null || info.category() != DoomThings.Category.DECORATION || !DoomThings.onMediumSkill(thing)
				|| !g.hasSprite(info.sprite())) {
				continue;
			}
			int sector = map.sectorAt(thing.x(), thing.y());
			decorations.add(new Decoration(thing.x(), thing.y(), 0, info, sector));
		}
	}

	private int textureId(String key) {
		return textureIds.computeIfAbsent(key, k -> {
			textureNames.add(k);
			return textureNames.size() - 1;
		});
	}

	public DoomGraphics.Atlas atlas() {
		return atlas;
	}

	public void setSector(int sector, float floor, float ceiling, int light) {
		DoomMap.Sector s = map.sectors.get(sector);
		s.light = light;
		if (s.floor != floor || s.ceiling != ceiling) {
			s.floor = floor;
			s.ceiling = ceiling;
			dirty = true;
		}
	}

	public void setSwitch(int line, boolean on) {
		if (on ? switchesOn.add(line) : switchesOn.remove(line)) {
			dirty = true;
		}
	}

	// ------------------------------------------------------------ mesh

	/**
	 * Rebuilds the mesh after a sector moved. Scrolling walls (special 48) go
	 * at the end and are redone every tic with their texture moved one unit
	 * along, the way Doom scrolls them.
	 */
	public void rebuildIfNeeded(long tic) {
		if (dirty) {
			dirty = false;
			quadCount = 0;
			for (int i = 0; i < map.lines.size(); i++) {
				if (map.lines.get(i).special() != 48) {
					buildLine(i);
				}
			}
			for (DoomMap.Subsector ss : map.subsectors) {
				buildFlats(ss);
			}
			staticQuads = quadCount;
			scrolledAt = -1;
		}
		if (tic != scrolledAt) {
			scrolledAt = tic;
			quadCount = staticQuads;
			scroll = (int) (tic % 4096);
			for (int i = 0; i < map.lines.size(); i++) {
				if (map.lines.get(i).special() == 48) {
					buildLine(i);
				}
			}
			scroll = 0;
		}
	}

	private void buildLine(int lineIndex) {
		DoomMap.Line line = map.lines.get(lineIndex);
		DoomMap.Vertex a = map.vertices.get(line.v1());
		DoomMap.Vertex b = map.vertices.get(line.v2());
		// Doom's fake contrast: walls running east-west are darker, north-south lighter.
		int contrast = a.y() == b.y() ? -16 : a.x() == b.x() ? 16 : 0;
		boolean switched = switchesOn.contains(lineIndex);
		buildSide(line, map.front(line), map.back(line), a, b, contrast, switched);
		if (line.twoSided()) {
			buildSide(line, map.back(line), map.front(line), b, a, contrast, false);
		}
	}

	private void buildSide(DoomMap.Line line, DoomMap.Side side, DoomMap.Side other, DoomMap.Vertex a, DoomMap.Vertex b, int contrast,
		boolean switched) {
		DoomMap.Sector front = map.sectors.get(side.sector());
		float length = (float) Math.hypot(b.x() - a.x(), b.y() - a.y());
		boolean pegTop = (line.flags() & DoomMap.ML_DONTPEGTOP) != 0;
		boolean pegBottom = (line.flags() & DoomMap.ML_DONTPEGBOTTOM) != 0;

		if (other == null) {
			String tex = swap(side.middle(), switched);
			int h = textureHeight(tex);
			float top = pegBottom ? front.floor + h : front.ceiling;
			wall(a, b, length, side, tex, front.floor, front.ceiling, top, side.sector(), contrast, true);
			return;
		}

		DoomMap.Sector back = map.sectors.get(other.sector());
		if (back.ceiling < front.ceiling && !(front.skyCeiling() && back.skyCeiling())) {
			String tex = swap(side.upper(), switched);
			int h = textureHeight(tex);
			float top = pegTop ? front.ceiling : back.ceiling + h;
			wall(a, b, length, side, tex, back.ceiling, front.ceiling, top, side.sector(), contrast, true);
		}
		if (back.floor > front.floor) {
			String tex = swap(side.lower(), switched);
			float top = pegBottom ? front.ceiling : back.floor;
			wall(a, b, length, side, tex, front.floor, back.floor, top, side.sector(), contrast, true);
		}
		String mid = swap(side.middle(), switched);
		if (!mid.equals("-")) {
			// Masked middle textures (grates, windows) are not tiled vertically.
			int h = textureHeight(mid);
			float openBottom = Math.max(front.floor, back.floor);
			float openTop = Math.min(front.ceiling, back.ceiling);
			float top = pegBottom ? openBottom + h + side.yOffset() : openTop + side.yOffset();
			float bottom = top - h;
			wall(a, b, length, side, mid, Math.max(bottom, openBottom), Math.min(top, openTop), top, side.sector(), contrast, false);
		}
	}

	private static String swap(String tex, boolean switched) {
		if (!switched) {
			return tex;
		}
		if (tex.startsWith("SW1")) {
			return "SW2" + tex.substring(3);
		}
		if (tex.startsWith("SW2")) {
			return "SW1" + tex.substring(3);
		}
		return tex;
	}

	private int textureHeight(String tex) {
		DoomGraphics.Region r = atlas.region("T:" + tex);
		return r == null ? 128 : r.height();
	}

	/**
	 * Adds a wall from height z0 to z1. Texture row 0 sits at height {@code vTop}
	 * (plus the sidedef's offset), columns start at the sidedef's x offset.
	 */
	private void wall(DoomMap.Vertex a, DoomMap.Vertex b, float length, DoomMap.Side side, String tex, float z0, float z1, float vTop,
		int sector, int contrast, boolean tileVertically) {
		if (z1 <= z0 || length <= 0) {
			return;
		}
		Integer id = textureIds.get("T:" + tex);
		DoomGraphics.Region r = id == null ? null : atlas.region("T:" + tex);
		if (r == null) {
			return;
		}
		float w = r.width();
		float h = r.height();
		float u0 = side.xOffset() + scroll;
		float u1 = u0 + length;
		float vA = vTop - z1 + (tileVertically ? side.yOffset() : 0);
		float vB = vTop - z0 + (tileVertically ? side.yOffset() : 0);

		for (float ut = (float) Math.floor(u0 / w) * w; ut < u1; ut += w) {
			float ua = Math.max(u0, ut);
			float ub = Math.min(u1, ut + w);
			if (ub - ua < 0.001f) {
				continue;
			}
			float ta = (ua - u0) / length;
			float tb = (ub - u0) / length;
			double xa = a.x() + (b.x() - a.x()) * ta, ya = a.y() + (b.y() - a.y()) * ta;
			double xb = a.x() + (b.x() - a.x()) * tb, yb = a.y() + (b.y() - a.y()) * tb;
			for (float vt = (float) Math.floor(vA / h) * h; vt < vB; vt += h) {
				float va = Math.max(vA, vt);
				float vb = Math.min(vB, vt + h);
				if (vb - va < 0.001f) {
					continue;
				}
				float zTop = z1 - (va - vA);
				float zBottom = z1 - (vb - vA);
				addQuad(id, sector, contrast,
					xa, zBottom, ya, ua - ut, vb - vt,
					xb, zBottom, yb, ub - ut, vb - vt,
					xb, zTop, yb, ub - ut, va - vt,
					xa, zTop, ya, ua - ut, va - vt);
			}
		}
	}

	private void buildFlats(DoomMap.Subsector ss) {
		DoomMap.Sector s = map.sectors.get(ss.sector());
		flat(ss, s.floorPic, s.floor, ss.sector());
		if (!s.skyCeiling()) {
			flat(ss, s.ceilingPic, s.ceiling, ss.sector());
		}
	}

	/** A floor or ceiling polygon, cut along Doom's 64 unit flat grid. */
	private void flat(DoomMap.Subsector ss, String pic, float z, int sector) {
		Integer id = textureIds.get("F:" + pic);
		if (id == null) {
			return;
		}
		float[] xs = ss.xs();
		float[] ys = ss.ys();
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < xs.length; i++) {
			minX = Math.min(minX, xs[i]);
			maxX = Math.max(maxX, xs[i]);
			minY = Math.min(minY, ys[i]);
			maxY = Math.max(maxY, ys[i]);
		}
		for (int cx = (int) Math.floor(minX / 64); cx * 64 < maxX; cx++) {
			for (int cy = (int) Math.floor(minY / 64); cy * 64 < maxY; cy++) {
				float[][] p = new float[][] {xs, ys};
				p = clip(p, cx * 64, 0, 1, 0);
				p = clip(p, (cx + 1) * 64, 0, -1, 0);
				p = clip(p, 0, cy * 64, 0, 1);
				p = clip(p, 0, (cy + 1) * 64, 0, -1);
				int n = p[0].length;
				if (n < 3) {
					continue;
				}
				// Split the convex piece into a fan of (possibly degenerate) quads.
				for (int i = 1; i + 1 < n; i++) {
					float x0 = p[0][0], y0 = p[1][0];
					float x1 = p[0][i], y1 = p[1][i];
					float x2 = p[0][i + 1], y2 = p[1][i + 1];
					addQuad(id, sector, 0,
						x0, z, y0, x0 - cx * 64, (cy + 1) * 64 - y0,
						x1, z, y1, x1 - cx * 64, (cy + 1) * 64 - y1,
						x2, z, y2, x2 - cx * 64, (cy + 1) * 64 - y2,
						x2, z, y2, x2 - cx * 64, (cy + 1) * 64 - y2);
				}
			}
		}
	}

	private static float[][] clip(float[][] p, float px, float py, float nx, float ny) {
		float[] xs = p[0];
		float[] ys = p[1];
		int n = xs.length;
		float[] ox = new float[n * 2];
		float[] oy = new float[n * 2];
		int c = 0;
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			float di = nx * (xs[i] - px) + ny * (ys[i] - py);
			float dj = nx * (xs[j] - px) + ny * (ys[j] - py);
			if (di >= 0) {
				ox[c] = xs[i];
				oy[c++] = ys[i];
			}
			if ((di >= 0) != (dj >= 0)) {
				float t = di / (di - dj);
				ox[c] = xs[i] + t * (xs[j] - xs[i]);
				oy[c++] = ys[i] + t * (ys[j] - ys[i]);
			}
		}
		return new float[][] {java.util.Arrays.copyOf(ox, c), java.util.Arrays.copyOf(oy, c)};
	}

	/** Positions are Doom (x, height, y). They are converted to world blocks here. */
	private void addQuad(int texture, int sector, int contrast, double... v) {
		if (quadCount == quadTexture.length) {
			int n = Math.max(1024, quadCount * 2);
			quadTexture = java.util.Arrays.copyOf(quadTexture, n);
			quadSector = java.util.Arrays.copyOf(quadSector, n);
			quadContrast = java.util.Arrays.copyOf(quadContrast, n);
			verts = java.util.Arrays.copyOf(verts, n * 20);
		}
		int base = quadCount * 20;
		for (int i = 0; i < 4; i++) {
			verts[base + i * 5] = (float) DoomGeometry.worldX(index, v[i * 5]);
			verts[base + i * 5 + 1] = (float) DoomGeometry.worldY(v[i * 5 + 1]);
			verts[base + i * 5 + 2] = (float) DoomGeometry.worldZ(v[i * 5 + 2]);
			verts[base + i * 5 + 3] = (float) v[i * 5 + 3];
			verts[base + i * 5 + 4] = (float) v[i * 5 + 4];
		}
		quadTexture[quadCount] = texture;
		quadSector[quadCount] = sector;
		quadContrast[quadCount] = contrast;
		quadCount++;
	}

	public int quadCount() {
		return quadCount;
	}

	public float[] verts() {
		return verts;
	}

	public int quadSector(int q) {
		return quadSector[q];
	}

	public int quadContrast(int q) {
		return quadContrast[q];
	}

	/** The atlas region for a quad's texture right now, following animations. */
	public DoomGraphics.Region region(int q, long tic) {
		int texture = quadTexture[q];
		int[] anim = animationOf.get(texture);
		if (anim != null) {
			int n = anim.length - 1;
			texture = anim[1 + (int) ((tic / 8 + anim[0]) % n)];
		}
		return atlas.region(textureNames.get(texture));
	}

	// ------------------------------------------------------------ light

	/** A sector's light this tic, including Doom's flicker, strobe and glow specials. */
	public int light(int sector, long tic) {
		DoomMap.Sector s = map.sectors.get(sector);
		int max = s.light;
		int min = max;
		for (int n : neighbours.get(sector)) {
			min = Math.min(min, map.sectors.get(n).light);
		}
		if (min == max) {
			min = 0;
		}
		long seed = tic / 4 + sector * 7919L;
		return switch (s.special) {
			case 1 -> hash(seed) % 64 < 6 ? min : max;
			case 2, 4 -> (tic + sector * 13) % 20 < 5 ? max : min;
			case 3 -> (tic + sector * 13) % 40 < 5 ? max : min;
			case 12 -> tic % 40 < 5 ? max : min;
			case 13 -> tic % 20 < 5 ? max : min;
			case 8 -> {
				int range = Math.max(1, max - min);
				int phase = (int) ((tic * 8) % (range * 2));
				yield phase < range ? max - phase : min + (phase - range);
			}
			case 17 -> max - (int) (hash(seed) % 3) * 16;
			default -> max;
		};
	}

	private static long hash(long x) {
		x ^= x >>> 33;
		x *= 0xff51afd7ed558ccdL;
		x ^= x >>> 33;
		return x & 0x7FFFFFFF;
	}

	public int sectorAtWorld(double worldX, double worldZ) {
		return map.sectorAt((float) DoomGeometry.doomX(index, worldX), (float) DoomGeometry.doomY(worldZ));
	}
}
