package dev.pragyaan.doomcraft.entity;

import java.util.List;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.world.DoomMonsterKind;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A Doom monster living in Minecraft. It thinks like Doom's A_Look and
 * A_Chase: it wakes when it sees you, chases, and decides to shoot with odds
 * that fall off with distance. Minecraft's pathfinding does the walking.
 * The client draws it with the sprite frames from the WAD.
 */
public class DoomMonster extends Monster {
	public static final int STATE_WALK = 0;
	public static final int STATE_ATTACK = 1;
	public static final int STATE_PAIN = 2;
	public static final int ATTACK_TICKS = 14;
	public static final int CORPSE_TICKS = 20 * 120;

	private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(DoomMonster.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(DoomMonster.class, EntityDataSerializers.INT);

	private boolean ambush;
	private boolean awake;
	private int attackTimer = -1;
	private boolean meleeAttack;
	private int cooldown = 20;
	private int painTimer;
	private int repath;

	// Client side: when the current state began, for picking animation frames.
	public int stateStartTick;

	public DoomMonster(EntityType<? extends DoomMonster> type, Level level) {
		super(type, level);
		xpReward = 5;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
			.add(Attributes.MAX_HEALTH, 4)
			.add(Attributes.MOVEMENT_SPEED, 0.05)
			.add(Attributes.FOLLOW_RANGE, 64)
			.add(Attributes.ATTACK_DAMAGE, 2);
	}

	public static DoomMonster spawn(ServerLevel level, DoomMonsterKind kind, double x, double y, double z, float yaw, boolean ambush) {
		DoomMonster m = new DoomMonster(DoomCraft.DOOM_MONSTER, level);
		m.setKind(kind);
		m.ambush = ambush;
		m.snapTo(x, y, z, yaw, 0);
		m.setYHeadRot(yaw);
		m.setYBodyRot(yaw);
		level.addFreshEntity(m);
		return m;
	}

	public DoomMonsterKind kind() {
		int k = entityData.get(KIND);
		DoomMonsterKind[] all = DoomMonsterKind.values();
		return all[Math.max(0, Math.min(all.length - 1, k))];
	}

	public int state() {
		return entityData.get(STATE);
	}

	private void setKind(DoomMonsterKind kind) {
		entityData.set(KIND, kind.ordinal());
		getAttribute(Attributes.MAX_HEALTH).setBaseValue(Math.max(1, kind.health / 5.0));
		setHealth(getMaxHealth());
		// Doom speed is units per chase step. These give roughly Doom's walking pace.
		double blocksPerSecond = switch (kind) {
			case DEMON, SPECTRE -> 5.0;
			case IMP, BARON, CACODEMON -> 2.9;
			case BARREL -> 0;
			default -> 2.2;
		};
		getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(blocksPerSecond / 43.0);
		setNoGravity(kind.flying);
		xpReward = Math.max(1, kind.health / 20);
		refreshDimensions();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(KIND, 0);
		builder.define(STATE, STATE_WALK);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (KIND.equals(accessor)) {
			refreshDimensions();
			setNoGravity(kind().flying);
		}
		if (STATE.equals(accessor)) {
			stateStartTick = tickCount;
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		DoomMonsterKind kind = kind();
		// Doom's radius is wider than a corridor of blocks allows, so keep the
		// collision box under a block wide. The sprite is still drawn full size.
		float width = Math.min(0.9f, kind.radius * 2 / 32f);
		return EntityDimensions.scalable(width, kind.height / 32f);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("doom_kind", entityData.get(KIND));
		output.putInt("doom_ambush", ambush ? 1 : 0);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(KIND, input.getIntOr("doom_kind", 0));
		ambush = input.getIntOr("doom_ambush", 0) != 0;
		setNoGravity(kind().flying);
		refreshDimensions();
	}

	// ------------------------------------------------------------ AI

	@Override
	protected void registerGoals() {
		// Doom's own rules run in customServerAiStep instead.
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		DoomMonsterKind kind = kind();
		if (kind.isBarrel()) {
			return;
		}
		cooldown--;

		LivingEntity target = getTarget();
		if (target == null || !target.isAlive() || (target instanceof Player p && (p.isCreative() || p.isSpectator()))) {
			target = look(level);
			setTarget(target);
			if (target == null) {
				getNavigation().stop();
				return;
			}
		}

		if (painTimer > 0) {
			painTimer--;
			getNavigation().stop();
			if (painTimer == 0) {
				setState(STATE_WALK);
			}
			return;
		}

		getLookControl().setLookAt(target, 30, 30);
		double dist = distanceTo(target);
		boolean sees = hasLineOfSight(target);

		if (attackTimer >= 0) {
			attackTimer++;
			faceTarget(target);
			getNavigation().stop();
			if (kind == DoomMonsterKind.LOST_SOUL) {
				chargeTick(level, target);
			} else if (attackTimer == ATTACK_TICKS / 2) {
				if (meleeAttack) {
					melee(level, target);
				} else {
					ranged(level, target);
				}
			}
			if (attackTimer >= ATTACK_TICKS) {
				attackTimer = -1;
				cooldown = 10 + random.nextInt(25);
				setState(STATE_WALK);
				if (kind == DoomMonsterKind.LOST_SOUL) {
					setDeltaMovement(Vec3.ZERO);
				}
			}
			return;
		}

		if (random.nextInt(150) == 0) {
			DoomWorld.get(level.getServer()).soundFrom(this, kind.activeSound);
		}

		double reach = getBbWidth() / 2 + target.getBbWidth() / 2 + 1.2;
		if (kind.meleeDamage > 0 && dist < reach && sees && kind != DoomMonsterKind.LOST_SOUL) {
			startAttack(true);
			return;
		}
		if (sees && cooldown <= 0 && wantsToShoot(kind, dist)) {
			if (kind == DoomMonsterKind.LOST_SOUL) {
				DoomWorld.get(level.getServer()).soundFrom(this, kind.attackSound);
				startAttack(true);
				return;
			}
			if (kind.ranged != DoomMonsterKind.Ranged.NONE) {
				startAttack(false);
				return;
			}
		}

		if (kind.flying) {
			Vec3 to = target.getEyePosition().subtract(position().add(0, getBbHeight() / 2, 0));
			double speed = getAttributeValue(Attributes.MOVEMENT_SPEED) * 6;
			Vec3 v = to.lengthSqr() > 1 ? to.normalize().scale(speed) : Vec3.ZERO;
			setDeltaMovement(getDeltaMovement().scale(0.5).add(v.scale(0.5)));
			faceTarget(target);
		} else if (repath-- <= 0 || getNavigation().isDone()) {
			repath = 8;
			getNavigation().moveTo(target, 1.0);
			// Walking into a door opens it, as Doom's monsters do.
			if (horizontalCollision || getNavigation().isDone()) {
				DoomWorld.get(level.getServer()).monsterUse(this);
			}
		}
	}

	/** A_Look: wake on sight, or when the player is right next to us. Ambush monsters wait for line of sight. */
	private LivingEntity look(ServerLevel level) {
		Player nearest = null;
		double best = 48;
		for (Player p : level.players()) {
			if (p.isCreative() || p.isSpectator() || !p.isAlive()) {
				continue;
			}
			double d = distanceTo(p);
			if (d < best && (hasLineOfSight(p) && (d < 32 || awake) || (!ambush && d < 4))) {
				best = d;
				nearest = p;
			}
		}
		if (nearest != null && !awake) {
			awake = true;
			DoomWorld.get(level.getServer()).soundFrom(this, kind().sightSound);
			cooldown = 10 + random.nextInt(20);
		}
		return nearest;
	}

	/** P_CheckMissileRange, roughly: the further away, the less likely to fire this tick. */
	private boolean wantsToShoot(DoomMonsterKind kind, double dist) {
		double units = dist * 32 - 64;
		if (kind.meleeDamage == 0) {
			units -= 128;
		}
		double chance = Math.max(0.02, 0.12 * (1 - Math.min(200, Math.max(0, units / 8)) / 200.0));
		return random.nextDouble() < chance;
	}

	private void startAttack(boolean melee) {
		meleeAttack = melee;
		attackTimer = 0;
		setState(STATE_ATTACK);
	}

	private void faceTarget(LivingEntity target) {
		double dx = target.getX() - getX();
		double dz = target.getZ() - getZ();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
		setYRot(yaw);
		setYHeadRot(yaw);
		setYBodyRot(yaw);
	}

	private void melee(ServerLevel level, LivingEntity target) {
		DoomMonsterKind kind = kind();
		if (distanceTo(target) > getBbWidth() / 2 + target.getBbWidth() / 2 + 1.6) {
			return;
		}
		DoomWorld.get(level.getServer()).soundFrom(this, kind == DoomMonsterKind.IMP ? "DSCLAW" : kind.attackSound);
		// Doom melee is (random 1-8) times a base; the tables give the maximum.
		float damage = (random.nextInt(8) + 1) * (kind.meleeDamage / 8f);
		target.hurtServer(level, damageSources().mobAttack(this), damage / 5f);
	}

	private void ranged(ServerLevel level, LivingEntity target) {
		DoomMonsterKind kind = kind();
		DoomWorld world = DoomWorld.get(level.getServer());
		switch (kind.ranged) {
			case BULLET -> {
				world.soundFrom(this, kind.attackSound);
				hitscan(level, target, 1);
			}
			case SHOTGUN -> {
				world.soundFrom(this, kind.attackSound);
				hitscan(level, target, 3);
			}
			case IMP_BALL, CACO_BALL, BARON_BALL -> {
				world.soundFrom(this, kind.attackSound);
				DoomBall.Kind ball = kind.ranged == DoomMonsterKind.Ranged.IMP_BALL ? DoomBall.Kind.IMP
					: kind.ranged == DoomMonsterKind.Ranged.CACO_BALL ? DoomBall.Kind.CACODEMON : DoomBall.Kind.BARON;
				DoomBall.fire(level, this, target, ball);
			}
			default -> {
			}
		}
	}

	/** Doom's monster hitscan: each pellet is aimed with a random spread and does 3 to 15 points. */
	private void hitscan(ServerLevel level, LivingEntity target, int pellets) {
		Vec3 eye = getEyePosition();
		Vec3 aim = target.getBoundingBox().getCenter().subtract(eye).normalize();
		for (int i = 0; i < pellets; i++) {
			double spread = Math.toRadians((random.nextDouble() - random.nextDouble()) * 22.5);
			double cos = Math.cos(spread);
			double sin = Math.sin(spread);
			Vec3 dir = new Vec3(aim.x * cos - aim.z * sin, aim.y, aim.x * sin + aim.z * cos);
			Vec3 end = eye.add(dir.scale(64));
			HitResult wall = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			Vec3 stop = wall.getType() == HitResult.Type.MISS ? end : wall.getLocation();
			if (target.getBoundingBox().inflate(0.1).clip(eye, stop).isPresent()) {
				float damage = (random.nextInt(5) + 1) * 3;
				target.hurtServer(level, damageSources().mobAttack(this), damage / 5f);
			}
			DoomWorld.get(level.getServer()).shootLine(level, this, eye, stop);
		}
	}

	/** The lost soul flies straight at its target and bites on contact. */
	private void chargeTick(ServerLevel level, LivingEntity target) {
		if (attackTimer == 1) {
			Vec3 dir = target.getEyePosition().subtract(position().add(0, getBbHeight() / 2, 0)).normalize();
			setDeltaMovement(dir.scale(0.9));
		}
		if (getBoundingBox().inflate(0.3).intersects(target.getBoundingBox())) {
			float damage = (random.nextInt(8) + 1) * 3;
			target.hurtServer(level, damageSources().mobAttack(this), damage / 5f);
			attackTimer = ATTACK_TICKS;
		}
	}

	private void setState(int state) {
		entityData.set(STATE, state);
	}

	// ------------------------------------------------------------ damage and death

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		boolean hurt = super.hurtServer(level, source, damage);
		if (!hurt || !isAlive()) {
			return hurt;
		}
		DoomMonsterKind kind = kind();
		Entity attacker = source.getEntity();
		// Infighting: a monster hurt by another kind of monster turns on it.
		if (attacker instanceof DoomMonster other && other.kind() != kind) {
			setTarget(other);
		} else if (attacker instanceof LivingEntity living && getTarget() == null) {
			setTarget(living);
		}
		awake = true;
		if (kind.painChance > 0 && random.nextInt(256) < kind.painChance) {
			painTimer = 5;
			attackTimer = -1;
			setState(STATE_PAIN);
			DoomWorld.get(level.getServer()).soundFrom(this, kind.painSound);
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		DoomMonsterKind kind = kind();
		DoomWorld world = DoomWorld.get(level.getServer());
		world.soundFrom(this, kind.deathSound);
		setNoGravity(false);
		if (!kind.isBarrel()) {
			world.recordKill(this);
		}
		if (kind.isBarrel()) {
			explode(level, source.getEntity());
		}
		if (kind == DoomMonsterKind.BARON) {
			world.bossDeath(level, this);
		}
		// Doom's zombies drop what they were carrying.
		if (kind == DoomMonsterKind.ZOMBIEMAN) {
			DoomPickup.spawn(level, 2007, getX(), getY(), getZ());
		} else if (kind == DoomMonsterKind.SHOTGUN_GUY) {
			DoomPickup.spawn(level, 2001, getX(), getY(), getZ());
		}
	}

	/** A_Explode: 128 points at the centre, falling off to nothing 128 units away. */
	private void explode(ServerLevel level, @Nullable Entity cause) {
		AABB area = getBoundingBox().inflate(4);
		List<LivingEntity> hit = level.getEntitiesOfClass(LivingEntity.class, area, e -> e != this && e.isAlive());
		for (LivingEntity e : hit) {
			double units = Math.max(0, e.distanceTo(this) * 32 - e.getBbWidth() * 16);
			if (units >= 128 || !e.hasLineOfSight(this)) {
				continue;
			}
			e.hurtServer(level, damageSources().explosion(this, cause instanceof LivingEntity l ? l : this), (float) ((128 - units) / 5));
		}
	}

	@Override
	protected void tickDeath() {
		// Doom leaves bodies behind, so this one stays for a while instead of vanishing in a puff.
		deathTime++;
		if (deathTime >= CORPSE_TICKS && !level().isClientSide()) {
			remove(RemovalReason.KILLED);
		}
	}

	@Override
	public boolean isPushable() {
		return isAlive() && !kind().isBarrel();
	}

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	public void checkDespawn() {
	}

	@Override
	protected @Nullable SoundEvent getHurtSound(DamageSource source) {
		return null;
	}

	@Override
	protected @Nullable SoundEvent getDeathSound() {
		return null;
	}

	@Override
	protected @Nullable SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected void playStepSound(BlockPos pos, BlockState state) {
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
		return false;
	}
}
