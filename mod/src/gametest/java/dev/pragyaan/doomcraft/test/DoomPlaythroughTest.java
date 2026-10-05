package dev.pragyaan.doomcraft.test;

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.entity.DoomPickup;
import dev.pragyaan.doomcraft.world.DoomGeometry;
import dev.pragyaan.doomcraft.world.DoomMap;
import dev.pragyaan.doomcraft.world.DoomWorld;
import dev.pragyaan.doomcraft.world.WadFile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Plays through E1M1 with real inputs where it matters: opening a door with a
 * right-click, firing with a left-click, taking pickups, pressing the exit
 * switch, dying and coming back, and leaving. Fails on anything that doesn't
 * behave. Screenshots land in build/run/clientGameTest/screenshots.
 */
public class DoomPlaythroughTest implements FabricClientGameTest {
	private ClientGameTestContext context;
	private TestSingleplayerContext world;

	@Override
	public void runTest(ClientGameTestContext context) {
		this.context = context;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			cmd("gamemode survival @a");
			cmd("difficulty normal");
			cmd("time set 13000");

			// 1. Enter through the arcade cabinet: sneak and right-click it.
			BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
			cmd(String.format("setblock %d %d %d doomcraft:doom_arcade[facing=north]", feet.getX(), feet.getY(), feet.getZ() + 2));
			context.getInput().lookAt(feet.south(2));
			context.waitTicks(5);
			context.getInput().holdKey(options -> options.keyShift);
			context.takeScreenshot("play-00-arcade");
			log("crosshair: " + context.computeOnClient(mc -> mc.hitResult == null ? "none" : mc.hitResult.getType() + " " + mc.hitResult.getLocation()));
			log("server sees sneaking: " + world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0).isShiftKeyDown()));
			log("block: " + world.getServer().computeOnServer(server -> server.overworld().getBlockState(feet.south(2)).toString()));
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(5);
			context.getInput().releaseKey(options -> options.keyShift);
			context.waitTicks(40);
			world.getConnection().waitForChunksRender();
			check(inDoom(), "sneak-using the arcade should take the player into Doom");
			check(mapIndex() == 0, "the arcade should lead to E1M1");
			int bullets = count("doom_bullets");
			check(bullets >= 50, "the player should start with 50 bullets, has " + bullets);
			context.takeScreenshot("play-01-entered-e1m1");

			// 2. Hunger stays put in Doom, even with the Hunger effect running.
			cmd("effect give @a minecraft:hunger 10 100 true");
			int food = context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
			context.waitTicks(120);
			int foodAfter = context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
			log("food " + food + " -> " + foodAfter);
			check(foodAfter == food, "food should not drain in Doom");
			cmd("effect clear @a");

			// 3. Walk up to the first door and open it with a right-click.
			DoomMap map = new DoomMap(WadFile.shared(), "E1M1");
			DoomMap.Thing start = map.things.stream().filter(t -> t.type() == 1).findFirst().orElseThrow();
			int door = nearestLine(map, 1, start.x(), start.y());
			standInFrontOf(map, door, start.x(), start.y(), 2.0);
			context.takeScreenshot("play-02-facing-door");
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(40);
			context.takeScreenshot("play-03-door-open");
			Vec3 before = playerPos();
			context.getInput().holdKeyFor(InputConstants.KEY_W, 30);
			Vec3 after = playerPos();
			log("walked through door: " + before + " -> " + after);
			check(before.distanceTo(after) > 2.5, "the player should be able to walk through the opened door");
			context.takeScreenshot("play-04-through-door");

			// 4. A shotgun from the creative menu, used on a monster.
			cmd("give @a doomcraft:doom_shotgun");
			cmd("give @a doomcraft:doom_shells 20");
			cmd("execute as @a at @s run doom spawn zombieman");
			context.waitTicks(5);
			int shotgunSlot = context.computeOnClient(mc -> {
				for (int i = 0; i < 9; i++) {
					if (mc.player.getInventory().getItem(i).is(DoomCraft.weaponItem("shotgun"))) {
						return i;
					}
				}
				return -1;
			});
			check(shotgunSlot >= 0, "the shotgun should be in the hotbar");
			context.getInput().pressKey(InputConstants.KEY_1 + shotgunSlot);
			context.waitTicks(3);
			aimAtNearestMonster();
			int shells = count("doom_shells");
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			context.takeScreenshot("play-05-shotgun");
			context.waitTicks(25);
			check(count("doom_shells") == shells - 1, "firing should use one shell");

			// 4b. Back in the open start room: an imp throws fireballs, a barrel blows up when shot.
			cmd("execute as @a run doom E1M1");
			context.waitTicks(20);
			cmd("execute as @a at @s run kill @e[type=doomcraft:doom_monster,distance=..48]");
			world.getServer().runOnServer(server -> {
				var p = server.getPlayerList().getPlayers().get(0);
				Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
				dev.pragyaan.doomcraft.entity.DoomMonster.spawn((net.minecraft.server.level.ServerLevel) p.level(),
					dev.pragyaan.doomcraft.world.DoomMonsterKind.IMP, p.getX() + look.x * 5, p.getY(), p.getZ() + look.z * 5, p.getYRot() + 180, false);
			});
			boolean sawBall = false;
			for (int i = 0; i < 80 && !sawBall; i++) {
				context.waitTick();
				sawBall = world.getServer().computeOnServer(server -> !server.getLevel(DoomWorld.DIMENSION)
					.getEntitiesOfClass(dev.pragyaan.doomcraft.entity.DoomBall.class, new AABB(-50, 0, -50, 300, 200, 300)).isEmpty());
			}
			context.waitTicks(3);
			context.takeScreenshot("play-05b-imp-fireball");
			log("imp threw a fireball: " + sawBall);
			check(sawBall, "an imp in sight should throw a fireball within 4 seconds");
			context.waitTicks(15);
			context.takeScreenshot("play-05c-after-fireball");
			cmd("execute as @a at @s run kill @e[type=doomcraft:doom_monster,distance=..48]");
			world.getServer().runOnServer(server -> {
				var p = server.getPlayerList().getPlayers().get(0);
				Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
				dev.pragyaan.doomcraft.entity.DoomMonster.spawn((net.minecraft.server.level.ServerLevel) p.level(),
					dev.pragyaan.doomcraft.world.DoomMonsterKind.BARREL, p.getX() + look.x * 3.5, p.getY(), p.getZ() + look.z * 3.5, 0, false);
			});
			context.waitTicks(30);
			aimAtNearestMonster();
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(4);
			context.takeScreenshot("play-05d-barrel");

			// 5. No falling damage in Doom. Clear the monsters first so only the fall can hurt.
			cmd("execute as @a at @s run kill @e[type=doomcraft:doom_monster,distance=..48]");
			cmd("execute as @a at @s run kill @e[type=doomcraft:doom_ball]");
			context.waitTicks(5);
			float health = context.computeOnClient(mc -> mc.player.getHealth());
			cmd("execute as @a at @s run tp @s ~ ~4 ~");
			context.waitTicks(30);
			float healthAfterFall = context.computeOnClient(mc -> mc.player.getHealth());
			log("health before and after a 4 block fall: " + health + " " + healthAfterFall);
			check(healthAfterFall >= health - 0.01f, "falling should not hurt in Doom");

			// 6. Take a pickup by walking onto it.
			Vec3 pickup = world.getServer().computeOnServer(server -> server.getLevel(DoomWorld.DIMENSION)
				.getEntitiesOfClass(DoomPickup.class, new AABB(-50, 0, -50, 300, 200, 300), p -> p.doomType() == 2007 || p.doomType() == 2008)
				.stream().map(p -> p.position()).findFirst().orElse(null));
			if (pickup != null) {
				int bulletsBefore = count("doom_bullets") + count("doom_shells");
				cmd(String.format(Locale.ROOT, "execute in doomcraft:doom run tp @a %.2f %.2f %.2f", pickup.x, pickup.y + 0.1, pickup.z));
				context.waitTicks(10);
				context.takeScreenshot("play-06-pickup");
				check(count("doom_bullets") + count("doom_shells") > bulletsBefore, "walking onto ammo should pick it up");
			}

			// 7. Press the exit switch: E1M2 should load and the tally should print.
			int exit = nearestLine(map, 11, start.x(), start.y());
			standInFrontOf(map, exit, Float.NaN, Float.NaN, 1.4);
			context.takeScreenshot("play-07-exit-switch");
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(40);
			world.getConnection().waitForChunksRender();
			check(mapIndex() == 1, "the exit switch should lead to E1M2, map index is " + mapIndex());
			context.takeScreenshot("play-08-e1m2");

			// 8. Dying in Doom starts the map again.
			cmd("kill @a");
			context.waitTicks(20);
			context.takeScreenshot("play-09-dead");
			// The death screen's buttons only become active after a moment.
			context.waitTicks(40);
			context.clickScreenButton("deathScreen.respawn");
			context.waitTicks(40);
			world.getConnection().waitForChunksRender();
			log("after respawn: " + context.computeOnClient(mc -> mc.level.dimension().identifier() + " " + mc.player.position() + " screen " + mc.gui.screen()));
			check(inDoom() && mapIndex() == 1, "respawning should put the player back at the start of E1M2");
			context.takeScreenshot("play-10-respawned");

			// 9. And /doom leave goes home.
			cmd("execute as @a run doom leave");
			context.waitTicks(30);
			check(!inDoom(), "/doom leave should return to the overworld");
		}
	}

	private void cmd(String command) {
		world.getServer().runCommand(command);
	}

	private void log(String text) {
		System.out.println("[doomcraft-play] " + text);
	}

	private void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}

	private boolean inDoom() {
		return context.computeOnClient(mc -> mc.level.dimension() == DoomWorld.DIMENSION);
	}

	private int mapIndex() {
		return context.computeOnClient(mc -> DoomGeometry.mapIndexAt(mc.player.getX()));
	}

	private Vec3 playerPos() {
		return context.computeOnClient(mc -> mc.player.position());
	}

	private int count(String item) {
		return context.computeOnClient(mc -> mc.player.getInventory().countItem(
			net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(DoomCraft.id(item))));
	}

	private static int nearestLine(DoomMap map, int special, float x, float y) {
		int best = -1;
		double bestDist = Double.MAX_VALUE;
		for (int i = 0; i < map.lines.size(); i++) {
			DoomMap.Line l = map.lines.get(i);
			if (l.special() != special) {
				continue;
			}
			DoomMap.Vertex a = map.vertices.get(l.v1());
			double d = Math.hypot(a.x() - x, a.y() - y);
			if (d < bestDist) {
				bestDist = d;
				best = i;
			}
		}
		return best;
	}

	/** Teleports to just in front of a line's front side (towards a point, if given) and faces its middle. */
	private void standInFrontOf(DoomMap map, int lineIndex, float towardX, float towardY, double blocks) {
		DoomMap.Line line = map.lines.get(lineIndex);
		DoomMap.Vertex a = map.vertices.get(line.v1());
		DoomMap.Vertex b = map.vertices.get(line.v2());
		double mx = (a.x() + b.x()) / 2, my = (a.y() + b.y()) / 2;
		double len = Math.hypot(b.x() - a.x(), b.y() - a.y());
		// The front side is to the right of v1 -> v2.
		double nx = (b.y() - a.y()) / len, ny = -(b.x() - a.x()) / len;
		double px = mx + nx * blocks * 32, py = my + ny * blocks * 32;
		float floor = map.sectors.get(map.front(line).sector()).floor;
		double wx = DoomGeometry.worldX(0, px), wz = DoomGeometry.worldZ(py), wy = DoomGeometry.worldY(floor) + 0.05;
		double tx = DoomGeometry.worldX(0, mx), tz = DoomGeometry.worldZ(my), ty = wy + 1.2;
		cmd(String.format(Locale.ROOT, "execute in doomcraft:doom run tp @a %.3f %.3f %.3f facing %.3f %.3f %.3f", wx, wy, wz, tx, ty, tz));
		context.waitTicks(15);
	}

	private void aimAtNearestMonster() {
		Vec3 target = world.getServer().computeOnServer(server -> {
			var player = server.getPlayerList().getPlayers().get(0);
			return server.getLevel(DoomWorld.DIMENSION)
				.getEntitiesOfClass(dev.pragyaan.doomcraft.entity.DoomMonster.class, player.getBoundingBox().inflate(12), m -> m.isAlive())
				.stream().min((p, q) -> Double.compare(p.distanceTo(player), q.distanceTo(player)))
				.map(m -> m.position().add(0, 1, 0)).orElse(null);
		});
		if (target != null) {
			cmd(String.format(Locale.ROOT, "execute as @a at @s run tp @s ~ ~ ~ facing %.3f %.3f %.3f", target.x, target.y, target.z));
			context.waitTicks(3);
		}
	}
}
