package dev.pragyaan.doomcraft.client;

import com.mojang.blaze3d.platform.InputConstants;

/** Translates Minecraft's SDL key events into Doom key codes (doomkeys.h). */
final class DoomKeys {
	static final int RIGHTARROW = 0xAE;
	static final int LEFTARROW = 0xAC;
	static final int UPARROW = 0xAD;
	static final int DOWNARROW = 0xAF;
	static final int STRAFE_L = 0xA0;
	static final int STRAFE_R = 0xA1;
	static final int USE = 0xA2;
	static final int FIRE = 0xA3;
	static final int ESCAPE = 27;
	static final int ENTER = 13;
	static final int TAB = 9;
	static final int BACKSPACE = 0x7F;
	static final int PAUSE = 0xFF;
	static final int RSHIFT = 0x80 + 0x36;
	static final int RALT = 0x80 + 0x38;

	private DoomKeys() {
	}

	/**
	 * The Doom action key for a physical key, or -1. WASD and E are added on top
	 * of the classic layout so the controls feel like Minecraft.
	 */
	static int action(int scancode) {
		return switch (scancode) {
			case InputConstants.KEY_W, InputConstants.KEY_UP -> UPARROW;
			case InputConstants.KEY_S, InputConstants.KEY_DOWN -> DOWNARROW;
			case InputConstants.KEY_A -> STRAFE_L;
			case InputConstants.KEY_D -> STRAFE_R;
			case InputConstants.KEY_LEFT -> LEFTARROW;
			case InputConstants.KEY_RIGHT -> RIGHTARROW;
			case InputConstants.KEY_E, InputConstants.KEY_SPACE -> USE;
			case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> FIRE;
			case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> RSHIFT;
			case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> RALT;
			case InputConstants.KEY_RETURN -> ENTER;
			case InputConstants.KEY_ESCAPE -> ESCAPE;
			case InputConstants.KEY_TAB -> TAB;
			case InputConstants.KEY_BACKSPACE -> BACKSPACE;
			case InputConstants.KEY_PAUSE -> PAUSE;
			default -> {
				if (scancode >= InputConstants.KEY_F1 && scancode <= InputConstants.KEY_F1 + 9) {
					yield 0x80 + 0x3B + (scancode - InputConstants.KEY_F1);
				}
				if (scancode == InputConstants.KEY_F1 + 10) {
					yield 0x80 + 0x57;
				}
				if (scancode == InputConstants.KEY_F12) {
					yield 0x80 + 0x58;
				}
				yield -1;
			}
		};
	}

	/**
	 * The plain character for a key, or -1. Doom reads these for weapon numbers,
	 * y/n prompts, savegame names and cheat codes, so letters are sent as well
	 * as any action they are bound to.
	 */
	static int character(int keycode) {
		if (keycode >= 'A' && keycode <= 'Z') {
			return keycode + ('a' - 'A');
		}
		if (keycode > ' ' && keycode < 127) {
			return keycode;
		}
		return -1;
	}
}
