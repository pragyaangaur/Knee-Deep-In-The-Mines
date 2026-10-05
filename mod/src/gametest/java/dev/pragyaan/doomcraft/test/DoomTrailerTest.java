package dev.pragyaan.doomcraft.test;

import java.nio.file.Path;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pragyaan.doomcraft.entity.DoomMonster;
import dev.pragyaan.doomcraft.world.DoomMonsterKind;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.phys.Vec3;

/**
 * Records the README clip: one 1280x720 frame per game tick, so the result
 * plays at an even 20 frames a second however slowly frames are saved. It only
 * runs when DOOMCRAFT_RECORD names a folder for the frames.
 */
public class DoomTrailerTest implements FabricClientGameTest {
	private ClientGameTestContext context;
	private TestSingleplayerContext world;
	private Path out;
	private int frame;

	@Override
	public void runTest(ClientGameTestContext context) {
		String dir = System.getenv("DOOMCRAFT_RECORD");
		if (dir == null || dir.isEmpty()) {
			return;
		}
		this.context = context;
		this.out = Path.of(dir);
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			this.world = world;
			world.getConnection().waitForChunksRender();
			context.runOnClient(mc -> mc.options.chatVisibility().set(ChatVisiblity.HIDDEN));
			// A real 1280x720 window, so the HUD is laid out for the frame size.
			context.getInput().resizeWindow(1280, 720);
			cmd("gamemode survival @a");
			cmd("difficulty normal");
			cmd("time set 12800");
			cmd("gamerule advance_time false");
			cmd("give @a doomcraft:doom_shotgun");
			cmd("give @a doomcraft:doom_chaingun");
			cmd("give @a doomcraft:doom_shells 40");
			cmd("give @a doomcraft:doom_bullets 200");

			// Steve arrives in E1M1, and a zombieman, an imp and a demon come for him.
			cmd("execute as @a run doom E1M1");
			context.waitTicks(10);
			world.getConnection().waitForChunksRender();
			cmd("effect give @a minecraft:resistance 600 2 true");
			context.waitTicks(30);
			world.getServer().runOnServer(server -> {
				var p = server.getPlayerList().getPlayers().get(0);
				Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
				Vec3 side = new Vec3(-look.z, 0, look.x);
				ServerLevel level = (ServerLevel) p.level();
				float faceUs = p.getYRot() + 180;
				spawn(level, DoomMonsterKind.ZOMBIEMAN, p.position().add(look.scale(5.5)).add(side.scale(-1.5)), faceUs);
				spawn(level, DoomMonsterKind.IMP, p.position().add(look.scale(7.5)).add(side.scale(2.2)), faceUs);
				spawn(level, DoomMonsterKind.DEMON, p.position().add(look.scale(9)), faceUs);
			});
			context.getInput().pressKey(InputConstants.KEY_1);
			hold(10);
			fight(200);
		}
	}

	private static void spawn(ServerLevel level, DoomMonsterKind kind, Vec3 at, float yaw) {
		DoomMonster.spawn(level, kind, at.x, at.y + 0.05, at.z, yaw, false);
	}

	private void cmd(String command) {
		world.getServer().runCommand(command);
	}

	private void frame() {
		context.takeScreenshot(TestScreenshotOptions.of(String.format("f%05d", frame++)).disableCounterPrefix().withDestinationDir(out));
		context.waitTick();
	}

	private void hold(int ticks) {
		for (int i = 0; i < ticks; i++) {
			frame();
		}
	}

	private void rotate(float yaw, float pitch) {
		cmd(String.format(Locale.ROOT, "execute as @a at @s run tp @s ~ ~ ~ %.2f %.2f", yaw, pitch));
	}

	/** Tracks the nearest living monster, firing when the sights are on it. Switches to the chaingun for the demon. */
	private void fight(int maxTicks) {
		for (int i = 0; i < maxTicks; i++) {
			Object[] target = world.getServer().computeOnServer(server -> {
				var p = server.getPlayerList().getPlayers().get(0);
				return server.getLevel(DoomWorld.DIMENSION).getEntitiesOfClass(DoomMonster.class, p.getBoundingBox().inflate(16), DoomMonster::isAlive)
					.stream().min((a, b) -> Double.compare(a.distanceTo(p), b.distanceTo(p)))
					.map(m -> new Object[] {m.position().add(0, m.getBbHeight() * 0.55, 0), m.kind()}).orElse(null);
			});
			if (target == null) {
				hold(25);
				return;
			}
			Vec3 aim = (Vec3) target[0];
			float[] now = context.computeOnClient(mc -> {
				Vec3 eye = mc.player.getEyePosition();
				double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
				return new float[] {mc.player.getYRot(), mc.player.getXRot(), yaw, pitch};
			});
			float dyaw = ((now[2] - now[0]) % 360 + 540) % 360 - 180;
			float yaw = now[0] + dyaw * 0.3f;
			float pitch = now[1] + (now[3] - now[1]) * 0.3f;
			rotate(yaw, pitch);
			if (target[1] == DoomMonsterKind.DEMON) {
				context.getInput().pressKey(InputConstants.KEY_2);
			}
			if (Math.abs(dyaw) < 6) {
				context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			}
			frame();
		}
	}
}
