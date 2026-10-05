package dev.pragyaan.doomcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pragyaan.doomcraft.DoomCraft;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.sounds.SoundSource;
import dev.pragyaan.doomcraft.client.world.DoomClientWorld;
import dev.pragyaan.doomcraft.client.world.DoomEntityRenderers;
import dev.pragyaan.doomcraft.client.world.DoomWeaponHud;
import dev.pragyaan.doomcraft.client.world.DoomStatusHud;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public final class DoomCraftClient implements ClientModInitializer {
	// Doom is heard from this far away, getting quieter with distance.
	private static final double HEARING_RANGE = 16.0;

	private static KeyMapping playKey;
	private static double nearestArcade = Double.MAX_VALUE;

	@Override
	public void onInitializeClient() {
		BlockEntityRenderers.register(DoomCraft.ARCADE_BLOCK_ENTITY, context -> new DoomArcadeRenderer());
		DoomClientWorld.register();
		DoomEntityRenderers.register();
		DoomWeaponHud.register();
		DoomStatusHud.register();
		// The Doom dimension's collision blocks are invisible, so don't outline them either.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level == null || !mc.level.getBlockState(outline.pos()).is(DoomCraft.DOOM_SOLID);
		});

		playKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.doomcraft.play", InputConstants.KEY_J,
			KeyMapping.Category.register(DoomCraft.id("doomcraft"))));

		DoomCraft.openDoomScreen = () -> Minecraft.getInstance().gui.setScreen(new DoomScreen());
		DoomCraft.arcadeClientTick = pos -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) {
				return;
			}
			double distance = Math.sqrt(mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
			nearestArcade = Math.min(nearestArcade, distance);
			// A cabinet in view runs the attract-mode demo.
			if (distance < HEARING_RANGE * 2) {
				DoomEngine.get().ensureRunning();
			}
		};

		ClientTickEvents.END_CLIENT_TICK.register(DoomCraftClient::endTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> DoomEngine.get().stop());
		Runtime.getRuntime().addShutdownHook(new Thread(() -> DoomEngine.get().stop(), "Doom shutdown"));
	}

	private static void endTick(Minecraft mc) {
		while (playKey.consumeClick()) {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new DoomScreen());
			}
		}

		float volume;
		if (mc.gui.screen() instanceof DoomScreen) {
			volume = 1f;
		} else if (mc.player != null && nearestArcade < HEARING_RANGE) {
			double t = nearestArcade / HEARING_RANGE;
			volume = (float) ((1 - t) * (1 - t));
		} else {
			volume = 0f;
		}
		DoomEngine.get().setListenerVolume(volume * mc.options.getSoundSourceVolume(SoundSource.MASTER));
		nearestArcade = Double.MAX_VALUE;
	}
}
