package dev.pragyaan.doomcraft.world;

import dev.pragyaan.doomcraft.DoomCraft;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Messages from the server to clients about the Doom world. */
public final class DoomNet {
	private DoomNet() {
	}

	/** Current heights of moved sectors and the state of flipped switches in one map. */
	public record StatePayload(int map, int[] sectors, float[] floors, float[] ceilings, int[] lights, int[] lines, boolean[] linesOn)
		implements CustomPacketPayload {
		public static final Type<StatePayload> TYPE = new Type<>(DoomCraft.id("state"));
		public static final StreamCodec<FriendlyByteBuf, StatePayload> CODEC = CustomPacketPayload.codec(StatePayload::write, StatePayload::read);

		private static StatePayload read(FriendlyByteBuf buf) {
			int map = buf.readVarInt();
			int n = buf.readVarInt();
			int[] sectors = new int[n];
			float[] floors = new float[n];
			float[] ceilings = new float[n];
			int[] lights = new int[n];
			for (int i = 0; i < n; i++) {
				sectors[i] = buf.readVarInt();
				floors[i] = buf.readFloat();
				ceilings[i] = buf.readFloat();
				lights[i] = buf.readVarInt();
			}
			int m = buf.readVarInt();
			int[] lines = new int[m];
			boolean[] on = new boolean[m];
			for (int i = 0; i < m; i++) {
				lines[i] = buf.readVarInt();
				on[i] = buf.readBoolean();
			}
			return new StatePayload(map, sectors, floors, ceilings, lights, lines, on);
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeVarInt(map);
			buf.writeVarInt(sectors.length);
			for (int i = 0; i < sectors.length; i++) {
				buf.writeVarInt(sectors[i]);
				buf.writeFloat(floors[i]);
				buf.writeFloat(ceilings[i]);
				buf.writeVarInt(lights[i]);
			}
			buf.writeVarInt(lines.length);
			for (int i = 0; i < lines.length; i++) {
				buf.writeVarInt(lines[i]);
				buf.writeBoolean(linesOn[i]);
			}
		}

		@Override
		public Type<StatePayload> type() {
			return TYPE;
		}
	}

	/** A Doom sound effect, by lump name, played at a point in the world. */
	public record SoundPayload(String sound, double x, double y, double z) implements CustomPacketPayload {
		public static final Type<SoundPayload> TYPE = new Type<>(DoomCraft.id("sound"));
		public static final StreamCodec<FriendlyByteBuf, SoundPayload> CODEC = CustomPacketPayload.codec(SoundPayload::write, SoundPayload::read);

		private static SoundPayload read(FriendlyByteBuf buf) {
			return new SoundPayload(buf.readUtf(16), buf.readDouble(), buf.readDouble(), buf.readDouble());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(sound, 16);
			buf.writeDouble(x);
			buf.writeDouble(y);
			buf.writeDouble(z);
		}

		@Override
		public Type<SoundPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A message for Doom's top-left message line, and which keys the player
	 * holds as a bitmask (blue, yellow, red cards, then the skulls). An empty
	 * message changes only the keys.
	 */
	public record HudPayload(String message, int keys) implements CustomPacketPayload {
		public static final Type<HudPayload> TYPE = new Type<>(DoomCraft.id("hud"));
		public static final StreamCodec<FriendlyByteBuf, HudPayload> CODEC = CustomPacketPayload.codec(HudPayload::write, HudPayload::read);

		private static HudPayload read(FriendlyByteBuf buf) {
			return new HudPayload(buf.readUtf(256), buf.readVarInt());
		}

		private void write(FriendlyByteBuf buf) {
			buf.writeUtf(message, 256);
			buf.writeVarInt(keys);
		}

		@Override
		public Type<HudPayload> type() {
			return TYPE;
		}
	}

	/** Sent by a client when the player left-clicks with a Doom weapon. */
	public record FirePayload() implements CustomPacketPayload {
		public static final FirePayload INSTANCE = new FirePayload();
		public static final Type<FirePayload> TYPE = new Type<>(DoomCraft.id("fire"));
		public static final StreamCodec<FriendlyByteBuf, FirePayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<FirePayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(FirePayload.TYPE, FirePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SoundPayload.TYPE, SoundPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HudPayload.TYPE, HudPayload.CODEC);
	}
}
