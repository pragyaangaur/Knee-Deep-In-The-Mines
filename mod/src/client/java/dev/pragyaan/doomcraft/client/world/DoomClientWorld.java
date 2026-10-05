package dev.pragyaan.doomcraft.client.world;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.pragyaan.doomcraft.world.DoomGeometry;
import dev.pragyaan.doomcraft.world.DoomMap;
import dev.pragyaan.doomcraft.world.DoomNet;
import dev.pragyaan.doomcraft.world.DoomWorld;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;

/**
 * The client side of the Doom dimension: keeps a copy of each map in sync
 * with the server, draws its walls, floors and decorations into the world,
 * and plays its music.
 */
public final class DoomClientWorld {
	private static final Map<Integer, ClientMap> MAPS = new HashMap<>();
	private static final DoomMusic MUSIC = new DoomMusic();

	private DoomClientWorld() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(DoomNet.StatePayload.TYPE, (payload, context) -> {
			ClientMap map = map(payload.map());
			if (map == null) {
				return;
			}
			for (int i = 0; i < payload.sectors().length; i++) {
				map.setSector(payload.sectors()[i], payload.floors()[i], payload.ceilings()[i], payload.lights()[i]);
			}
			for (int i = 0; i < payload.lines().length; i++) {
				map.setSwitch(payload.lines()[i], payload.linesOn()[i]);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(DoomNet.SoundPayload.TYPE, (payload, context) ->
			DoomSounds.get().play(payload.sound(), new Vec3(payload.x(), payload.y(), payload.z())));
		LevelRenderEvents.COLLECT_SUBMITS.register(DoomClientWorld::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MUSIC.close());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ClientMap current = current();
			MUSIC.update(current == null ? null : current.map.name);
		});
	}

	/** The map the player is standing in, or null outside the Doom dimension. */
	public static ClientMap current() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.level.dimension() != DoomWorld.DIMENSION) {
			return null;
		}
		return map(DoomGeometry.mapIndexAt(mc.player.getX()));
	}

	public static ClientMap map(int index) {
		ClientMap map = MAPS.get(index);
		if (map == null) {
			String name = DoomWorld.mapName(index);
			if (name == null) {
				return null;
			}
			map = new ClientMap(index, name);
			MAPS.put(index, map);
		}
		return map;
	}

	/** Light (0-255) for something standing at a world position, including distance falloff from the camera. */
	public static int lightAt(double x, double y, double z) {
		ClientMap map = current();
		Minecraft mc = Minecraft.getInstance();
		if (map == null || mc.gameRenderer == null) {
			return 255;
		}
		if (mc.player != null && mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
			return 255;
		}
		long tic = tic();
		int light = map.light(map.sectorAtWorld(x, z), tic);
		return SpriteDraw.shade(light, mc.gameRenderer.mainCamera().position().distanceTo(new Vec3(x, y, z)));
	}

	private static long tic() {
		Minecraft mc = Minecraft.getInstance();
		long gameTime = mc.level == null ? 0 : mc.level.getGameTime();
		return gameTime * 35 / 20;
	}

	private static void render(LevelRenderContext context) {
		ClientMap map = current();
		if (map == null) {
			return;
		}
		map.rebuildIfNeeded(tic());
		Vec3 cam = context.levelState().cameraRenderState.pos;
		float camYaw = context.levelState().cameraRenderState.yRot;
		long tic = tic();
		Minecraft mc = Minecraft.getInstance();
		boolean visor = mc.player != null && mc.player.hasEffect(MobEffects.NIGHT_VISION);

		int sectors = map.map.sectors.size();
		int[] light = new int[sectors];
		for (int s = 0; s < sectors; s++) {
			light[s] = visor ? 255 : map.light(s, tic);
		}

		DoomGraphics.Atlas atlas = map.atlas();
		float aw = atlas.width;
		float ah = atlas.height;
		int quads = map.quadCount();
		float[] v = map.verts();
		DoomGraphics.Region[] regions = new DoomGraphics.Region[quads];
		for (int q = 0; q < quads; q++) {
			regions[q] = map.region(q, tic);
		}
		int[] quadLight = new int[quads];
		for (int q = 0; q < quads; q++) {
			quadLight[q] = Math.max(0, Math.min(255, light[map.quadSector(q)] + map.quadContrast(q)));
		}
		float cx = (float) cam.x, cy = (float) cam.y, cz = (float) cam.z;

		PoseStack pose = context.poseStack();
		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);
		context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.text(atlas.id), (p, buffer) -> {
			for (int q = 0; q < quads; q++) {
				DoomGraphics.Region r = regions[q];
				if (r == null) {
					continue;
				}
				int base = q * 20;
				// Doom draws both faces of every surface, and this pipeline culls back faces,
				// so each quad goes in twice: once each way round.
				for (int k = 0; k < 8; k++) {
					int i = k < 4 ? k : 7 - k;
					int o = base + i * 5;
					float x = v[o], y = v[o + 1], z = v[o + 2];
					float dx = x - cx, dy = y - cy, dz = z - cz;
					int shade = visor ? 255 : SpriteDraw.shade(quadLight[q], Math.sqrt(dx * dx + dy * dy + dz * dz));
					int color = 0xFF000000 | shade << 16 | shade << 8 | shade;
					buffer.addVertex(p, x, y, z).setColor(color)
						.setUv((r.x() + v[o + 3]) / aw, (r.y() + v[o + 4]) / ah)
						.setLight(SpriteDraw.FULL_BRIGHT);
				}
			}
		});

		for (ClientMap.Decoration d : map.decorations) {
			DoomMap.Sector sector = map.map.sectors.get(d.sector());
			double wx = DoomGeometry.worldX(map.index, d.x());
			double wz = DoomGeometry.worldZ(d.y());
			double wy = d.info().hanging() ? DoomGeometry.worldY(sector.ceiling - d.info().height()) : DoomGeometry.worldY(sector.floor);
			double dist = Math.sqrt((wx - cam.x) * (wx - cam.x) + (wy - cam.y) * (wy - cam.y) + (wz - cam.z) * (wz - cam.z));
			if (dist > 96) {
				continue;
			}
			String frames = d.info().frames();
			char frame = frames.charAt((int) ((tic / 6) % frames.length()));
			int shade = d.info().bright() || visor ? 255 : SpriteDraw.shade(light[d.sector()], dist);
			pose.pushPose();
			pose.translate(wx, wy, wz);
			SpriteDraw.draw(pose, context.submitNodeCollector(), camYaw, d.info().sprite(), frame, 1, shade, 255);
			pose.popPose();
		}
		pose.popPose();
	}
}
