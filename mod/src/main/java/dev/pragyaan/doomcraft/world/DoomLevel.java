package dev.pragyaan.doomcraft.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.pragyaan.doomcraft.DoomCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A Doom map built into the Doom dimension. It owns the collision blocks and
 * runs the sector movers (doors, lifts, floors, stairs) that Doom's line
 * specials start. Heights are in Doom units throughout.
 */
public final class DoomLevel {
	public final int index;
	public final DoomMap map;
	public final int originX;

	// Column grid covering the map, one cell per block column.
	final int gx0;
	final int gz0;
	final int gw;
	final int gh;
	private final int[][] cellSectors;
	private final float[] pillarTop;
	private final List<List<Integer>> sectorCells = new ArrayList<>();
	private final List<Set<Integer>> neighbours = new ArrayList<>();
	final int yMin;
	final int yMax;

	private final Map<Integer, Mover> movers = new HashMap<>();
	private final Set<Integer> usedOnce = new HashSet<>();
	private final Map<Integer, Integer> switchesOn = new HashMap<>();
	private final Set<Integer> dirtySectors = new HashSet<>();
	private final Set<Integer> dirtyLines = new HashSet<>();
	private final Set<Integer> secretsFound = new HashSet<>();

	// Doom's intermission tally. Totals count what the map places on the medium skill.
	public int kills;
	public int items;
	public final int totalKills;
	public final int totalItems;
	public final int totalSecrets;

	DoomLevel(int index, DoomMap map) {
		this.index = index;
		this.map = map;
		this.originX = DoomGeometry.originX(index);

		gx0 = (int) Math.floor(DoomGeometry.worldX(index, map.minX)) - 2;
		int gx1 = (int) Math.ceil(DoomGeometry.worldX(index, map.maxX)) + 2;
		gz0 = (int) Math.floor(DoomGeometry.worldZ(map.maxY)) - 2;
		int gz1 = (int) Math.ceil(DoomGeometry.worldZ(map.minY)) + 2;
		gw = gx1 - gx0;
		gh = gz1 - gz0;
		yMin = (int) Math.floor(DoomGeometry.worldY(map.lowestFloor)) - 2;
		yMax = (int) Math.ceil(DoomGeometry.worldY(map.highestCeiling)) + 2;

		for (int s = 0; s < map.sectors.size(); s++) {
			sectorCells.add(new ArrayList<>());
			neighbours.add(new HashSet<>());
		}
		for (DoomMap.Line line : map.lines) {
			if (line.twoSided()) {
				int a = map.front(line).sector();
				int b = map.back(line).sector();
				if (a != b) {
					neighbours.get(a).add(b);
					neighbours.get(b).add(a);
				}
			}
		}

		int monsterCount = 0;
		int itemCount = 0;
		for (DoomMap.Thing thing : map.things) {
			DoomThings.Info info = DoomThings.get(thing.type());
			if (info == null || !DoomThings.onMediumSkill(thing)) {
				continue;
			}
			if (info.category() == DoomThings.Category.MONSTER && thing.type() != DoomMonsterKind.BARREL.doomType) {
				monsterCount++;
			} else if (info.category() == DoomThings.Category.PICKUP) {
				itemCount++;
			}
		}
		totalKills = monsterCount;
		totalItems = itemCount;
		totalSecrets = (int) map.sectors.stream().filter(s -> s.special == 9).count();

		cellSectors = new int[gw * gh][];
		pillarTop = new float[gw * gh];
		java.util.Arrays.fill(pillarTop, Float.NEGATIVE_INFINITY);
		voxelize();
	}

	// ------------------------------------------------------------ voxels

	/**
	 * Samples each column at 4x4 points against the exact subsector polygons.
	 * A column that is mostly outside every sector is wall. Otherwise every
	 * sector that covers part of it counts, and the column takes the highest
	 * floor and lowest ceiling among them, so a thin closed door still seals
	 * the passage.
	 */
	private void voxelize() {
		int n = gw * gh;
		int[] covered = new int[n];
		Map<Long, Integer> counts = new HashMap<>();
		for (DoomMap.Subsector ss : map.subsectors) {
			float[] xs = ss.xs();
			float[] ys = ss.ys();
			float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
			for (int i = 0; i < xs.length; i++) {
				minX = Math.min(minX, xs[i]);
				maxX = Math.max(maxX, xs[i]);
				minY = Math.min(minY, ys[i]);
				maxY = Math.max(maxY, ys[i]);
			}
			// Sample points sit at 8 unit spacing, offset by 4 from the column edges.
			int sx0 = (int) Math.floor((DoomGeometry.worldX(index, minX) - gx0) * 4 - 0.5);
			int sx1 = (int) Math.ceil((DoomGeometry.worldX(index, maxX) - gx0) * 4 - 0.5);
			int sz0 = (int) Math.floor((DoomGeometry.worldZ(maxY) - gz0) * 4 - 0.5);
			int sz1 = (int) Math.ceil((DoomGeometry.worldZ(minY) - gz0) * 4 - 0.5);
			for (int sz = Math.max(0, sz0); sz <= Math.min(gh * 4 - 1, sz1); sz++) {
				for (int sx = Math.max(0, sx0); sx <= Math.min(gw * 4 - 1, sx1); sx++) {
					double wx = gx0 + (sx + 0.5) / 4.0;
					double wz = gz0 + (sz + 0.5) / 4.0;
					float px = (float) DoomGeometry.doomX(index, wx);
					float py = (float) DoomGeometry.doomY(wz);
					if (inside(xs, ys, px, py)) {
						int cell = (sx / 4) + (sz / 4) * gw;
						covered[cell]++;
						counts.merge((long) cell << 16 | ss.sector(), 1, Integer::sum);
					}
				}
			}
		}

		List<List<int[]>> perCell = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			perCell.add(null);
		}
		for (Map.Entry<Long, Integer> e : counts.entrySet()) {
			int cell = (int) (e.getKey() >>> 16);
			int sector = (int) (e.getKey() & 0xFFFF);
			if (perCell.get(cell) == null) {
				perCell.set(cell, new ArrayList<>());
			}
			perCell.get(cell).add(new int[] {sector, e.getValue()});
		}

