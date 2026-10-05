package dev.pragyaan.doomcraft.entity;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A Doom pickup lying on the floor. Walking into it does what it did in Doom,
 * translated into Minecraft: health heals, armor becomes a chestplate,
 * powerups become potion effects, and ammo and weapons become items.
 */
public class DoomPickup extends Entity {
	private static final EntityDataAccessor<Integer> TYPE = SynchedEntityData.defineId(DoomPickup.class, EntityDataSerializers.INT);

	public DoomPickup(EntityType<? extends DoomPickup> type, Level level) {
		super(type, level);
	}

	public static void spawn(ServerLevel level, int doomType, double x, double y, double z) {
		DoomPickup p = new DoomPickup(DoomCraft.DOOM_PICKUP, level);
		p.entityData.set(TYPE, doomType);
		p.setPos(x, y, z);
		level.addFreshEntity(p);
	}

	public int doomType() {
		return entityData.get(TYPE);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(TYPE, 2011);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		entityData.set(TYPE, input.getIntOr("doom_type", 2011));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("doom_type", doomType());
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (!onGround()) {
			setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
		} else {
			setDeltaMovement(Vec3.ZERO);
		}
		move(MoverType.SELF, getDeltaMovement());
	}

	@Override
	public void playerTouch(Player player) {
		if (level().isClientSide() || isRemoved() || player.isSpectator() || !player.isAlive()) {
			return;
		}
		String message = give(player);
		if (message == null) {
			return;
		}
		DoomWorld world = DoomWorld.get(level().getServer());
		world.message(player, message);
		world.recordItem(this);
		int type = doomType();
		boolean weapon = type >= 2001 && type <= 2006;
		boolean power = type == 2013 || (type >= 2022 && type <= 2026) || type == 2045;
		world.soundFrom(player, weapon ? "DSWPNUP" : power ? "DSGETPOW" : "DSITEMUP");
		discard();
	}

	/** Applies the pickup. Returns Doom's message, or null if the player can't take it right now. */
	private String give(Player player) {
		int type = doomType();
		return switch (type) {
			case 2011 -> heal(player, 10) ? "Picked up a stimpack." : null;
			case 2012 -> {
				boolean low = player.getHealth() < player.getMaxHealth() / 4;
				yield heal(player, 25) ? (low ? "Picked up a medikit that you REALLY need!" : "Picked up a medikit.") : null;
			}
			case 2014 -> {
				overheal(player, 1);
				yield "Picked up a health bonus.";
			}
			case 2015 -> {
				overheal(player, 1);
				yield "Picked up an armor bonus.";
			}
			case 2018 -> armor(player, Items.IRON_CHESTPLATE) ? "Picked up the armor." : null;
			case 2019 -> armor(player, Items.DIAMOND_CHESTPLATE) ? "Picked up the MegaArmor!" : null;
			case 2013 -> {
				overheal(player, 100);
				feed(player, 20);
				yield "Supercharge!";
			}
			case 2022 -> effect(player, MobEffects.RESISTANCE, 30, 4, "Invulnerability!");
			case 2023 -> {
				player.heal(player.getMaxHealth());
				feed(player, 20);
				yield effect(player, MobEffects.STRENGTH, 60, 2, "Berserk!");
			}
			case 2024 -> effect(player, MobEffects.INVISIBILITY, 60, 0, "Partial Invisibility");
			case 2025 -> effect(player, MobEffects.FIRE_RESISTANCE, 60, 0, "Radiation Shielding Suit");
			case 2026 -> "Computer Area Map";
			case 2045 -> effect(player, MobEffects.NIGHT_VISION, 120, 0, "Light Amplification Visor");
			case 5 -> key(player, "blue", "Picked up a blue keycard.");
			case 6 -> key(player, "yellow", "Picked up a yellow keycard.");
			case 13 -> key(player, "red", "Picked up a red keycard.");
			case 38 -> key(player, "red", "Picked up a red skull key.");
			case 39 -> key(player, "yellow", "Picked up a yellow skull key.");
			case 40 -> key(player, "blue", "Picked up a blue skull key.");
			case 2007 -> items(player, DoomCraft.DOOM_BULLETS, 10, "Picked up a clip.");
			case 2048 -> items(player, DoomCraft.DOOM_BULLETS, 50, "Picked up a box of bullets.");
			case 2008 -> items(player, DoomCraft.DOOM_SHELLS, 4, "Picked up 4 shotgun shells.");
			case 2049 -> items(player, DoomCraft.DOOM_SHELLS, 20, "Picked up a box of shotgun shells.");
			case 2010 -> items(player, DoomCraft.DOOM_ROCKETS, 1, "Picked up a rocket.");
			case 2046 -> items(player, DoomCraft.DOOM_ROCKETS, 5, "Picked up a box of rockets.");
			case 2047 -> items(player, DoomCraft.DOOM_CELLS, 20, "Picked up an energy cell.");
			case 17 -> items(player, DoomCraft.DOOM_CELLS, 100, "Picked up an energy cell pack.");
			case 8 -> {
				items(player, DoomCraft.DOOM_BULLETS, 10, null);
				items(player, DoomCraft.DOOM_SHELLS, 4, null);
				items(player, DoomCraft.DOOM_ROCKETS, 1, null);
				yield "Picked up a backpack full of ammo!";
			}
			case 2001 -> weapon(player, DoomCraft.weaponItem("shotgun"), DoomCraft.DOOM_SHELLS, 8, "You got the shotgun!");
			case 2002 -> weapon(player, DoomCraft.weaponItem("chaingun"), DoomCraft.DOOM_BULLETS, 20, "You got the chaingun!");
			case 2003 -> weapon(player, DoomCraft.weaponItem("rocket_launcher"), DoomCraft.DOOM_ROCKETS, 2, "You got the rocket launcher!");
			case 2004 -> weapon(player, DoomCraft.weaponItem("plasma_rifle"), DoomCraft.DOOM_CELLS, 40, "You got the plasma gun!");
			case 2005 -> weapon(player, DoomCraft.weaponItem("chainsaw"), null, 0, "A chainsaw! Find some meat!");
			default -> null;
		};
	}

