package dev.pragyaan.doomcraft.mixin;

import dev.pragyaan.doomcraft.world.DoomWorld;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Doom has no hunger and no healing over time, so inside the Doom dimension
 * the food bar stays where it was and health only comes back from pickups.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void doomcraft$pauseInDoom(ServerPlayer player, CallbackInfo ci) {
		if (player.level().dimension() == DoomWorld.DIMENSION) {
			ci.cancel();
		}
	}
}