		for (int cell = 0; cell < n; cell++) {
			List<int[]> list = perCell.get(cell);
			if (list == null || 16 - covered[cell] >= 6) {
				cellSectors[cell] = new int[0];
				continue;
			}
			int best = 0;
			for (int[] p : list) {
				best = Math.max(best, p[1]);
			}
			List<Integer> keep = new ArrayList<>();
			for (int[] p : list) {
				if (p[1] >= 2 || p[1] == best) {
					keep.add(p[0]);
				}
			}
			cellSectors[cell] = keep.stream().mapToInt(Integer::intValue).toArray();
			for (int s : cellSectors[cell]) {
				sectorCells.get(s).add(cell);
			}
		}

		// Impassable two-sided lines, such as railings and windows, become wall.
		for (DoomMap.Line line : map.lines) {
			if (!line.twoSided() || (line.flags() & DoomMap.ML_BLOCKING) == 0) {
				continue;
			}
			DoomMap.Vertex a = map.vertices.get(line.v1());
			DoomMap.Vertex b = map.vertices.get(line.v2());
			float len = (float) Math.hypot(b.x() - a.x(), b.y() - a.y());
			for (float t = 0; t <= len; t += 4) {
				int cell = cellAt(a.x() + (b.x() - a.x()) * t / len, a.y() + (b.y() - a.y()) * t / len);
				if (cell >= 0) {
					cellSectors[cell] = new int[0];
				}
			}
		}

