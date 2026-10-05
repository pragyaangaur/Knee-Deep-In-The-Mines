package dev.pragyaan.doomcraft.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * Full-window Doom. Every key goes to Doom, including Escape, which opens
 * Doom's own menu. The grave key (`) returns to Minecraft.
 */
public class DoomScreen extends Screen {
	public static final int EXIT_KEY = InputConstants.KEY_GRAVE;

	private static double pendingMouseX;

	private final Map<Integer, int[]> held = new HashMap<>();
	private int buttons;
	private long openedAt;

	public DoomScreen() {
		super(Component.translatable("screen.doomcraft.doom"));
	}

	/** Called from the mouse mixin with raw relative motion while this screen is open. */
	public static void addMouseMotion(double dx) {
		pendingMouseX += dx;
	}

	@Override
	protected void init() {
		DoomEngine.get().ensureRunning();
		openedAt = System.currentTimeMillis();
		pendingMouseX = 0;
		InputConstants.grabMouse(minecraft.getWindow(), minecraft.getWindow().getScreenWidth() / 2.0, minecraft.getWindow().getScreenHeight() / 2.0);
	}

	@Override
	public void removed() {
		DoomEngine engine = DoomEngine.get();
		for (int[] keys : held.values()) {
			for (int key : keys) {
				engine.sendKey(key, false);
			}
		}
		held.clear();
		if (buttons != 0) {
			buttons = 0;
			engine.sendMouse(0, 0);
		}
		InputConstants.releaseMouse(minecraft.getWindow(), minecraft.getWindow().getScreenWidth() / 2.0, minecraft.getWindow().getScreenHeight() / 2.0);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void tick() {
		DoomEngine.get().ensureRunning();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, width, height, 0xFF000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		DoomEngine engine = DoomEngine.get();
		flushMouse(engine);
		engine.updateTexture();

		// Doom's 320x200 picture was drawn for a 4:3 monitor.
		int w = width;
		int h = w * 3 / 4;
		if (h > height) {
			h = height;
			w = h * 4 / 3;
		}
		int x = (width - w) / 2;
		int y = (height - h) / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, DoomEngine.TEXTURE, x, y, 0, 0, w, h,
			DoomEngine.WIDTH, DoomEngine.HEIGHT, DoomEngine.WIDTH, DoomEngine.HEIGHT);

		if (!engine.isRunning()) {
			String message = engine.problem() != null ? engine.problem() : "Doom has exited. Starting it again...";
			graphics.centeredText(font, message, width / 2, height / 2, 0xFFFF5555);
		}
		if (System.currentTimeMillis() - openedAt < 5000) {
			graphics.centeredText(font, Component.translatable("screen.doomcraft.hint"), width / 2, 4, 0xFFFFFF55);
		}
	}

	private void flushMouse(DoomEngine engine) {
		int dx = (int) pendingMouseX;
		pendingMouseX -= dx;
		while (dx != 0) {
			int step = Math.max(-127, Math.min(127, dx));
			engine.sendMouse(buttons, step);
			dx -= step;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == EXIT_KEY) {
			onClose();
			return true;
		}
		if (held.containsKey(event.key())) {
			return true;
		}
		int action = DoomKeys.action(event.key());
		int character = DoomKeys.character(event.keycode());
		int[] keys = action >= 0 && character >= 0 && action != character ? new int[] {action, character}
			: action >= 0 ? new int[] {action}
			: character >= 0 ? new int[] {character}
			: new int[0];
		held.put(event.key(), keys);
		for (int key : keys) {
			DoomEngine.get().sendKey(key, true);
		}
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		int[] keys = held.remove(event.key());
		if (keys != null) {
			for (int key : keys) {
				DoomEngine.get().sendKey(key, false);
			}
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return mouseButton(event.button(), true);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return mouseButton(event.button(), false);
	}

	// Left button fires. Right button opens doors and presses switches.
	private boolean mouseButton(int button, boolean down) {
		DoomEngine engine = DoomEngine.get();
		if (button == InputConstants.MOUSE_BUTTON_LEFT) {
			buttons = down ? buttons | 1 : buttons & ~1;
			engine.sendMouse(buttons, 0);
		} else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
			engine.sendKey(DoomKeys.USE, down);
		}
		return true;
	}
}
