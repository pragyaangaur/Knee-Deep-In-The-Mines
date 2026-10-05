package dev.pragyaan.doomcraft.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pragyaan.doomcraft.client.DoomEngine;
import dev.pragyaan.doomcraft.client.DoomScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Boots the real client, puts a cabinet in front of the player, then plays
 * Doom through the screen. Screenshots land in run/screenshots.
 */
public class DoomCraftClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();

			BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
			BlockPos arcade = feet.south(3);
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand(String.format("setblock %d %d %d doomcraft:doom_arcade[facing=north]", arcade.getX(), arcade.getY(), arcade.getZ()));
			world.getServer().runCommand(String.format("setblock %d %d %d doomcraft:doom_arcade[facing=north]", arcade.getX() + 1, arcade.getY(), arcade.getZ()));
			context.getInput().lookAt(arcade);
			context.waitTicks(200);
			assertRunning("after placing the cabinet");
			context.takeScreenshot("doomcraft-1-arcade-in-world");

			context.setScreen(DoomScreen::new);
			context.waitTicks(20);
			context.takeScreenshot("doomcraft-2-doom-screen");

			// Escape opens Doom's menu. Enter picks New Game, the episode, then the skill.
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(10);
			context.takeScreenshot("doomcraft-3-doom-menu");
			for (int i = 0; i < 3; i++) {
				context.getInput().pressKey(InputConstants.KEY_RETURN);
				context.waitTicks(10);
			}
			context.waitTicks(40);
			context.takeScreenshot("doomcraft-4-e1m1");

			context.getInput().holdKeyFor(InputConstants.KEY_W, 30);
			context.getInput().moveCursor(300, 0);
			context.waitTicks(5);
			context.getInput().holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 10);
			context.waitTicks(5);
			context.takeScreenshot("doomcraft-5-moved-and-fired");
			assertRunning("after playing");

			context.getInput().pressKey(DoomScreen.EXIT_KEY);
			context.waitTicks(10);
			boolean closed = context.computeOnClient(mc -> mc.gui.screen() == null);
			if (!closed) {
				throw new AssertionError("The grave key did not return to Minecraft");
			}
			context.waitTicks(40);
			context.takeScreenshot("doomcraft-6-back-in-minecraft");
		}
		DoomEngine.get().stop();
	}

	private static void assertRunning(String when) {
		if (!DoomEngine.get().isRunning()) {
			throw new AssertionError("Doom is not running " + when + ": " + DoomEngine.get().problem());
		}
	}
}