		// Solid decorations such as columns, lamps and trees stand as pillars.
		for (DoomMap.Thing thing : map.things) {
			DoomThings.Info info = DoomThings.get(thing.type());
			if (info == null || info.category() != DoomThings.Category.DECORATION || !info.solid() || info.hanging()
				|| !DoomThings.onMediumSkill(thing)) {
				continue;
			}
			int cell = cellAt(thing.x(), thing.y());
			if (cell >= 0 && cellSectors[cell].length > 0) {
				DoomMap.Sector sector = map.sectors.get(map.sectorAt(thing.x(), thing.y()));
				pillarTop[cell] = Math.max(pillarTop[cell], sector.floor + info.height());
			}
		}
	}

	private static boolean inside(float[] xs, float[] ys, float px, float py) {
		for (int i = 0, n = xs.length; i < n; i++) {
			int j = (i + 1) % n;
			if ((xs[j] - xs[i]) * (py - ys[i]) - (ys[j] - ys[i]) * (px - xs[i]) < -0.01f) {
				return false;
			}
		}
		return true;
	}

	int cellAt(double doomX, double doomY) {
		int bx = (int) Math.floor(DoomGeometry.worldX(index, doomX)) - gx0;
		int bz = (int) Math.floor(DoomGeometry.worldZ(doomY)) - gz0;
		if (bx < 0 || bz < 0 || bx >= gw || bz >= gh) {
			return -1;
		}
		return bx + bz * gw;
	}

	/** Places every collision block. Runs once, the first time the map is visited. */
	void build(ServerLevel level) {
		for (int cell = 0; cell < gw * gh; cell++) {
			fillColumn(level, cell);
		}
		// A bedrock floor under the whole strip keeps anything from falling forever.
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cell = 0; cell < gw * gh; cell++) {
			pos.set(gx0 + cell % gw, yMin - 1, gz0 + cell / gw);
			level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	/** The floor and ceiling a column collides at, in Doom units, or null if it is solid wall. */
	float[] column(int cell) {
		int[] sectors = cellSectors[cell];
		if (sectors.length == 0) {
			return null;
		}
		float floor = Float.NEGATIVE_INFINITY;
		float ceiling = Float.POSITIVE_INFINITY;
		for (int s : sectors) {
			DoomMap.Sector sector = map.sectors.get(s);
			floor = Math.max(floor, sector.floor);
			ceiling = Math.min(ceiling, sector.ceiling);
		}
		return new float[] {Math.max(floor, pillarTop[cell]), ceiling};
	}

	private void fillColumn(ServerLevel level, int cell) {
		float[] column = column(cell);
		boolean wall = column == null;
		float floor = wall ? 0 : column[0];
		float ceiling = wall ? 0 : column[1];

		double fy = DoomGeometry.worldY(floor);
		double cy = DoomGeometry.worldY(ceiling);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState full = DoomCraft.DOOM_SOLID.defaultBlockState();
		int x = gx0 + cell % gw;
		int z = gz0 + cell / gw;
		for (int y = yMin; y <= yMax; y++) {
			BlockState state;
			if (wall || ceiling - floor < 1) {
				state = full;
			} else {
				double floorPart = Math.max(0, Math.min(1, fy - y));
				double ceilPart = Math.max(0, Math.min(1, y + 1 - cy));
				if (floorPart >= 0.999 || ceilPart >= 0.999 || (floorPart > 0 && ceilPart > 0)) {
					state = full;
				} else if (floorPart > 0) {
					int layers = (int) Math.round(floorPart * 8);
					state = layers == 0 ? Blocks.AIR.defaultBlockState() : full.setValue(DoomSolidBlock.LAYERS, layers);
				} else if (ceilPart > 0) {
					int layers = (int) Math.round(ceilPart * 8);
					state = layers == 0 ? Blocks.AIR.defaultBlockState()
						: full.setValue(DoomSolidBlock.LAYERS, layers).setValue(DoomSolidBlock.HANGING, true);
				} else {
					state = Blocks.AIR.defaultBlockState();
				}
			}
			pos.set(x, y, z);
			if (level.getBlockState(pos) != state) {
				level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
	}

	private void refreshSector(ServerLevel level, int sector) {
		for (int cell : sectorCells.get(sector)) {
			fillColumn(level, cell);
		}
		dirtySectors.add(sector);
	}

	/** Columns belonging to a sector, as block coordinates {x, z}. */
	List<int[]> sectorColumns(int sector) {
		List<int[]> out = new ArrayList<>();
		for (int cell : sectorCells.get(sector)) {
			out.add(new int[] {gx0 + cell % gw, gz0 + cell / gw});
		}
		return out;
	}

	public AABB bounds() {
		return new AABB(gx0, yMin, gz0, gx0 + gw, yMax + 1, gz0 + gh);
	}

	// ------------------------------------------------------------ sector queries

	private float lowestCeilingAround(int s) {
		float v = Float.MAX_VALUE;
		for (int n : neighbours.get(s)) {
			v = Math.min(v, map.sectors.get(n).ceiling);
		}
		return v == Float.MAX_VALUE ? map.sectors.get(s).ceiling : v;
	}

	private float highestCeilingAround(int s) {
		float v = -Float.MAX_VALUE;
		for (int n : neighbours.get(s)) {
			v = Math.max(v, map.sectors.get(n).ceiling);
		}
		return v == -Float.MAX_VALUE ? map.sectors.get(s).ceiling : v;
	}

	private float lowestFloorAround(int s) {
		float v = map.sectors.get(s).floor;
		for (int n : neighbours.get(s)) {
			v = Math.min(v, map.sectors.get(n).floor);
		}
		return v;
	}

	private float highestFloorAround(int s) {
		float v = -500;
		for (int n : neighbours.get(s)) {
			v = Math.max(v, map.sectors.get(n).floor);
		}
		return v;
	}

	private float nextHigherFloor(int s) {
		float own = map.sectors.get(s).floor;
		float v = Float.MAX_VALUE;
		for (int n : neighbours.get(s)) {
			float f = map.sectors.get(n).floor;
			if (f > own) {
				v = Math.min(v, f);
			}
		}
		return v == Float.MAX_VALUE ? own : v;
	}

	private List<Integer> taggedSectors(int tag) {
		List<Integer> out = new ArrayList<>();
		for (int s = 0; s < map.sectors.size(); s++) {
			if (map.sectors.get(s).tag == tag) {
				out.add(s);
			}
		}
		return out;
	}

	// ------------------------------------------------------------ movers

	private enum Plane { FLOOR, CEILING }

	private enum DoorKind { NORMAL, OPEN, CLOSE, CLOSE_30_OPEN }

	/** One moving plane. Doors and lifts move out, wait and come back. */
	private final class Mover {
		final int sector;
		final Plane plane;
		float target;
		final float speed;
		float returnTarget = Float.NaN;
		int waitTics;
		int waiting = -1;
		final String startSound;
		final String stopSound;
		final boolean door;
		final boolean reverseWhenBlocked;
		boolean autoClose;
		int soundTimer;

		Mover(int sector, Plane plane, float target, float speed, String startSound, String stopSound, boolean door, boolean reverseWhenBlocked) {
			this.sector = sector;
			this.plane = plane;
			this.target = target;
			this.speed = speed;
			this.startSound = startSound;
			this.stopSound = stopSound;
			this.door = door;
			this.reverseWhenBlocked = reverseWhenBlocked;
		}

		float current() {
			DoomMap.Sector s = map.sectors.get(sector);
			return plane == Plane.FLOOR ? s.floor : s.ceiling;
		}

		void set(float v) {
			DoomMap.Sector s = map.sectors.get(sector);
			if (plane == Plane.FLOOR) {
				s.floor = v;
			} else {
				s.ceiling = v;
			}
		}

		/** Advances one Minecraft tick. Returns true when the mover is finished. */
		boolean tick(ServerLevel level, DoomWorld world) {
			if (waiting >= 0) {
				waiting -= DoomGeometry.TICS_PER_TICK;
				if (waiting < 0) {
					float back = returnTarget;
					returnTarget = Float.NaN;
					target = back;
					sectorSound(level, world, sector, door ? (target > current() ? "DSDOROPN" : "DSDORCLS") : "DSPSTART");
				}
				return false;
			}
			float now = current();
			float step = speed * DoomGeometry.TICS_PER_TICK;
			float next = now < target ? Math.min(target, now + step) : Math.max(target, now - step);

			DoomMap.Sector s = map.sectors.get(sector);
			if (plane == Plane.CEILING && next < now && blocked(level, next)) {
				if (door && reverseWhenBlocked) {
					// A closing door that hits someone opens again, as in Doom.
					target = lowestCeilingAround(sector) - 4;
					if (autoClose) {
						returnTarget = s.floor;
						waitTics = 150;
					}
					sectorSound(level, world, sector, "DSDOROPN");
				}
				return false;
			}
			if (plane == Plane.FLOOR && next > now) {
				lift(level, now, next);
			}
			set(next);
			refreshSector(level, sector);

			if (!door && plane == Plane.FLOOR && speed <= 1 && (soundTimer++ % 5 == 0)) {
				sectorSound(level, world, sector, "DSSTNMOV");
			}
			if (next == target) {
				if (stopSound != null) {
					sectorSound(level, world, sector, stopSound);
				}
				if (!Float.isNaN(returnTarget)) {
					waiting = waitTics;
					return false;
				}
				return true;
			}
			return false;
		}

		private boolean blocked(ServerLevel level, float ceiling) {
			double top = DoomGeometry.worldY(ceiling);
			for (Entity e : entitiesIn(level, sector)) {
				if (e.getBoundingBox().maxY > top && e.getBoundingBox().minY < top) {
					return true;
				}
			}
			return false;
		}

		private void lift(ServerLevel level, float from, float to) {
			double oldY = DoomGeometry.worldY(from);
			double newY = DoomGeometry.worldY(to);
			for (Entity e : entitiesIn(level, sector)) {
				if (e.getY() >= oldY - 0.2 && e.getY() <= newY + 0.05) {
					e.setPos(e.getX(), newY + 0.01, e.getZ());
					e.needsSync = true;
				}
			}
		}
	}

	private List<Entity> entitiesIn(ServerLevel level, int sector) {
		List<Entity> out = new ArrayList<>();
		for (int cell : sectorCells.get(sector)) {
			int x = gx0 + cell % gw;
			int z = gz0 + cell / gw;
			out.addAll(level.getEntitiesOfClass(Entity.class, new AABB(x, yMin, z, x + 1, yMax + 1, z + 1), e -> !out.contains(e)));
		}
		return out;
	}

	private boolean busy(int sector) {
		return movers.containsKey(sector);
	}

	private boolean doDoor(ServerLevel level, DoomWorld world, int sector, DoorKind kind, float speed) {
		Mover existing = movers.get(sector);
		if (existing != null) {
			return false;
		}
		boolean blazing = speed > 2;
		DoomMap.Sector s = map.sectors.get(sector);
		float open = lowestCeilingAround(sector) - 4;
		Mover m;
		switch (kind) {
			case NORMAL -> {
				m = new Mover(sector, Plane.CEILING, open, speed, null, null, true, true);
				m.returnTarget = s.floor;
				m.waitTics = 150;
				m.autoClose = true;
				sectorSound(level, world, sector, blazing ? "DSBDOPN" : "DSDOROPN");
			}
			case OPEN -> {
				m = new Mover(sector, Plane.CEILING, open, speed, null, null, true, false);
				sectorSound(level, world, sector, blazing ? "DSBDOPN" : "DSDOROPN");
			}
			case CLOSE -> {
				m = new Mover(sector, Plane.CEILING, s.floor, speed, null, null, true, true);
				sectorSound(level, world, sector, blazing ? "DSBDCLS" : "DSDORCLS");
			}
			default -> {
				m = new Mover(sector, Plane.CEILING, s.floor, speed, null, null, true, true);
				m.returnTarget = open;
				m.waitTics = 30 * 35;
				sectorSound(level, world, sector, "DSDORCLS");
			}
		}
		movers.put(sector, m);
		return true;
	}

	/** Re-using a door that is already open (DR lines) closes it, as in Doom. */
	private boolean manualDoor(ServerLevel level, DoomWorld world, int sector, DoorKind kind, float speed) {
		Mover m = movers.get(sector);
		if (m != null && m.door && kind == DoorKind.NORMAL) {
			if (m.waiting >= 0 || m.target > map.sectors.get(sector).floor) {
				m.waiting = -1;
				m.returnTarget = Float.NaN;
				m.target = map.sectors.get(sector).floor;
				sectorSound(level, world, sector, "DSDORCLS");
				return true;
			}
			m.target = lowestCeilingAround(sector) - 4;
			m.returnTarget = map.sectors.get(sector).floor;
			sectorSound(level, world, sector, "DSDOROPN");
			return true;
		}
		return doDoor(level, world, sector, kind, speed);
	}

	private boolean doLift(ServerLevel level, DoomWorld world, int sector, float speed) {
		if (busy(sector)) {
			return false;
		}
		DoomMap.Sector s = map.sectors.get(sector);
		float low = Math.min(lowestFloorAround(sector), s.floor);
		Mover m = new Mover(sector, Plane.FLOOR, low, speed, "DSPSTART", "DSPSTOP", false, false);
		m.returnTarget = s.floor;
		m.waitTics = 105;
		movers.put(sector, m);
		sectorSound(level, world, sector, "DSPSTART");
		return true;
	}

	private boolean doFloor(ServerLevel level, DoomWorld world, int sector, float target, float speed) {
		if (busy(sector)) {
			return false;
		}
		DoomMap.Sector s = map.sectors.get(sector);
		target = Math.min(target, s.ceiling);
		Mover m = new Mover(sector, Plane.FLOOR, target, speed, null, "DSPSTOP", false, false);
		movers.put(sector, m);
		return true;
	}

	private boolean doCeiling(ServerLevel level, DoomWorld world, int sector, float target, float speed) {
		if (busy(sector)) {
			return false;
		}
		movers.put(sector, new Mover(sector, Plane.CEILING, target, speed, null, "DSPSTOP", false, false));
		return true;
	}

	/** Raises a staircase: each next step is the sector behind this one with the same floor texture. */
	private boolean doStairs(ServerLevel level, DoomWorld world, int start, float stepSize, float speed) {
		if (busy(start)) {
			return false;
		}
		int sector = start;
		float height = map.sectors.get(sector).floor + stepSize;
		Set<Integer> seen = new HashSet<>();
		while (sector >= 0 && seen.add(sector)) {
			movers.put(sector, new Mover(sector, Plane.FLOOR, height, speed, null, null, false, false));
			String pic = map.sectors.get(sector).floorPic;
			int next = -1;
			for (DoomMap.Line line : map.lines) {
				if (!line.twoSided() || map.front(line).sector() != sector) {
					continue;
				}
				int back = map.back(line).sector();
				if (map.sectors.get(back).floorPic.equals(pic) && !seen.contains(back) && !busy(back)) {
					next = back;
					break;
				}
			}
			sector = next;
			height += stepSize;
		}
		return true;
	}

	// ------------------------------------------------------------ specials

	enum Trigger { USE, WALK, SHOOT }

	/** Runs a line's special. Returns true if anything happened. */
	boolean activate(ServerLevel level, DoomWorld world, int lineIndex, Trigger trigger, Entity who) {
		DoomMap.Line line = map.lines.get(lineIndex);
		int special = line.special();
		if (special == 0 || usedOnce.contains(lineIndex)) {
			return false;
		}
		Special sp = Special.of(special);
		if (sp == null || sp.trigger != trigger) {
			return false;
		}
		if (sp.key != null && !world.hasKey(who, sp.key)) {
			world.message(who, "You need a " + sp.key + " key to open this door");
			world.soundAt(level, who.position(), "DSOOF");
			return false;
		}

		boolean did = false;
		if (sp.manual) {
			if (line.back() >= 0) {
				int sector = map.back(line).sector();
				did = manualDoor(level, world, sector, sp.door, sp.speed);
			}
		} else if (sp.action == Action.EXIT || sp.action == Action.SECRET_EXIT) {
			setSwitch(lineIndex, sp.repeat, level, world, true);
			world.exitLevel(level, this, sp.action == Action.SECRET_EXIT);
			return true;
		} else if (sp.action == Action.TELEPORT) {
			return world.teleport(level, this, line.tag(), who);
		} else {
			for (int s : taggedSectors(line.tag())) {
				did |= run(level, world, sp, s);
			}
		}

		if (did || trigger == Trigger.USE) {
			if (!sp.repeat) {
				usedOnce.add(lineIndex);
			}
			if (trigger == Trigger.USE || trigger == Trigger.SHOOT) {
				setSwitch(lineIndex, sp.repeat, level, world, false);
			}
		}
		return did;
	}

	private boolean run(ServerLevel level, DoomWorld world, Special sp, int s) {
		DoomMap.Sector sector = map.sectors.get(s);
		return switch (sp.action) {
			case DOOR -> doDoor(level, world, s, sp.door, sp.speed);
			case LIFT -> doLift(level, world, s, sp.speed);
			case FLOOR_LOWEST -> doFloor(level, world, s, lowestFloorAround(s), sp.speed);
			case FLOOR_HIGHEST -> doFloor(level, world, s, highestFloorAround(s) + sp.amount, sp.speed);
			case FLOOR_TO_CEILING -> doFloor(level, world, s, lowestCeilingAround(s) - sp.amount, sp.speed);
			case FLOOR_NEXT -> doFloor(level, world, s, nextHigherFloor(s), sp.speed);
			case FLOOR_BY -> doFloor(level, world, s, sector.floor + sp.amount, sp.speed);
			case CEILING_TO_FLOOR -> doCeiling(level, world, s, sector.floor + sp.amount, sp.speed);
			case CEILING_HIGHEST -> doCeiling(level, world, s, highestCeilingAround(s), sp.speed);
			case STAIRS -> doStairs(level, world, s, sp.amount, sp.speed);
			case DONUT -> doDonut(level, world, s);
			case LIGHT_SET -> setLight(s, (int) sp.amount);
			case LIGHT_MAX_NEIGHBOUR -> setLight(s, neighbourLight(s, true));
			case LIGHT_MIN_NEIGHBOUR -> setLight(s, neighbourLight(s, false));
			default -> false;
		};
	}

	/**
	 * EV_DoDonut: the tagged pillar sinks while the ring around it rises, both
	 * to the floor height outside the ring.
	 */
	private boolean doDonut(ServerLevel level, DoomWorld world, int pillar) {
		for (int ring : neighbours.get(pillar)) {
			for (int outside : neighbours.get(ring)) {
				if (outside == pillar) {
					continue;
				}
				float target = map.sectors.get(outside).floor;
				doFloor(level, world, ring, target, 0.5f);
				doFloor(level, world, pillar, target, 0.5f);
				return true;
			}
		}
		return false;
	}

	private boolean setLight(int sector, int light) {
		DoomMap.Sector s = map.sectors.get(sector);
		if (s.light == light) {
			return false;
		}
		s.light = light;
		dirtySectors.add(sector);
		return true;
	}

	private int neighbourLight(int sector, boolean brightest) {
		int v = brightest ? 0 : 255;
		for (int n : neighbours.get(sector)) {
			int l = map.sectors.get(n).light;
			v = brightest ? Math.max(v, l) : Math.min(v, l);
		}
		return v;
	}

	/** Lowers every sector with this tag to its lowest neighbouring floor. */
	void lowerTagged(ServerLevel level, DoomWorld world, int tag) {
		for (int s : taggedSectors(tag)) {
			doFloor(level, world, s, lowestFloorAround(s), 1);
		}
	}

	/** Flips a switch texture. Repeatable switches flip back after a second. */
	private void setSwitch(int lineIndex, boolean repeat, ServerLevel level, DoomWorld world, boolean exit) {
		DoomMap.Side side = map.front(map.lines.get(lineIndex));
		boolean hasSwitch = side.upper().startsWith("SW1") || side.middle().startsWith("SW1") || side.lower().startsWith("SW1");
		if (!hasSwitch) {
			return;
		}
		switchesOn.put(lineIndex, repeat ? 35 : -1);
		dirtyLines.add(lineIndex);
		DoomMap.Vertex a = map.vertices.get(map.lines.get(lineIndex).v1());
		DoomMap.Vertex b = map.vertices.get(map.lines.get(lineIndex).v2());
		DoomMap.Sector front = map.sectors.get(side.sector());
		world.soundAt(level, new net.minecraft.world.phys.Vec3(DoomGeometry.worldX(index, (a.x() + b.x()) / 2),
			DoomGeometry.worldY(front.floor + 32), DoomGeometry.worldZ((a.y() + b.y()) / 2)), exit ? "DSSWTCHX" : "DSSWTCHN");
	}

	public boolean switchOn(int lineIndex) {
		return switchesOn.containsKey(lineIndex);
	}

	public Set<Integer> switchesOn() {
		return switchesOn.keySet();
	}

	void tick(ServerLevel level, DoomWorld world) {
		movers.values().removeIf(m -> m.tick(level, world));
		switchesOn.entrySet().removeIf(e -> {
			if (e.getValue() < 0) {
				return false;
			}
			int left = e.getValue() - 2;
			if (left <= 0) {
				dirtyLines.add(e.getKey());
				return true;
			}
			e.setValue(left);
			return false;
		});
	}

	public int secretCount() {
		return secretsFound.size();
	}

	boolean secretFound(int sector) {
		return !secretsFound.add(sector);
	}

	/** Sectors and switches that changed since the last call, for syncing clients. */
	Set<Integer> takeDirtySectors() {
		Set<Integer> out = new HashSet<>(dirtySectors);
		dirtySectors.clear();
		return out;
	}

	Set<Integer> takeDirtyLines() {
		Set<Integer> out = new HashSet<>(dirtyLines);
		dirtyLines.clear();
		return out;
	}

	/** Plays a sound from the middle of a sector, at the height of its opening. */
	private void sectorSound(ServerLevel level, DoomWorld world, int sector, String sound) {
		List<Integer> cells = sectorCells.get(sector);
		if (cells.isEmpty()) {
			return;
		}
		double x = 0;
		double z = 0;
		for (int cell : cells) {
			x += gx0 + cell % gw + 0.5;
			z += gz0 + cell / gw + 0.5;
		}
		DoomMap.Sector s = map.sectors.get(sector);
		world.soundAt(level, new net.minecraft.world.phys.Vec3(x / cells.size(), DoomGeometry.worldY(s.floor + 32), z / cells.size()), sound);
	}

	// ------------------------------------------------------------ special table

	enum Action { DOOR, LIFT, FLOOR_LOWEST, FLOOR_HIGHEST, FLOOR_TO_CEILING, FLOOR_NEXT, FLOOR_BY, CEILING_TO_FLOOR, CEILING_HIGHEST,
		STAIRS, EXIT, SECRET_EXIT, TELEPORT, DONUT, LIGHT_SET, LIGHT_MAX_NEIGHBOUR, LIGHT_MIN_NEIGHBOUR }

	/** One row of Doom's line special table (p_spec.c, p_switch.c). */
	static final class Special {
		final Trigger trigger;
		final boolean repeat;
		final boolean manual;
		final Action action;
		final DoorKind door;
		final float speed;
		final float amount;
		final String key;

		private Special(Trigger trigger, boolean repeat, boolean manual, Action action, DoorKind door, float speed, float amount, String key) {
			this.trigger = trigger;
			this.repeat = repeat;
			this.manual = manual;
			this.action = action;
			this.door = door;
			this.speed = speed;
			this.amount = amount;
			this.key = key;
		}

		private static final Map<Integer, Special> TABLE = new HashMap<>();

		private static void manual(int id, boolean repeat, DoorKind kind, float speed, String key) {
			TABLE.put(id, new Special(Trigger.USE, repeat, true, Action.DOOR, kind, speed, 0, key));
		}

		private static void door(int id, Trigger t, boolean repeat, DoorKind kind, float speed, String key) {
			TABLE.put(id, new Special(t, repeat, false, Action.DOOR, kind, speed, 0, key));
		}

		private static void act(int id, Trigger t, boolean repeat, Action action, float speed, float amount) {
			TABLE.put(id, new Special(t, repeat, false, action, null, speed, amount, null));
		}

		static {
			Trigger U = Trigger.USE;
			Trigger W = Trigger.WALK;
			Trigger G = Trigger.SHOOT;
			manual(1, true, DoorKind.NORMAL, 2, null);
			manual(26, true, DoorKind.NORMAL, 2, "blue");
			manual(27, true, DoorKind.NORMAL, 2, "yellow");
			manual(28, true, DoorKind.NORMAL, 2, "red");
			manual(31, false, DoorKind.OPEN, 2, null);
			manual(32, false, DoorKind.OPEN, 2, "blue");
			manual(33, false, DoorKind.OPEN, 2, "red");
			manual(34, false, DoorKind.OPEN, 2, "yellow");
			manual(117, true, DoorKind.NORMAL, 8, null);
			manual(118, false, DoorKind.OPEN, 8, null);

			door(2, W, false, DoorKind.OPEN, 2, null);
			door(3, W, false, DoorKind.CLOSE, 2, null);
			door(4, W, false, DoorKind.NORMAL, 2, null);
			door(16, W, false, DoorKind.CLOSE_30_OPEN, 2, null);
			door(75, W, true, DoorKind.CLOSE, 2, null);
			door(76, W, true, DoorKind.CLOSE_30_OPEN, 2, null);
			door(86, W, true, DoorKind.OPEN, 2, null);
			door(90, W, true, DoorKind.NORMAL, 2, null);
			door(105, W, true, DoorKind.NORMAL, 8, null);
			door(106, W, true, DoorKind.OPEN, 8, null);
			door(107, W, true, DoorKind.CLOSE, 8, null);
			door(108, W, false, DoorKind.NORMAL, 8, null);
			door(109, W, false, DoorKind.OPEN, 8, null);
			door(110, W, false, DoorKind.CLOSE, 8, null);
			door(29, U, false, DoorKind.NORMAL, 2, null);
			door(50, U, false, DoorKind.CLOSE, 2, null);
			door(103, U, false, DoorKind.OPEN, 2, null);
			door(42, U, true, DoorKind.CLOSE, 2, null);
			door(61, U, true, DoorKind.OPEN, 2, null);
			door(63, U, true, DoorKind.NORMAL, 2, null);
			door(111, U, false, DoorKind.NORMAL, 8, null);
			door(112, U, false, DoorKind.OPEN, 8, null);
			door(113, U, false, DoorKind.CLOSE, 8, null);
			door(114, U, true, DoorKind.NORMAL, 8, null);
			door(115, U, true, DoorKind.OPEN, 8, null);
			door(116, U, true, DoorKind.CLOSE, 8, null);
			door(99, U, true, DoorKind.OPEN, 8, "blue");
			door(133, U, false, DoorKind.OPEN, 8, "blue");
			door(134, U, true, DoorKind.OPEN, 8, "red");
			door(135, U, false, DoorKind.OPEN, 8, "red");
			door(136, U, true, DoorKind.OPEN, 8, "yellow");
			door(137, U, false, DoorKind.OPEN, 8, "yellow");
			door(46, G, true, DoorKind.OPEN, 2, null);

			act(10, W, false, Action.LIFT, 4, 0);
			act(88, W, true, Action.LIFT, 4, 0);
			act(120, W, true, Action.LIFT, 8, 0);
			act(121, W, false, Action.LIFT, 8, 0);
			act(21, U, false, Action.LIFT, 4, 0);
			act(62, U, true, Action.LIFT, 4, 0);
			act(122, U, false, Action.LIFT, 8, 0);
			act(123, U, true, Action.LIFT, 8, 0);

			act(23, U, false, Action.FLOOR_LOWEST, 1, 0);
			act(60, U, true, Action.FLOOR_LOWEST, 1, 0);
			act(38, W, false, Action.FLOOR_LOWEST, 1, 0);
			act(82, W, true, Action.FLOOR_LOWEST, 1, 0);
			act(37, W, false, Action.FLOOR_LOWEST, 1, 0);
			act(84, W, true, Action.FLOOR_LOWEST, 1, 0);
			act(19, W, false, Action.FLOOR_HIGHEST, 1, 0);
			act(83, W, true, Action.FLOOR_HIGHEST, 1, 0);
			act(45, U, true, Action.FLOOR_HIGHEST, 1, 0);
			act(102, U, false, Action.FLOOR_HIGHEST, 1, 0);
			act(36, W, false, Action.FLOOR_HIGHEST, 4, 8);
			act(98, W, true, Action.FLOOR_HIGHEST, 4, 8);
			act(70, U, true, Action.FLOOR_HIGHEST, 4, 8);
			act(71, U, false, Action.FLOOR_HIGHEST, 4, 8);
			act(5, W, false, Action.FLOOR_TO_CEILING, 1, 0);
			act(91, W, true, Action.FLOOR_TO_CEILING, 1, 0);
			act(101, U, false, Action.FLOOR_TO_CEILING, 1, 0);
			act(64, U, true, Action.FLOOR_TO_CEILING, 1, 0);
			act(24, G, false, Action.FLOOR_TO_CEILING, 1, 0);
			act(55, U, false, Action.FLOOR_TO_CEILING, 1, 8);
			act(65, U, true, Action.FLOOR_TO_CEILING, 1, 8);
			act(56, W, false, Action.FLOOR_TO_CEILING, 1, 8);
			act(94, W, true, Action.FLOOR_TO_CEILING, 1, 8);
			act(18, U, false, Action.FLOOR_NEXT, 1, 0);
			act(69, U, true, Action.FLOOR_NEXT, 1, 0);
			act(119, W, false, Action.FLOOR_NEXT, 1, 0);
			act(128, W, true, Action.FLOOR_NEXT, 1, 0);
			act(20, U, false, Action.FLOOR_NEXT, 0.5f, 0);
			act(68, U, true, Action.FLOOR_NEXT, 0.5f, 0);
			act(22, W, false, Action.FLOOR_NEXT, 0.5f, 0);
			act(95, W, true, Action.FLOOR_NEXT, 0.5f, 0);
			act(47, G, false, Action.FLOOR_NEXT, 0.5f, 0);
			act(130, W, false, Action.FLOOR_NEXT, 4, 0);
			act(131, U, false, Action.FLOOR_NEXT, 4, 0);
			act(129, W, true, Action.FLOOR_NEXT, 4, 0);
			act(132, U, true, Action.FLOOR_NEXT, 4, 0);
			act(58, W, false, Action.FLOOR_BY, 1, 24);
			act(92, W, true, Action.FLOOR_BY, 1, 24);
			act(59, W, false, Action.FLOOR_BY, 1, 24);
			act(93, W, true, Action.FLOOR_BY, 1, 24);
			act(15, U, false, Action.FLOOR_BY, 0.5f, 24);
			act(66, U, true, Action.FLOOR_BY, 0.5f, 24);
			act(14, U, false, Action.FLOOR_BY, 0.5f, 32);
			act(67, U, true, Action.FLOOR_BY, 0.5f, 32);
			act(140, U, false, Action.FLOOR_BY, 1, 512);
			act(41, U, false, Action.CEILING_TO_FLOOR, 1, 0);
			act(43, U, true, Action.CEILING_TO_FLOOR, 1, 0);
			act(44, W, false, Action.CEILING_TO_FLOOR, 1, 8);
			act(40, W, false, Action.CEILING_HIGHEST, 1, 0);
			act(7, U, false, Action.STAIRS, 0.25f, 8);
			act(8, W, false, Action.STAIRS, 0.25f, 8);
			act(127, U, false, Action.STAIRS, 4, 16);
			act(100, W, false, Action.STAIRS, 4, 16);

			act(11, U, false, Action.EXIT, 0, 0);
			act(52, W, false, Action.EXIT, 0, 0);
			act(51, U, false, Action.SECRET_EXIT, 0, 0);
			act(124, W, false, Action.SECRET_EXIT, 0, 0);
			act(39, W, false, Action.TELEPORT, 0, 0);
			act(9, U, false, Action.DONUT, 0, 0);
			act(35, W, false, Action.LIGHT_SET, 0, 35);
			act(79, W, true, Action.LIGHT_SET, 0, 35);
			act(13, W, false, Action.LIGHT_SET, 0, 255);
			act(81, W, true, Action.LIGHT_SET, 0, 255);
			act(138, U, true, Action.LIGHT_SET, 0, 255);
			act(139, U, true, Action.LIGHT_SET, 0, 35);
			act(12, W, false, Action.LIGHT_MAX_NEIGHBOUR, 0, 0);
			act(80, W, true, Action.LIGHT_MAX_NEIGHBOUR, 0, 0);
			act(104, W, false, Action.LIGHT_MIN_NEIGHBOUR, 0, 0);
			act(97, W, true, Action.TELEPORT, 0, 0);
		}

		static Special of(int id) {
			return TABLE.get(id);
		}

		static boolean isUse(int id) {
			Special s = TABLE.get(id);
			return s != null && s.trigger == Trigger.USE;
		}

		static boolean isWalk(int id) {
			Special s = TABLE.get(id);
			return s != null && s.trigger == Trigger.WALK;
		}
	}
}
