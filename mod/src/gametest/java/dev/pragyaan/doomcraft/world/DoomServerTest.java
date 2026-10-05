package dev.pragyaan.doomcraft.world;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.entity.DoomMonster;
import dev.pragyaan.doomcraft.entity.DoomPickup;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/** Headless checks of the Doom dimension: building E1M1, its things, and a door opening. */
public class DoomServerTest {
	@GameTest(maxTicks = 600)
	public void e1m1BuildsWithThingsAndDoors(GameTestHelper helper) {
		DoomWorld world = DoomWorld.get(helper.getLevel().getServer());
		world.useLevelForTests(helper.getLevel());
		ServerLevel doom = world.dimension();
		long start = System.currentTimeMillis();
		// Reset rather than load, so leftovers from an earlier test run don't count.
		world.reset("E1M1");
		DoomLevel level = world.level(DoomWorld.mapIndex("E1M1"));
		System.out.println("[doomcraft-test] E1M1 built in " + (System.currentTimeMillis() - start) + " ms");

		int monsters = doom.getEntitiesOfClass(DoomMonster.class, level.bounds()).size();
		int pickups = doom.getEntitiesOfClass(DoomPickup.class, level.bounds()).size();
		System.out.println("[doomcraft-test] monsters " + monsters + ", pickups " + pickups);
		if (monsters < 5 || pickups < 5) {
			helper.fail("Expected E1M1's monsters and pickups, got " + monsters + " and " + pickups);
			return;
		}

		// The player start stands on a solid floor with air above it.
		DoomMap.Thing startThing = level.map.things.stream().filter(t -> t.type() == 1).findFirst().orElseThrow();
		BlockPos feet = BlockPos.containing(DoomGeometry.worldX(level.index, startThing.x()), DoomGeometry.worldY(0) + 0.1,
			DoomGeometry.worldZ(startThing.y()));
		if (!doom.getBlockState(feet.below()).is(DoomCraft.DOOM_SOLID) || !doom.getBlockState(feet).isAir() || !doom.getBlockState(feet.above()).isAir()) {
			helper.fail("The start is not standable: below " + doom.getBlockState(feet.below()) + ", feet " + doom.getBlockState(feet));
			return;
		}

		// Open the nearest ordinary door (special 1) to the start, as a player using it would.
		int door = -1;
		double best = Double.MAX_VALUE;
		for (int i = 0; i < level.map.lines.size(); i++) {
			DoomMap.Line l = level.map.lines.get(i);
			if (l.special() == 1 && l.twoSided()) {
				DoomMap.Vertex v = level.map.vertices.get(l.v1());
				double d = Math.hypot(v.x() - startThing.x(), v.y() - startThing.y());
				if (d < best) {
					best = d;
					door = i;
				}
			}
		}
		int doorSector = level.map.back(level.map.lines.get(door)).sector();
		float closed = level.map.sectors.get(doorSector).ceiling;
		Player player = helper.makeMockServerPlayer(GameType.SURVIVAL);
		boolean started = level.activate(doom, world, door, DoomLevel.Trigger.USE, player);
		System.out.println("[doomcraft-test] door line " + door + " sector " + doorSector + " activated " + started);
		int[] column = level.sectorColumns(doorSector).get(0);
		BlockPos doorFloor = BlockPos.containing(column[0], DoomGeometry.worldY(level.map.sectors.get(doorSector).floor) + 0.5, column[1]);
		boolean shutBefore = !doom.getBlockState(doorFloor).isAir();

		// The server ticks movers itself. A door opens in about 20 ticks and stays open for about 85.
		helper.runAfterDelay(50, () -> {
			float open = level.map.sectors.get(doorSector).ceiling;
			boolean airAfter = doom.getBlockState(doorFloor).isAir() && doom.getBlockState(doorFloor.above()).isAir();
			System.out.println("[doomcraft-test] door ceiling " + closed + " -> " + open + ", blocked before " + shutBefore + ", clear after " + airAfter);
			if (open <= closed + 56 || !shutBefore || !airAfter) {
				helper.fail("The door did not open: ceiling " + closed + " -> " + open);
				return;
			}
			// Then it closes again by itself.
			helper.runAfterDelay(120, () -> {
				float after = level.map.sectors.get(doorSector).ceiling;
				System.out.println("[doomcraft-test] door ceiling after waiting " + after);
				if (after != closed || doom.getBlockState(doorFloor).isAir()) {
					helper.fail("The door did not close again: ceiling " + after);
					return;
				}
				helper.succeed();
			});
		});
	}

	private static int firstLine(DoomLevel level, int special) {
		for (int i = 0; i < level.map.lines.size(); i++) {
			if (level.map.lines.get(i).special() == special) {
				return i;
			}
		}
		throw new AssertionError("No line with special " + special + " in " + level.map.name);
	}

