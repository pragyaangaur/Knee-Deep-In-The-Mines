package dev.pragyaan.doomcraft.entity;

import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A Doom fireball. It flies in a straight line at Doom's speed and bursts on whatever it hits. */
public class DoomBall extends Projectile {
	public enum Kind {
		IMP("BAL1", 10, 3),
		CACODEMON("BAL2", 10, 5),
		BARON("BAL7", 15, 8),
		ROCKET("MISL", 20, 20),
		PLASMA("PLSS", 25, 5);

		public final String sprite;
		final float speed;
		final int damage;

		Kind(String sprite, float speed, int damage) {
			this.sprite = sprite;
			this.speed = speed;
			this.damage = damage;
		}
	}

	public static final int BURST_TICKS = 8;

	private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(DoomBall.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> BURST = SynchedEntityData.defineId(DoomBall.class, EntityDataSerializers.INT);

	private int life;
	private int burstStart = -1;

	public DoomBall(EntityType<? extends DoomBall> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static void fire(ServerLevel level, LivingEntity shooter, LivingEntity target, Kind kind) {
		DoomBall ball = new DoomBall(DoomCraft.DOOM_BALL, level);
		ball.entityData.set(KIND, kind.ordinal());
		ball.setOwner(shooter);
		Vec3 from = shooter.position().add(0, shooter.getBbHeight() * 0.6, 0);
		Vec3 dir = target.getBoundingBox().getCenter().subtract(from).normalize();
		ball.setPos(from.add(dir.scale(shooter.getBbWidth() * 0.6)));
		// Doom speed is units per tic. Convert to blocks per Minecraft tick.
		ball.setDeltaMovement(dir.scale(kind.speed * 35 / 20.0 / 32.0));
		level.addFreshEntity(ball);
	}

	/** Fires a ball from a player (or anything) along a direction rather than at a target. */
	public static void fireDirection(ServerLevel level, LivingEntity shooter, Vec3 direction, Kind kind) {
		DoomBall ball = new DoomBall(DoomCraft.DOOM_BALL, level);
		ball.entityData.set(KIND, kind.ordinal());
		ball.setOwner(shooter);
		Vec3 dir = direction.normalize();
		ball.setPos(shooter.getEyePosition().add(0, -0.2, 0).add(dir.scale(0.6)));
		ball.setDeltaMovement(dir.scale(kind.speed * 35 / 20.0 / 32.0));
		level.addFreshEntity(ball);
	}

	public Kind kind() {
		Kind[] all = Kind.values();
		return all[Math.max(0, Math.min(all.length - 1, entityData.get(KIND)))];
	}

	/** Ticks since this ball burst, or -1 while it is still flying. */
	public int burstAge() {
		return burstStart < 0 ? -1 : tickCount - burstStart;
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (BURST.equals(accessor) && entityData.get(BURST) >= 0 && burstStart < 0) {
			burstStart = tickCount;
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(KIND, 0);
		builder.define(BURST, -1);
	}

	@Override
	public void tick() {
		super.tick();
		if (entityData.get(BURST) >= 0) {
			setDeltaMovement(Vec3.ZERO);
			if (!level().isClientSide() && burstAge() > BURST_TICKS) {
				discard();
			}
			return;
		}
		if (++life > 400) {
			discard();
			return;
		}
		HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
		Vec3 next = hit.getType() == HitResult.Type.MISS ? position().add(getDeltaMovement()) : hit.getLocation();
		setPos(next);
		if (hit.getType() != HitResult.Type.MISS && !level().isClientSide()) {
			burst(hit);
		}
	}

	private void burst(HitResult hit) {
		ServerLevel level = (ServerLevel) level();
		if (hit instanceof EntityHitResult eh) {
			Entity target = eh.getEntity();
			Entity owner = getOwner();
			float damage = (random.nextInt(8) + 1) * kind().damage;
			target.hurtServer(level, damageSources().mobProjectile(this, owner instanceof LivingEntity l ? l : null), damage / 5f);
			// Burst just short of the target, as Doom does, so it isn't drawn inside the victim's head.
			Vec3 back = getDeltaMovement().lengthSqr() > 1e-6 ? getDeltaMovement().normalize().scale(0.6) : Vec3.ZERO;
			setPos(position().subtract(back));
		}
		if (kind() == Kind.ROCKET) {
			splash(level);
		}
		DoomWorld.get(level.getServer()).soundFrom(this, kind() == Kind.ROCKET ? "DSBAREXP" : "DSFIRXPL");
		burstStart = tickCount;
		entityData.set(BURST, 1);
	}

	/** A_Explode for rockets: 128 points at the centre, nothing at 128 units. */
	private void splash(ServerLevel level) {
		Entity owner = getOwner();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(4), LivingEntity::isAlive)) {
			double units = Math.max(0, e.distanceTo(this) * 32 - e.getBbWidth() * 16);
			if (units < 128) {
				e.hurtServer(level, damageSources().explosion(this, owner), (float) ((128 - units) / 5));
			}
		}
	}

	@Override
	protected boolean canHitEntity(Entity entity) {
		return super.canHitEntity(entity) && !(entity instanceof DoomBall) && entity.isAlive();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("doom_kind", entityData.get(KIND));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		entityData.set(KIND, input.getIntOr("doom_kind", 0));
	}
}
