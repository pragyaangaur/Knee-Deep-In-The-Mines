package dev.pragyaan.doomcraft.item;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.entity.DoomBall;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A Doom weapon you can hold in Minecraft. Left-click fires, as with Doom's
 * mouse, and holding it keeps firing at Doom's rate. Right-click stays free
 * for opening doors. It uses Doom ammo items from your inventory.
 */
public class DoomWeaponItem extends Item {
	public final DoomWeapon weapon;

	public DoomWeaponItem(DoomWeapon weapon, Properties properties) {
		super(properties);
		this.weapon = weapon;
	}

	/** True if the player can fire this weapon now: off cooldown and with ammo. Checked on both sides. */
	public boolean canFire(Player player, ItemStack stack) {
		if (player.getCooldowns().isOnCooldown(stack)) {
			return false;
		}
		return player.isCreative() || weapon.ammo == null || countAmmo(player) >= weapon.ammoPerShot;
	}

	/** Fires once on the server. Left-click sends this, see the client attack mixin. */
	public void tryFire(ServerLevel level, Player player, ItemStack stack) {
		if (player.getCooldowns().isOnCooldown(stack)) {
			return;
		}
		if (!canFire(player, stack)) {
			DoomWorld.get(level.getServer()).message(player, "Out of " + weapon.ammo + ".");
			return;
		}
		player.getCooldowns().addCooldown(stack, weapon.cooldown);
		if (!player.isCreative() && weapon.ammo != null) {
			takeAmmo(player, weapon.ammoPerShot);
		}
		fire(level, player);
	}

	private void fire(ServerLevel level, Player player) {
		DoomWorld world = DoomWorld.get(level.getServer());
		switch (weapon) {
			case CHAINSAW -> {
				boolean hit = bullet(level, player, 0, 2.2, 2, 20);
				world.soundFrom(player, hit ? "DSSAWHIT" : "DSSAWFUL");
				return;
			}
			case PISTOL, CHAINGUN -> bullet(level, player, 5.6, 64, 5, 15);
			case SHOTGUN -> {
				for (int i = 0; i < 7; i++) {
					bullet(level, player, 5.6, 64, 5, 15);
				}
			}
			case ROCKET_LAUNCHER -> DoomBall.fireDirection(level, player, player.getLookAngle(), DoomBall.Kind.ROCKET);
			case PLASMA_RIFLE -> DoomBall.fireDirection(level, player, player.getLookAngle(), DoomBall.Kind.PLASMA);
		}
		world.soundFrom(player, weapon.sound);
	}

	/**
	 * One hitscan bullet with Doom's horizontal spread. Damage is in Doom points,
	 * picked from 1 to 3 times the base like P_GunShot, then scaled to Minecraft.
	 */
	private static boolean bullet(ServerLevel level, Player player, double spreadDegrees, double range, int minDamage, int maxDamage) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double spread = Math.toRadians((player.getRandom().nextDouble() - player.getRandom().nextDouble()) * spreadDegrees);
		double cos = Math.cos(spread);
		double sin = Math.sin(spread);
		Vec3 dir = new Vec3(look.x * cos - look.z * sin, look.y, look.x * sin + look.z * cos);
		Vec3 end = eye.add(dir.scale(range));

		HitResult wall = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 stop = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();

		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		AABB sweep = new AABB(eye, stop).inflate(1);
		for (Entity e : level.getEntities(player, sweep, e -> e instanceof LivingEntity && e.isAlive() && e.isPickable())) {
			var clip = e.getBoundingBox().inflate(0.15).clip(eye, stop);
			if (clip.isPresent()) {
				double d = clip.get().distanceToSqr(eye);
				if (d < bestDist) {
					bestDist = d;
					best = e;
				}
			}
		}

		DoomWorld.get(level.getServer()).shootLine(level, player, eye, stop);
		if (best != null) {
			int steps = Math.max(1, maxDamage / minDamage);
			float damage = minDamage * (player.getRandom().nextInt(steps) + 1);
			best.hurtServer(level, level.damageSources().playerAttack(player), damage / 5f);
			return true;
		}
		if (wall.getType() != HitResult.Type.MISS) {
			level.sendParticles(ParticleTypes.SMOKE, stop.x, stop.y, stop.z, 3, 0.02, 0.02, 0.02, 0.01);
		}
		return false;
	}

	private Item ammoItem() {
		return switch (weapon.ammo) {
			case "bullets" -> DoomCraft.DOOM_BULLETS;
			case "shells" -> DoomCraft.DOOM_SHELLS;
			case "rockets" -> DoomCraft.DOOM_ROCKETS;
			default -> DoomCraft.DOOM_CELLS;
		};
	}

	public int countAmmo(Player player) {
		Item ammo = ammoItem();
		Inventory inv = player.getInventory();
		int count = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ammo)) {
				count += s.getCount();
			}
		}
		return count;
	}

	private void takeAmmo(Player player, int amount) {
		Item ammo = ammoItem();
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ammo)) {
				int take = Math.min(amount, s.getCount());
				s.shrink(take);
				amount -= take;
			}
		}
	}
}
