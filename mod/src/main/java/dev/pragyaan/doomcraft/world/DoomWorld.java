package dev.pragyaan.doomcraft.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.entity.DoomMonster;
import dev.pragyaan.doomcraft.entity.DoomPickup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Everything about the Doom dimension on the server: which maps are built,
 * taking players in and out, and running the parts of Doom's rules that
 * belong to the level rather than to a monster.
 */
public final class DoomWorld {
	public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, DoomCraft.id("doom"));
	private static final Pattern EPISODE_MAP = Pattern.compile("E(\\d)M(\\d)");
	private static final Pattern DOOM2_MAP = Pattern.compile("MAP(\\d\\d)");

	private static DoomWorld instance;
	private static MinecraftServer instanceServer;

	private final MinecraftServer server;
	private final Map<Integer, DoomLevel> levels = new HashMap<>();
	private final Map<UUID, Set<String>> keys = new HashMap<>();
	private final Map<UUID, Return> returns = new HashMap<>();
	private final Map<UUID, double[]> lastPositions = new HashMap<>();
	private final Map<UUID, Integer> enteredAt = new HashMap<>();
	private final Map<UUID, Integer> currentMap = new HashMap<>();
	private int tickCount;

	private record Return(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {
	}

	private DoomWorld(MinecraftServer server) {
		this.server = server;
	}

	public static synchronized DoomWorld get(MinecraftServer server) {
		if (instance == null || instanceServer != server) {
			instance = new DoomWorld(server);
			instanceServer = server;
		}
		return instance;
	}

	// ------------------------------------------------------------ map names

	/** Maps are laid out in the dimension by this index, so it must not depend on which maps were visited first. */
	public static int mapIndex(String name) {
		Matcher e = EPISODE_MAP.matcher(name);
		if (e.matches()) {
			return (Integer.parseInt(e.group(1)) - 1) * 9 + Integer.parseInt(e.group(2)) - 1;
		}
		Matcher m = DOOM2_MAP.matcher(name);
		if (m.matches()) {
			return Integer.parseInt(m.group(1)) - 1;
		}
		return -1;
	}

	public static String mapName(int index) {
		WadFile wad = WadFile.shared();
		String episode = "E" + (index / 9 + 1) + "M" + (index % 9 + 1);
		if (wad.has(episode)) {
			return episode;
		}
		String doom2 = String.format("MAP%02d", index + 1);
		return wad.has(doom2) ? doom2 : null;
	}

	public static List<String> availableMaps() {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			String name = mapName(i);
			if (name != null) {
				out.add(name);
			}
		}
		return out;
	}

	private static String nextMap(String name, boolean secret) {
		Matcher e = EPISODE_MAP.matcher(name);
		if (e.matches()) {
			int ep = Integer.parseInt(e.group(1));
			int m = Integer.parseInt(e.group(2));
			int[] secretFrom = {3, 5, 6, 2};
			int[] returnTo = {4, 6, 7, 3};
			if (m == 9) {
				return "E" + ep + "M" + returnTo[ep - 1];
			}
			if (secret && m == secretFrom[ep - 1]) {
				return "E" + ep + "M9";
			}
			return m == 8 ? null : "E" + ep + "M" + (m + 1);
		}
		Matcher d = DOOM2_MAP.matcher(name);
		if (d.matches()) {
			int m = Integer.parseInt(d.group(1));
			if (secret && m == 15) {
				return "MAP31";
			}
			if (secret && m == 31) {
				return "MAP32";
			}
			if (m == 31 || m == 32) {
				return "MAP16";
			}
			return m == 30 ? null : String.format("MAP%02d", m + 1);
		}
		return null;
	}

	// ------------------------------------------------------------ levels

	// The game test server is built without datapack dimensions, so tests point this at their own level.
	private ServerLevel testLevel;

	void useLevelForTests(ServerLevel level) {
		testLevel = level;
	}

	public ServerLevel dimension() {
		if (testLevel != null) {
			return testLevel;
		}
		ServerLevel level = server.getLevel(DIMENSION);
		if (level == null) {
			throw new IllegalStateException("The Doom dimension is missing. Is the DoomCraft datapack enabled?");
		}
		return level;
	}

	public DoomLevel level(int index) {
		DoomLevel level = levels.get(index);
		if (level == null) {
			String name = mapName(index);
			if (name == null) {
				return null;
			}
			level = new DoomLevel(index, new DoomMap(WadFile.shared(), name));
			levels.put(index, level);
			ServerLevel world = dimension();
			BlockPos marker = new BlockPos(level.gx0, level.yMin - 1, level.gz0);
			if (!world.getBlockState(marker).is(Blocks.BEDROCK)) {
				level.build(world);
				spawnThings(world, level);
			} else {
				// Already built in an earlier session. Rebuild the columns so they
				// match the map's starting state, but keep the monsters as they were.
				level.build(world);
			}
		}
		return level;
	}

	private DoomLevel levelAt(Entity entity) {
		if (entity.level().dimension() != DIMENSION) {
			return null;
		}
		return levels.get(DoomGeometry.mapIndexAt(entity.getX()));
	}

	private void spawnThings(ServerLevel world, DoomLevel level) {
		DoomMap map = level.map;
		for (DoomMap.Thing thing : map.things) {
			if (!DoomThings.onMediumSkill(thing)) {
				continue;
			}
			DoomThings.Info info = DoomThings.get(thing.type());
			if (info == null) {
				continue;
			}
			DoomMap.Sector sector = map.sectors.get(map.sectorAt(thing.x(), thing.y()));
			double x = DoomGeometry.worldX(level.index, thing.x());
			double z = DoomGeometry.worldZ(thing.y());
			double y = DoomGeometry.worldY(sector.floor) + 0.01;
			float yaw = DoomGeometry.yaw(thing.angle());
			if (info.category() == DoomThings.Category.MONSTER) {
				DoomMonsterKind kind = DoomMonsterKind.byType(thing.type());
				if (kind == DoomMonsterKind.CACODEMON || kind == DoomMonsterKind.LOST_SOUL) {
					y += 0.5;
				}
				DoomMonster.spawn(world, kind, x, y, z, yaw, (thing.flags() & 8) != 0);
			} else if (info.category() == DoomThings.Category.PICKUP) {
				DoomPickup.spawn(world, thing.type(), x, y, z);
			}
		}
	}

	/** Takes a player into a Doom map, starting at the player 1 start. */
	public boolean enter(ServerPlayer player, String mapName) {
		int index = mapIndex(mapName);
		DoomLevel level = index < 0 ? null : level(index);
		if (level == null) {
			player.sendSystemMessage(Component.literal("There is no map called " + mapName + " in " + WadFile.shared().name(0)));
			return false;
		}
		if (player.level().dimension() != DIMENSION) {
			returns.put(player.getUUID(), new Return(player.level().dimension(), player.position(), player.getYRot(), player.getXRot()));
		}
		DoomMap.Thing start = null;
		for (DoomMap.Thing thing : level.map.things) {
			if (thing.type() == 1) {
				start = thing;
				break;
			}
		}
		float sx = start == null ? (level.map.minX + level.map.maxX) / 2 : start.x();
		float sy = start == null ? (level.map.minY + level.map.maxY) / 2 : start.y();
		DoomMap.Sector sector = level.map.sectors.get(level.map.sectorAt(sx, sy));
		double x = DoomGeometry.worldX(index, sx);
		double z = DoomGeometry.worldZ(sy);
		double y = DoomGeometry.worldY(sector.floor) + 0.05;
		player.teleportTo(dimension(), x, y, z, Set.of(), DoomGeometry.yaw(start == null ? 90 : start.angle()), 0, true);
		lastPositions.remove(player.getUUID());
		keys.remove(player.getUUID());
		enteredAt.put(player.getUUID(), server.getTickCount());
		sendFullState(player, level);
		ServerPlayNetworking.send(player, new DoomNet.HudPayload("", 0));

		// The marine always starts a level with at least a pistol and 50 bullets.
		Item pistol = DoomCraft.weaponItem("pistol");
		if (!player.getInventory().contains(new ItemStack(pistol))) {
			player.getInventory().add(new ItemStack(pistol));
		}
		int bullets = player.getInventory().countItem(DoomCraft.DOOM_BULLETS);
		if (bullets < 50) {
			player.getInventory().add(new ItemStack(DoomCraft.DOOM_BULLETS, 50 - bullets));
		}

		int monsters = dimension().getEntitiesOfClass(DoomMonster.class, level.bounds(), m -> m.isAlive() && !m.kind().isBarrel()).size();
		player.sendSystemMessage(Component.literal(level.map.name + ": " + monsters + " monsters are somewhere in this map, most of them behind doors."
			+ " Right-click a wall to open a door or press a switch. Left-click fires a Doom gun. /doom leave goes home."));
		return true;
	}

	/** Dying in Doom starts the map again from the beginning, as Doom does, instead of sending you home. */
	public void respawned(ServerPlayer before, ServerPlayer after) {
		// The old player has already been moved by now, so use the map we last saw them in.
		Integer index = currentMap.get(before.getUUID());
		if (before.level().dimension() != DIMENSION || index == null) {
			return;
		}
		String map = mapName(index);
		if (map == null) {
			return;
		}
		Return keep = returns.get(before.getUUID());
		enter(after, map);
		if (keep != null) {
			returns.put(after.getUUID(), keep);
		}
	}

	public void leave(ServerPlayer player) {
		Return back = returns.remove(player.getUUID());
		if (back != null && server.getLevel(back.dimension) != null) {
			player.teleportTo(server.getLevel(back.dimension), back.pos.x, back.pos.y, back.pos.z, Set.of(), back.yaw, back.pitch, true);
		} else {
			ServerLevel overworld = server.overworld();
			BlockPos spawn = overworld.getRespawnData().pos();
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.of(), 0, 0, true);
		}
		lastPositions.remove(player.getUUID());
		currentMap.remove(player.getUUID());
	}

	/** Puts a map back to how it started: doors shut, monsters and pickups back in place. */
	public void reset(String mapName) {
		int index = mapIndex(mapName);
		if (index < 0 || mapName(index) == null) {
			return;
		}
		ServerLevel world = dimension();
		levels.remove(index);
		DoomLevel fresh = new DoomLevel(index, new DoomMap(WadFile.shared(), mapName(index)));
		for (Entity e : world.getEntitiesOfClass(Entity.class, fresh.bounds().inflate(4), e -> !(e instanceof Player))) {
			e.discard();
		}
		levels.put(index, fresh);
		fresh.build(world);
		spawnThings(world, fresh);
		for (ServerPlayer p : world.players()) {
			if (DoomGeometry.mapIndexAt(p.getX()) == index) {
				sendFullState(p, fresh);
			}
		}
	}

	private static final int[][] PAR_TIMES = {
		{30, 75, 120, 90, 165, 180, 180, 30, 165},
		{90, 90, 90, 120, 90, 360, 240, 30, 170},
		{90, 45, 90, 150, 90, 90, 165, 30, 135},
	};
	private static final int[] DOOM2_PAR_TIMES = {30, 90, 120, 120, 90, 150, 120, 120, 270, 90, 210, 150, 150, 150, 210, 150, 420, 150,
		210, 150, 240, 150, 180, 150, 300, 330, 420, 300, 180, 120, 30, 30};

	private static int parTime(String map) {
		Matcher e = EPISODE_MAP.matcher(map);
		if (e.matches()) {
			int ep = Integer.parseInt(e.group(1));
			return ep <= 3 ? PAR_TIMES[ep - 1][Integer.parseInt(e.group(2)) - 1] : -1;
		}
		Matcher d = DOOM2_MAP.matcher(map);
		return d.matches() ? DOOM2_PAR_TIMES[Integer.parseInt(d.group(1)) - 1] : -1;
	}

	private static String clock(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	private static String percent(int got, int total) {
		return (total == 0 ? 100 : got * 100 / total) + "%";
	}

	/** Doom's intermission screen, as chat lines. */
	private void intermission(ServerPlayer p, DoomLevel level) {
		Integer start = enteredAt.get(p.getUUID());
		int seconds = start == null ? 0 : (server.getTickCount() - start) / 20;
		int par = parTime(level.map.name);
		p.sendSystemMessage(Component.literal(level.map.name + " FINISHED").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		p.sendSystemMessage(Component.literal("Kills " + percent(level.kills, level.totalKills)
			+ "   Items " + percent(level.items, level.totalItems)
			+ "   Secret " + percent(level.secretCount(), level.totalSecrets)).withStyle(ChatFormatting.RED));
		p.sendSystemMessage(Component.literal("Time " + clock(seconds) + (par > 0 ? "   Par " + clock(par) : "")).withStyle(ChatFormatting.RED));
	}

	/** Counted for the intermission when a monster dies. */
	public void recordKill(Entity monster) {
		DoomLevel level = levelAt(monster);
		if (level != null) {
			level.kills++;
		}
	}

	/** Counted for the intermission when a pickup is taken. */
	public void recordItem(Entity pickup) {
		DoomLevel level = levelAt(pickup);
		if (level != null) {
			level.items++;
		}
	}

	/**
	 * Monsters open ordinary doors (special 1) when they walk into them, as in
	 * P_TryMove. They cannot use keyed doors, switches or secret doors.
	 */
	public void monsterUse(DoomMonster monster) {
		DoomLevel level = levelAt(monster);
		if (level == null) {
			return;
		}
		double mx = DoomGeometry.doomX(level.index, monster.getX());
		double my = DoomGeometry.doomY(monster.getZ());
		for (int i = 0; i < level.map.lines.size(); i++) {
			DoomMap.Line line = level.map.lines.get(i);
			if ((line.special() != 1 && line.special() != 117) || (line.flags() & 32) != 0) {
				continue;
			}
			DoomMap.Vertex a = level.map.vertices.get(line.v1());
			DoomMap.Vertex b = level.map.vertices.get(line.v2());
			if (distanceToSegment(a, b, mx, my) < 52) {
				level.activate((ServerLevel) monster.level(), this, i, DoomLevel.Trigger.USE, monster);
				return;
			}
		}
	}

	public void exitLevel(ServerLevel world, DoomLevel level, boolean secret) {
		String next = nextMap(level.map.name, secret);
		List<ServerPlayer> inMap = new ArrayList<>();
		for (ServerPlayer p : world.players()) {
			if (DoomGeometry.mapIndexAt(p.getX()) == level.index) {
				inMap.add(p);
			}
		}
		for (ServerPlayer p : inMap) {
			intermission(p, level);
			if (next == null) {
				p.sendSystemMessage(Component.literal("That is the end of the episode. Back to Minecraft."));
				leave(p);
			} else {
				Return keep = returns.get(p.getUUID());
				enter(p, next);
				if (keep != null) {
					returns.put(p.getUUID(), keep);
				}
			}
		}
	}

	/** Moves an entity to the teleport destination in the sector with this tag. */
	public boolean teleport(ServerLevel world, DoomLevel level, int tag, Entity who) {
		DoomMap map = level.map;
		for (DoomMap.Thing thing : map.things) {
			if (thing.type() != 14) {
				continue;
			}
			int sector = map.sectorAt(thing.x(), thing.y());
			if (map.sectors.get(sector).tag != tag) {
				continue;
			}
			soundAt(world, who.position(), "DSTELEPT");
			double x = DoomGeometry.worldX(level.index, thing.x());
			double z = DoomGeometry.worldZ(thing.y());
			double y = DoomGeometry.worldY(map.sectors.get(sector).floor) + 0.05;
			who.teleportTo(world, x, y, z, Set.of(), DoomGeometry.yaw(thing.angle()), who.getXRot(), true);
			who.setDeltaMovement(Vec3.ZERO);
			lastPositions.put(who.getUUID(), new double[] {level.index, thing.x(), thing.y()});
			soundAt(world, new Vec3(x, y, z), "DSTELEPT");
			return true;
		}
		return false;
	}

	/** Lowers the floors tagged 666, as Doom does when the last Baron of Hell on E1M8 dies. */
	public void bossDeath(ServerLevel world, Entity boss) {
		DoomLevel level = levelAt(boss);
		if (level == null || !level.map.name.equals("E1M8")) {
			return;
		}
		boolean others = !world.getEntitiesOfClass(DoomMonster.class, level.bounds(),
			m -> m != boss && m.isAlive() && m.kind() == DoomMonsterKind.BARON).isEmpty();
		if (!others) {
			level.lowerTagged(world, this, 666);
		}
	}

	// ------------------------------------------------------------ player actions

	/** Doom's use key: finds the line with a use special nearest to where the player clicked. */
	public void playerUse(Player player, Vec3 hit) {
		DoomLevel level = levelAt(player);
		if (level == null) {
			return;
		}
		double hx = DoomGeometry.doomX(level.index, hit.x);
		double hy = DoomGeometry.doomY(hit.z);
		double px = DoomGeometry.doomX(level.index, player.getX());
		double py = DoomGeometry.doomY(player.getZ());
		int best = -1;
		double bestDist = 40;
		for (int i = 0; i < level.map.lines.size(); i++) {
			DoomMap.Line line = level.map.lines.get(i);
			if (!DoomLevel.Special.isUse(line.special())) {
				continue;
			}
			DoomMap.Vertex a = level.map.vertices.get(line.v1());
			DoomMap.Vertex b = level.map.vertices.get(line.v2());
			if (side(a, b, px, py) != 0) {
				continue;
			}
			double d = distanceToSegment(a, b, hx, hy);
			if (d < bestDist) {
				bestDist = d;
				best = i;
			}
		}
		if (best >= 0) {
			level.activate((ServerLevel) player.level(), this, best, DoomLevel.Trigger.USE, player);
		} else {
			soundAt((ServerLevel) player.level(), player.position(), "DSNOWAY");
		}
	}

	/** Shooting a line with a gun special, as Doom's hitscan weapons do. */
	public void shootLine(ServerLevel world, Entity shooter, Vec3 from, Vec3 to) {
		DoomLevel level = levelAt(shooter);
		if (level == null) {
			return;
		}
		double ax = DoomGeometry.doomX(level.index, from.x), ay = DoomGeometry.doomY(from.z);
		double bx = DoomGeometry.doomX(level.index, to.x), by = DoomGeometry.doomY(to.z);
		for (int i = 0; i < level.map.lines.size(); i++) {
			DoomMap.Line line = level.map.lines.get(i);
			DoomLevel.Special sp = DoomLevel.Special.of(line.special());
			if (sp == null || sp.trigger != DoomLevel.Trigger.SHOOT) {
				continue;
			}
			DoomMap.Vertex a = level.map.vertices.get(line.v1());
			DoomMap.Vertex b = level.map.vertices.get(line.v2());
			if (crosses(ax, ay, bx, by, a, b)) {
				level.activate(world, this, i, DoomLevel.Trigger.SHOOT, shooter);
			}
		}
	}

	/** Front is 0, back is 1, as P_PointOnLineSide. */
	static int side(DoomMap.Vertex a, DoomMap.Vertex b, double x, double y) {
		double cross = (b.x() - a.x()) * (y - a.y()) - (b.y() - a.y()) * (x - a.x());
		return cross <= 0 ? 0 : 1;
	}

	private static double distanceToSegment(DoomMap.Vertex a, DoomMap.Vertex b, double x, double y) {
		double dx = b.x() - a.x();
		double dy = b.y() - a.y();
		double len2 = dx * dx + dy * dy;
		double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * dx + (y - a.y()) * dy) / len2));
		return Math.hypot(a.x() + t * dx - x, a.y() + t * dy - y);
	}

	private static boolean crosses(double ax, double ay, double bx, double by, DoomMap.Vertex c, DoomMap.Vertex d) {
		double d1 = (d.x() - c.x()) * (ay - c.y()) - (d.y() - c.y()) * (ax - c.x());
		double d2 = (d.x() - c.x()) * (by - c.y()) - (d.y() - c.y()) * (bx - c.x());
		double d3 = (bx - ax) * (c.y() - ay) - (by - ay) * (c.x() - ax);
		double d4 = (bx - ax) * (d.y() - ay) - (by - ay) * (d.x() - ax);
		return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
	}

	// ------------------------------------------------------------ keys

	public boolean hasKey(Entity who, String colour) {
		if (!(who instanceof Player)) {
			return false;
		}
		Set<String> owned = keys.get(who.getUUID());
		return owned != null && owned.contains(colour);
	}

	public void giveKey(Player player, String colour) {
		keys.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(colour);
	}

	private int keyMask(Entity who) {
		Set<String> owned = keys.getOrDefault(who.getUUID(), Set.of());
		return (owned.contains("blue") ? 1 : 0) | (owned.contains("yellow") ? 2 : 0) | (owned.contains("red") ? 4 : 0);
	}

	public Set<String> keysOf(Player player) {
		return keys.getOrDefault(player.getUUID(), Set.of());
	}

	public void message(Entity who, String text) {
		if (who instanceof ServerPlayer p && p.connection != null) {
			ServerPlayNetworking.send(p, new DoomNet.HudPayload(text, keyMask(p)));
		}
	}

	// ------------------------------------------------------------ tick

	public void tick() {
		ServerLevel world = testLevel != null ? testLevel : server.getLevel(DIMENSION);
		if (world == null) {
			return;
		}
		tickCount++;
		for (DoomLevel level : levels.values()) {
			level.tick(world, this);
		}
		for (ServerPlayer player : world.players()) {
			DoomLevel level = levels.get(DoomGeometry.mapIndexAt(player.getX()));
			if (level == null) {
				// A player who logged out in the Doom dimension and came back.
				level = level(DoomGeometry.mapIndexAt(player.getX()));
				if (level == null) {
					continue;
				}
				sendFullState(player, level);
			}
			currentMap.put(player.getUUID(), level.index);
			walkTriggers(world, level, player);
			floorSpecials(world, level, player);
		}
		for (DoomLevel level : levels.values()) {
			sync(world, level);
		}
	}

	private void walkTriggers(ServerLevel world, DoomLevel level, ServerPlayer player) {
		double x = DoomGeometry.doomX(level.index, player.getX());
		double y = DoomGeometry.doomY(player.getZ());
		double[] last = lastPositions.put(player.getUUID(), new double[] {level.index, x, y});
		if (last == null || (int) last[0] != level.index || (last[1] == x && last[2] == y)) {
			return;
		}
		for (int i = 0; i < level.map.lines.size(); i++) {
			DoomMap.Line line = level.map.lines.get(i);
			if (!DoomLevel.Special.isWalk(line.special())) {
				continue;
			}
			DoomMap.Vertex a = level.map.vertices.get(line.v1());
			DoomMap.Vertex b = level.map.vertices.get(line.v2());
			if (!crosses(last[1], last[2], x, y, a, b)) {
				continue;
			}
			DoomLevel.Special sp = DoomLevel.Special.of(line.special());
			// Teleporters only work when entered from the front.
			if (sp.action == DoomLevel.Action.TELEPORT && side(a, b, last[1], last[2]) != 0) {
				continue;
			}
			level.activate(world, this, i, DoomLevel.Trigger.WALK, player);
			if (player.level() != world || DoomGeometry.mapIndexAt(player.getX()) != level.index) {
				return;
			}
		}
	}

	/** Nukage, lava and secret sectors, checked every 32 Doom tics as in P_PlayerInSpecialSector. */
	private void floorSpecials(ServerLevel world, DoomLevel level, ServerPlayer player) {
		double x = DoomGeometry.doomX(level.index, player.getX());
		double y = DoomGeometry.doomY(player.getZ());
		int s = level.map.sectorAt((float) x, (float) y);
		DoomMap.Sector sector = level.map.sectors.get(s);
		if (sector.special == 9 && !level.secretFound(s)) {
			message(player, "A secret is revealed!");
		}
		boolean onFloor = player.getY() <= DoomGeometry.worldY(sector.floor) + 0.1;
		if (!onFloor || tickCount % 18 != 0 || player.isCreative() || player.isSpectator()) {
			return;
		}
		int damage = switch (sector.special) {
			case 5 -> 10;
			case 7 -> 5;
			case 4, 11, 16 -> 20;
			default -> 0;
		};
		if (damage == 0) {
			return;
		}
		boolean suit = player.hasEffect(MobEffects.FIRE_RESISTANCE);
		if (suit && sector.special != 16 && sector.special != 4) {
			return;
		}
		player.hurtServer(world, world.damageSources().generic(), damage / 5f);
		if (sector.special == 11 && player.getHealth() <= 2) {
			exitLevel(world, level, false);
		}
	}

	private void sync(ServerLevel world, DoomLevel level) {
		Set<Integer> sectors = level.takeDirtySectors();
		Set<Integer> lines = level.takeDirtyLines();
		if (sectors.isEmpty() && lines.isEmpty()) {
			return;
		}
		DoomNet.StatePayload payload = statePayload(level, sectors, lines);
		for (ServerPlayer p : world.players()) {
			if (DoomGeometry.mapIndexAt(p.getX()) == level.index) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	private void sendFullState(ServerPlayer player, DoomLevel level) {
		Set<Integer> sectors = new HashSet<>();
		for (int s = 0; s < level.map.sectors.size(); s++) {
			DoomMap.Sector sector = level.map.sectors.get(s);
			if (sector.floor != sector.startFloor || sector.ceiling != sector.startCeiling || sector.light != sector.startLight) {
				sectors.add(s);
			}
		}
		// Clients start from the WAD's heights. Send everything that has moved, and every switch that is on.
		ServerPlayNetworking.send(player, statePayload(level, sectors, new HashSet<>(level.switchesOn())));
	}

	private static DoomNet.StatePayload statePayload(DoomLevel level, Set<Integer> sectors, Set<Integer> lines) {
		int[] ids = sectors.stream().mapToInt(Integer::intValue).toArray();
		float[] floors = new float[ids.length];
		float[] ceilings = new float[ids.length];
		int[] lights = new int[ids.length];
		for (int i = 0; i < ids.length; i++) {
			floors[i] = level.map.sectors.get(ids[i]).floor;
			ceilings[i] = level.map.sectors.get(ids[i]).ceiling;
			lights[i] = level.map.sectors.get(ids[i]).light;
		}
		int[] lineIds = lines.stream().mapToInt(Integer::intValue).toArray();
		boolean[] on = new boolean[lineIds.length];
		for (int i = 0; i < lineIds.length; i++) {
			on[i] = level.switchOn(lineIds[i]);
		}
		return new DoomNet.StatePayload(level.index, ids, floors, ceilings, lights, lineIds, on);
	}

	// ------------------------------------------------------------ sound

	public void soundAt(ServerLevel world, Vec3 pos, String sound) {
		DoomNet.SoundPayload payload = new DoomNet.SoundPayload(sound, pos.x, pos.y, pos.z);
		for (ServerPlayer p : world.players()) {
			if (p.position().distanceToSqr(pos) < 64 * 64) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	public void soundFrom(Entity entity, String sound) {
		if (entity.level() instanceof ServerLevel world && !sound.isEmpty()) {
			soundAt(world, entity.position().add(0, entity.getBbHeight() / 2, 0), sound);
		}
	}

	public static boolean inDoom(LivingEntity entity) {
		return entity.level().dimension() == DIMENSION;
	}
}
