package dev.pragyaan.doomcraft.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pragyaan.doomcraft.entity.DoomMonster;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;

/**
 * Walks into Doom's E1M1 as a Minecraft player and photographs it. Screenshots
 * land in build/run/clientGameTest/screenshots.
 */
public class DoomWorldClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("difficulty normal");
			world.getServer().runCommand("time set 6000");
			world.getServer().runCommand("execute as @a run doom E1M1");
			context.waitTicks(40);
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);

			boolean inDoom = context.computeOnClient(mc -> mc.level.dimension() == DoomWorld.DIMENSION);
			if (!inDoom) {
				throw new AssertionError("/doom did not take the player into the Doom dimension");
			}
			int monsters = world.getServer().computeOnServer(server -> server.getLevel(DoomWorld.DIMENSION)
				.getEntitiesOfClass(DoomMonster.class, new AABB(-200, 0, -200, 200, 200, 200)).size());
			System.out.println("[doomcraft-test] monsters in E1M1: " + monsters);
			context.takeScreenshot("world-1-e1m1-start");

			context.getInput().holdKeyFor(InputConstants.KEY_W, 30);
			context.waitTicks(5);
			context.takeScreenshot("world-2-walked-forward");

			// Turn round to see the room behind the start.
			context.getInput().moveCursor(600, 0);
			context.waitTicks(5);
			context.takeScreenshot("world-3-turned");

			context.getInput().pressKey(InputConstants.KEY_1);
			context.waitTicks(5);
			context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 2);
			context.waitTicks(2);
			context.takeScreenshot("world-4-pistol-fired");

			double[] pos = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
			System.out.println("[doomcraft-test] player at " + pos[0] + " " + pos[1] + " " + pos[2]);

			// Bring a zombieman, an imp and a demon into the open in front of the start, and photograph them.
			world.getServer().runCommand("execute as @a run doom E1M1");
			world.getServer().runCommand("effect give @a minecraft:resistance 600 4 true");
			context.waitTicks(20);
			world.getServer().runOnServer(server -> {
				net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				net.minecraft.world.phys.Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
				net.minecraft.world.phys.Vec3 side = new net.minecraft.world.phys.Vec3(-look.z, 0, look.x);
				dev.pragyaan.doomcraft.world.DoomMonsterKind[] kinds = {dev.pragyaan.doomcraft.world.DoomMonsterKind.ZOMBIEMAN,
					dev.pragyaan.doomcraft.world.DoomMonsterKind.IMP, dev.pragyaan.doomcraft.world.DoomMonsterKind.DEMON};
				for (int i = 0; i < 3; i++) {
					net.minecraft.world.phys.Vec3 at = p.position().add(look.scale(5)).add(side.scale((i - 1) * 2.2));
					DoomMonster.spawn((net.minecraft.server.level.ServerLevel) p.level(), kinds[i], at.x, p.getY(), at.z, p.getYRot() + 180, false);
				}
			});
			context.waitTicks(3);
			context.takeScreenshot("world-5-monsters");
			context.waitTicks(25);
			context.takeScreenshot("world-5-monsters-attacking");
			float health = context.computeOnClient(mc -> mc.player.getHealth());
			System.out.println("[doomcraft-test] player health after the attack: " + health);
			context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 40);
			context.takeScreenshot("world-5-monsters-shot");

			// Use the first door, then look at the open doorway.
			world.getServer().runCommand("execute as @a run doom E1M1");
			context.waitTicks(20);
			context.getInput().lookAt(context.computeOnClient(mc -> mc.player.blockPosition().relative(mc.player.getDirection(), 6).above()));
			context.waitTicks(5);
			context.takeScreenshot("world-6-start-ceiling");
		}
	}
}
