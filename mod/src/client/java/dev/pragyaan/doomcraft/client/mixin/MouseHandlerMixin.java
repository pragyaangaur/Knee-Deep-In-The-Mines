package dev.pragyaan.doomcraft.client.mixin;

import dev.pragyaan.doomcraft.client.DoomScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands raw mouse motion to the Doom screen so the mouse can turn the player. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "onMove", at = @At("HEAD"))
	private void doomcraft$captureMotion(long handle, double xpos, double ypos, double xrel, double yrel, CallbackInfo ci) {
		if (minecraft.gui.screen() instanceof DoomScreen) {
			DoomScreen.addMouseMotion(xrel);
		}
	}
}
