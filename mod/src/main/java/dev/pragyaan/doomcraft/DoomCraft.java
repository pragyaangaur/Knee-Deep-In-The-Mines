package dev.pragyaan.doomcraft;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.pragyaan.doomcraft.entity.DoomBall;
import dev.pragyaan.doomcraft.entity.DoomMonster;
import dev.pragyaan.doomcraft.entity.DoomPickup;
import dev.pragyaan.doomcraft.item.DoomWeapon;
import dev.pragyaan.doomcraft.item.DoomWeaponItem;
import dev.pragyaan.doomcraft.world.DoomNet;
import dev.pragyaan.doomcraft.world.DoomSolidBlock;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.tags.DamageTypeTags;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * DoomCraft puts Doom inside Minecraft. The /doom command takes you into a
 * dimension where Doom's maps are rebuilt from the WAD: you walk them as
 * yourself, with Doom's monsters, doors, lifts, pickups and weapons. The
 * arcade cabinet that plays the original game is still here as well.
 */
public final class DoomCraft implements ModInitializer {
	public static final String MOD_ID = "doomcraft";
	public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MOD_ID);

	public static Block ARCADE;
	public static Item ARCADE_ITEM;
	public static BlockEntityType<DoomArcadeBlockEntity> ARCADE_BLOCK_ENTITY;

	public static Block DOOM_SOLID;
	public static EntityType<DoomMonster> DOOM_MONSTER;
	public static EntityType<DoomBall> DOOM_BALL;
	public static EntityType<DoomPickup> DOOM_PICKUP;
	public static Item DOOM_BULLETS;
	public static Item DOOM_SHELLS;
	public static Item DOOM_ROCKETS;
	public static Item DOOM_CELLS;
	private static final Map<String, Item> WEAPONS = new HashMap<>();

	// Filled in by the client entrypoint. They stay no-ops on a dedicated server.
	public static Runnable openDoomScreen = () -> {};
	public static Consumer<BlockPos> arcadeClientTick = pos -> {};

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static Item weaponItem(String id) {
		return WEAPONS.get(id);
	}

	@Override
	public void onInitialize() {
		registerArcade();

		DOOM_SOLID = Blocks.register(ResourceKey.create(Registries.BLOCK, id("doom_solid")), DoomSolidBlock::new, BlockBehaviour.Properties.of()
			.strength(-1.0F, 3600000.8F)
			.mapColor(MapColor.NONE)
			.noLootTable()
			.noOcclusion()
			.isValidSpawn((state, level, pos, type) -> false)
			.noTerrainParticles()
			.pushReaction(PushReaction.IMMOVEABLE));

		DOOM_MONSTER = entity("doom_monster", EntityType.Builder.<DoomMonster>of(DoomMonster::new, MobCategory.MONSTER)
			.sized(0.9F, 1.75F).clientTrackingRange(10));
		DOOM_BALL = entity("doom_ball", EntityType.Builder.<DoomBall>of(DoomBall::new, MobCategory.MISC)
			.sized(0.4F, 0.4F).clientTrackingRange(8).updateInterval(1));
		DOOM_PICKUP = entity("doom_pickup", EntityType.Builder.<DoomPickup>of(DoomPickup::new, MobCategory.MISC)
			.sized(0.8F, 0.5F).clientTrackingRange(8));
		FabricDefaultAttributeRegistry.register(DOOM_MONSTER, DoomMonster.createAttributes());

		DOOM_BULLETS = item("doom_bullets", Item::new, new Item.Properties());
		DOOM_SHELLS = item("doom_shells", Item::new, new Item.Properties());
		DOOM_ROCKETS = item("doom_rockets", Item::new, new Item.Properties());
		DOOM_CELLS = item("doom_cells", Item::new, new Item.Properties());
		for (DoomWeapon weapon : DoomWeapon.values()) {
			WEAPONS.put(weapon.id, item("doom_" + weapon.id, p -> new DoomWeaponItem(weapon, p), new Item.Properties().stacksTo(1)));
		}
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
			for (DoomWeapon weapon : DoomWeapon.values()) {
				output.accept(WEAPONS.get(weapon.id));
			}
			output.accept(DOOM_BULLETS);
			output.accept(DOOM_SHELLS);
			output.accept(DOOM_ROCKETS);
			output.accept(DOOM_CELLS);
		});

		DoomNet.register();
		ServerPlayNetworking.registerGlobalReceiver(DoomNet.FirePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ItemStack stack = player.getMainHandItem();
			if (stack.getItem() instanceof DoomWeaponItem weapon) {
				weapon.tryFire((ServerLevel) player.level(), player, stack);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> DoomWorld.get(server).tick());
		registerDoomRules();
		registerCommand();
	}

	/** Parts of Doom's rules that apply to the player while in the Doom dimension. */
	private void registerDoomRules() {
		// Doom has no falling damage.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity.level().dimension() == DoomWorld.DIMENSION && source.is(DamageTypeTags.IS_FALL)));
		// The marine's grunt when hurt, and his scream on death.
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (entity instanceof ServerPlayer player && taken > 0 && entity.isAlive() && entity.level().dimension() == DoomWorld.DIMENSION) {
				DoomWorld.get(player.level().getServer()).soundFrom(player, "DSPLPAIN");
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player && entity.level().dimension() == DoomWorld.DIMENSION) {
				DoomWorld.get(player.level().getServer()).soundFrom(player, "DSPLDETH");
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((before, after, alive) -> {
			if (!alive) {
				DoomWorld.get(after.level().getServer()).respawned(before, after);
			}
		});
	}

	private void registerArcade() {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id("doom_arcade"));
		ARCADE = Blocks.register(blockKey, DoomArcadeBlock::new, BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_BLACK)
			.strength(3.0F, 6.0F)
			.sound(SoundType.METAL)
			.lightLevel(state -> 6));

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id("doom_arcade"));
		BlockItem item = new BlockItem(ARCADE, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
		item.registerBlocks(Item.BY_BLOCK, item);
		ARCADE_ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, item);

		ARCADE_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("doom_arcade"),
			FabricBlockEntityTypeBuilder.create(DoomArcadeBlockEntity::new, ARCADE).build());

		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(ARCADE_ITEM));
	}

	private static <T extends net.minecraft.world.entity.Entity> EntityType<T> entity(String name, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id(name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	private static Item item(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
	}

	/** /doom [map], /doom leave, /doom reset [map]. Anyone may use it, so it works in survival without cheats. */
	private void registerCommand() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("doom")
				.executes(ctx -> enter(ctx.getSource().getPlayerOrException(), "E1M1"))
				.then(Commands.literal("spawn").then(Commands.argument("monster", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
						java.util.Arrays.stream(dev.pragyaan.doomcraft.world.DoomMonsterKind.values()).map(k -> k.name().toLowerCase(java.util.Locale.ROOT)), builder))
					.executes(ctx -> spawn(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "monster")))))
				.then(Commands.literal("leave").executes(ctx -> {
					DoomWorld.get(ctx.getSource().getServer()).leave(ctx.getSource().getPlayerOrException());
					return 1;
				}))
				.then(Commands.literal("reset").then(Commands.argument("map", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DoomWorld.availableMaps(), builder))
					.executes(ctx -> {
						String map = StringArgumentType.getString(ctx, "map").toUpperCase(java.util.Locale.ROOT);
						DoomWorld.get(ctx.getSource().getServer()).reset(map);
						ctx.getSource().sendSuccess(() -> Component.literal(map + " has been reset."), false);
						return 1;
					})))
				.then(Commands.argument("map", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DoomWorld.availableMaps(), builder))
					.executes(ctx -> enter(ctx.getSource().getPlayerOrException(),
						StringArgumentType.getString(ctx, "map").toUpperCase(java.util.Locale.ROOT))))));
	}

	/** Puts a Doom monster a few blocks in front of the player, facing them. */
	private static int spawn(ServerPlayer player, String name) {
		dev.pragyaan.doomcraft.world.DoomMonsterKind kind;
		try {
			kind = dev.pragyaan.doomcraft.world.DoomMonsterKind.valueOf(name.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			player.sendSystemMessage(Component.literal("No monster called " + name + "."));
			return 0;
		}
		net.minecraft.world.phys.Vec3 look = player.getLookAngle().multiply(1, 0, 1);
		look = look.lengthSqr() < 1e-6 ? new net.minecraft.world.phys.Vec3(0, 0, 1) : look.normalize();
		// Walk back from 4 blocks out until there is room, so it doesn't land inside a wall.
		net.minecraft.world.phys.Vec3 at = player.position();
		for (double d = 4; d >= 1.5; d -= 0.5) {
			net.minecraft.world.phys.Vec3 p = player.position().add(look.scale(d));
			net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(p.x - 0.45, p.y + 0.05, p.z - 0.45, p.x + 0.45, p.y + kind.height / 32.0, p.z + 0.45);
			if (player.level().noCollision(box)) {
				at = p;
				break;
			}
		}
		DoomMonster.spawn((ServerLevel) player.level(), kind, at.x, at.y + 0.05, at.z, player.getYRot() + 180, false);
		return 1;
	}

	private static int enter(ServerPlayer player, String map) {
		DoomWorld world = DoomWorld.get(player.level().getServer());
		long start = System.currentTimeMillis();
		if (!world.enter(player, map)) {
			return 0;
		}
		LOG.info("{} entered {} ({} ms)", player.getName().getString(), map, System.currentTimeMillis() - start);
		return 1;
	}
}