	private static int taggedSector(DoomLevel level, int line) {
		int tag = level.map.lines.get(line).tag();
		for (int s = 0; s < level.map.sectors.size(); s++) {
			if (level.map.sectors.get(s).tag == tag) {
				return s;
			}
		}
		throw new AssertionError("No sector tagged " + tag);
	}

	@GameTest(maxTicks = 400)
	public void e1m2LiftKeyDoorAndDonut(GameTestHelper helper) {
		DoomWorld world = DoomWorld.get(helper.getLevel().getServer());
		world.useLevelForTests(helper.getLevel());
		world.reset("E1M2");
		DoomLevel level = world.level(DoomWorld.mapIndex("E1M2"));
		ServerLevel doom = world.dimension();
		Player player = helper.makeMockServerPlayer(GameType.SURVIVAL);

		// A repeatable lift (SR, special 62): goes down, waits, comes back up.
		int liftLine = firstLine(level, 62);
		int lift = taggedSector(level, liftLine);
		float liftStart = level.map.sectors.get(lift).floor;
		level.activate(doom, world, liftLine, DoomLevel.Trigger.USE, player);

		// A red key door (DR, special 28) stays shut without the key, then opens with it.
		int keyLine = firstLine(level, 28);
		int keyDoor = level.map.back(level.map.lines.get(keyLine)).sector();
		boolean openedWithoutKey = level.activate(doom, world, keyLine, DoomLevel.Trigger.USE, player);
		world.giveKey(player, "red");
		boolean openedWithKey = level.activate(doom, world, keyLine, DoomLevel.Trigger.USE, player);

		// The donut (special 9): the pillar sinks.
		int donutLine = firstLine(level, 9);
		int pillar = taggedSector(level, donutLine);
		float pillarStart = level.map.sectors.get(pillar).floor;
		boolean donut = level.activate(doom, world, donutLine, DoomLevel.Trigger.USE, player);

		helper.runAfterDelay(25, () -> {
			float liftDown = level.map.sectors.get(lift).floor;
			float keyCeiling = level.map.sectors.get(keyDoor).ceiling;
			System.out.println("[doomcraft-test] lift " + liftStart + " -> " + liftDown + ", key door without key " + openedWithoutKey
				+ " with key " + openedWithKey + " ceiling " + keyCeiling + ", donut " + donut);
			if (liftDown >= liftStart) {
				helper.fail("The lift did not go down");
				return;
			}
			if (openedWithoutKey || !openedWithKey || keyCeiling <= level.map.sectors.get(keyDoor).floor) {
				helper.fail("The red key door should open only with the red key");
				return;
			}
			helper.runAfterDelay(200, () -> {
				float liftBack = level.map.sectors.get(lift).floor;
				float pillarNow = level.map.sectors.get(pillar).floor;
				System.out.println("[doomcraft-test] lift back at " + liftBack + ", donut pillar " + pillarStart + " -> " + pillarNow);
				if (liftBack != liftStart) {
					helper.fail("The lift did not come back up");
					return;
				}
				if (!donut || pillarNow == pillarStart) {
					helper.fail("The donut pillar did not move");
					return;
				}
				helper.succeed();
			});
		});
	}

	@GameTest(maxTicks = 100)
	public void e1m3LightsGoOut(GameTestHelper helper) {
		DoomWorld world = DoomWorld.get(helper.getLevel().getServer());
		world.useLevelForTests(helper.getLevel());
		world.reset("E1M3");
		DoomLevel level = world.level(DoomWorld.mapIndex("E1M3"));
		int line = firstLine(level, 35);
		int sector = taggedSector(level, line);
		int before = level.map.sectors.get(sector).light;
		level.activate(world.dimension(), world, line, DoomLevel.Trigger.WALK, helper.makeMockServerPlayer(GameType.SURVIVAL));
		int after = level.map.sectors.get(sector).light;
		System.out.println("[doomcraft-test] lights " + before + " -> " + after);
		if (after != 35) {
			helper.fail("Special 35 should drop the light to 35, got " + after);
			return;
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void barrelsExplode(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
		DoomMonster barrel = DoomMonster.spawn(level, DoomMonsterKind.BARREL, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, false);
		DoomMonster zombie = DoomMonster.spawn(level, DoomMonsterKind.ZOMBIEMAN, base.getX() + 2.5, base.getY(), base.getZ() + 0.5, 0, false);
		float before = zombie.getHealth();
		barrel.hurtServer(level, level.damageSources().generic(), 100);
		helper.runAfterDelay(2, () -> {
			System.out.println("[doomcraft-test] zombieman next to a barrel: " + before + " -> " + zombie.getHealth() + " alive " + zombie.isAlive());
			if (zombie.isAlive() && zombie.getHealth() >= before) {
				helper.fail("The barrel explosion should hurt the zombieman");
				return;
			}
			helper.succeed();
		});
	}
}
