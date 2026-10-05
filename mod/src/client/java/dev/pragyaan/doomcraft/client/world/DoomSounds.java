package dev.pragyaan.doomcraft.client.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import dev.pragyaan.doomcraft.world.WadFile;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Plays Doom's sound effects from the WAD at points in the Minecraft world.
 * Volume falls off over Doom's distances (full within 200 units, silent at
 * 1200) and pans with where the player is facing. Mixed in software because
 * Minecraft's sound system only plays Ogg files from resource packs.
 */
public final class DoomSounds implements Runnable {
	private static final int RATE = 22050;
	private static final int CHUNK = 256;
	private static final int VOICES = 24;
	private static DoomSounds instance;

	private final ConcurrentLinkedQueue<float[]> requests = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<String> names = new ConcurrentLinkedQueue<>();
	private final Map<String, Sample> cache = new HashMap<>();
	private final Voice[] voices = new Voice[VOICES];
	private int nextVoice;

	private record Sample(byte[] pcm, int rate) {
	}

	private static final class Voice {
		Sample sample;
		double pos;
		float left;
		float right;
	}

	public static synchronized DoomSounds get() {
		if (instance == null) {
			instance = new DoomSounds();
			Thread t = new Thread(instance, "Doom sound effects");
			t.setDaemon(true);
			t.start();
		}
		return instance;
	}

	private DoomSounds() {
		for (int i = 0; i < VOICES; i++) {
			voices[i] = new Voice();
		}
	}

	/** Called on the client thread. Works out volume and pan now, from the camera. */
	public void play(String sound, Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.options == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		float yaw = mc.gameRenderer.mainCamera().yRot();
		double units = cam.distanceTo(at) * 32;
		if (units >= 1200) {
			return;
		}
		float volume = units < 200 ? 1f : (float) ((1200 - units) / 1000);
		volume *= mc.options.getFinalSoundSourceVolume(SoundSource.HOSTILE);
		if (volume <= 0.001f) {
			return;
		}
		double rad = Math.toRadians(yaw);
		Vec3 right = new Vec3(-Math.cos(rad), 0, -Math.sin(rad));
		Vec3 dir = at.subtract(cam);
		double pan = dir.lengthSqr() < 1e-6 ? 0 : dir.normalize().dot(right);
		float left = (float) (volume * (1 - Math.max(0, pan) * 0.75));
		float rightGain = (float) (volume * (1 + Math.min(0, pan) * 0.75));
		names.add(sound);
		requests.add(new float[] {left, rightGain});
	}

	@Override
	public void run() {
		SourceDataLine line;
		try {
			AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
			line = AudioSystem.getSourceDataLine(format);
			line.open(format, CHUNK * 4 * 8);
			line.start();
		} catch (Exception e) {
			return;
		}
		byte[] out = new byte[CHUNK * 4];
		float[] l = new float[CHUNK];
		float[] r = new float[CHUNK];
		while (true) {
			float[] req;
			while ((req = requests.poll()) != null) {
				String name = names.poll();
				Sample s = name == null ? null : sample(name);
				if (s != null) {
					Voice v = voices[nextVoice];
					nextVoice = (nextVoice + 1) % VOICES;
					v.sample = s;
					v.pos = 0;
					v.left = req[0];
					v.right = req[1];
				}
			}
			java.util.Arrays.fill(l, 0);
			java.util.Arrays.fill(r, 0);
			for (Voice v : voices) {
				Sample s = v.sample;
				if (s == null) {
					continue;
				}
				double step = (double) s.rate / RATE;
				for (int i = 0; i < CHUNK; i++) {
					int idx = (int) v.pos;
					if (idx >= s.pcm.length) {
						v.sample = null;
						break;
					}
					float value = ((s.pcm[idx] & 0xFF) - 128) / 128f;
					l[i] += value * v.left;
					r[i] += value * v.right;
					v.pos += step;
				}
			}
			for (int i = 0; i < CHUNK; i++) {
				int a = (int) (Math.max(-1f, Math.min(1f, l[i] * 0.5f)) * 32767);
				int b = (int) (Math.max(-1f, Math.min(1f, r[i] * 0.5f)) * 32767);
				out[i * 4] = (byte) a;
				out[i * 4 + 1] = (byte) (a >> 8);
				out[i * 4 + 2] = (byte) b;
				out[i * 4 + 3] = (byte) (b >> 8);
			}
			line.write(out, 0, out.length);
		}
	}

	private Sample sample(String name) {
		return cache.computeIfAbsent(name, n -> {
			byte[] lump = WadFile.shared().bytes(n);
			if (lump == null || lump.length < 8 || lump[0] != 3 || lump[1] != 0) {
				return null;
			}
			int rate = (lump[2] & 0xFF) | (lump[3] & 0xFF) << 8;
			int count = Math.min(ByteBuffer.wrap(lump, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(), lump.length - 8);
			int start = 8;
			if (count > 32) {
				start += 16;
				count -= 32;
			}
			byte[] pcm = new byte[Math.max(0, count)];
			System.arraycopy(lump, start, pcm, 0, pcm.length);
			return new Sample(pcm, rate == 0 ? 11025 : rate);
		});
	}
}