	/** Doom health is five times Minecraft health. */
	private static boolean heal(Player player, int doomPoints) {
		if (player.getHealth() >= player.getMaxHealth()) {
			return false;
		}
		player.heal(doomPoints / 5f);
		feed(player, doomPoints / 5);
		return true;
	}

	/** Health pickups fill the Minecraft food bar a little too, so it's full again when you leave. */
	private static void feed(Player player, int food) {
		player.getFoodData().eat(Math.max(1, food), 0.3f);
	}

	/** Bonuses and the soul sphere go past 100%, which Minecraft shows as golden hearts. */
	private static void overheal(Player player, int doomPoints) {
		float points = doomPoints / 5f;
		float room = player.getMaxHealth() - player.getHealth();
		player.heal(Math.min(room, points));
		float extra = points - Math.min(room, points);
		if (extra > 0) {
			player.setAbsorptionAmount(Math.min(player.getMaxHealth(), player.getAbsorptionAmount() + extra));
		}
	}

	private static boolean armor(Player player, Item chestplate) {
		ItemStack current = player.getItemBySlot(EquipmentSlot.CHEST);
		boolean better = current.isEmpty() || (chestplate == Items.DIAMOND_CHESTPLATE && !current.is(Items.DIAMOND_CHESTPLATE)
			&& !current.is(Items.NETHERITE_CHESTPLATE));
		if (!better) {
			return false;
		}
		if (!current.isEmpty()) {
			if (!player.getInventory().add(current)) {
				player.spawnAtLocation((ServerLevel) player.level(), current);
			}
		}
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chestplate));
		return true;
	}

	private static String effect(Player player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int seconds, int amplifier, String message) {
		player.addEffect(new MobEffectInstance(effect, seconds * 20, amplifier));
		return message;
	}

	private String key(Player player, String colour, String message) {
		DoomWorld.get(level().getServer()).giveKey(player, colour);
		return message;
	}

	private static String items(Player player, Item item, int count, String message) {
		ItemStack stack = new ItemStack(item, count);
		if (!player.getInventory().add(stack)) {
			return null;
		}
		return message;
	}

	private static String weapon(Player player, Item weapon, Item ammo, int ammoCount, String message) {
		boolean has = player.getInventory().contains(new ItemStack(weapon));
		if (!has) {
			player.getInventory().add(new ItemStack(weapon));
		}
		if (ammo != null) {
			player.getInventory().add(new ItemStack(ammo, ammoCount));
		} else if (has) {
			return null;
		}
		return message;
	}

	@Override
	public boolean isPickable() {
		return false;
	}
}
