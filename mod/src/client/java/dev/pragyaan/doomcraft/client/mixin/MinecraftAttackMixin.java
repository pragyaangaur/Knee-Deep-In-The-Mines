package dev.pragyaan.doomcraft.client.mixin;

import dev.pragyaan.doomcraft.client.world.DoomWeaponHud;
import dev.pragyaan.doomcraft.item.DoomWeaponItem;
import dev.pragyaan.doomcraft.world.DoomNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With a Doom weapon in hand, the attack button fires it instead of punching
 * or breaking blocks. Holding the button keeps firing at the weapon's rate.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
	@Shadow
	public LocalPlayer player;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void doomcraft$fireOnClick(CallbackInfoReturnable<Boolean> cir) {
		if (doomcraft$tryFire()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void doomcraft$fireWhileHeld(boolean down, CallbackInfo ci) {
		if (player != null && player.getMainHandItem().getItem() instanceof DoomWeaponItem) {
			if (down) {
				doomcraft$tryFire();
			}
			ci.cancel();
		}
	}

	private boolean doomcraft$tryFire() {
		if (player == null) {
			return false;
		}
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof DoomWeaponItem weapon)) {
			return false;
		}
		if (weapon.canFire(player, stack)) {
			player.getCooldowns().addCooldown(stack, weapon.weapon.cooldown);
			DoomWeaponHud.lastFireTime = System.currentTimeMillis();
			ClientPlayNetworking.send(DoomNet.FirePayload.INSTANCE);
		}
		return true;
	}
}
