package dev.pragyaan.doomcraft.client;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays Doom's sound. The engine only reports which effect started on which
 * channel, and which song is on, so the samples and music are read from the
 * same WAD here. Effects are mixed in software into one output line. Music
 * goes through Java's built-in General MIDI synthesizer.
 */
final class DoomAudio implements Runnable {
	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final int RATE = 22050;
	private static final int CHUNK = 256;
	private static final int CHANNELS = 16;

	private final ByteBuffer shm;
	private final Wad wad;
	private final Thread thread;
	private volatile boolean running = true;
	private volatile float listenerVolume;

	private final Map<String, Sample> samples = new HashMap<>();
	private final Voice[] voices = new Voice[CHANNELS];
	private int sfxRead = -1;

	private Sequencer sequencer;
	private Synthesizer synth;
	private VolumeReceiver musicOut;
	private int songId;
	private boolean musicOn;
	private boolean musicFailed;

	private record Sample(byte[] pcm, int rate) {
	}

	private static final class Voice {
		Sample sample;
		double pos;
		float left;
		float right;
	}

	DoomAudio(ByteBuffer shm, Wad wad) {
		this.shm = shm;
		this.wad = wad;
		for (int i = 0; i < CHANNELS; i++) {
			voices[i] = new Voice();
		}
		thread = new Thread(this, "Doom audio");
		thread.setDaemon(true);
	}

	void start() {
		thread.start();
	}

	void shutdown() {
		running = false;
		thread.interrupt();
	}

	void setListenerVolume(float volume) {
		listenerVolume = volume;
	}

	@Override
	public void run() {
		SourceDataLine line = null;
		try {
			AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
			line = AudioSystem.getSourceDataLine(format);
			line.open(format, CHUNK * 4 * 8);
			line.start();
		} catch (Exception e) {
			DoomEngine.LOG.warn("No audio output for Doom sound effects", e);
			line = null;
		}

		byte[] out = new byte[CHUNK * 4];
		float[] mixL = new float[CHUNK];
		float[] mixR = new float[CHUNK];
		while (running) {
			try {
				pollSoundEvents();
				pollMusic();
			} catch (Exception e) {
				DoomEngine.LOG.warn("Doom audio event failed", e);
			}

			if (line == null) {
				sleep(10);
				continue;
			}
			mix(mixL, mixR, out);
			line.write(out, 0, out.length);
		}

		if (line != null) {
			line.stop();
			line.close();
		}
		if (sequencer != null) {
			sequencer.close();
		}
		if (synth != null) {
			synth.close();
		}
	}

	private void mix(float[] mixL, float[] mixR, byte[] out) {
		java.util.Arrays.fill(mixL, 0);
		java.util.Arrays.fill(mixR, 0);
		float gain = listenerVolume * 0.6f;
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
				mixL[i] += value * v.left;
				mixR[i] += value * v.right;
				v.pos += step;
			}
		}
		for (int i = 0; i < CHUNK; i++) {
			int l = (int) (Math.max(-1f, Math.min(1f, mixL[i] * gain)) * 32767);
			int r = (int) (Math.max(-1f, Math.min(1f, mixR[i] * gain)) * 32767);
			out[i * 4] = (byte) l;
			out[i * 4 + 1] = (byte) (l >> 8);
			out[i * 4 + 2] = (byte) r;
			out[i * 4 + 3] = (byte) (r >> 8);
		}
	}

	private void pollSoundEvents() {
		int write = (int) INT.getAcquire(shm, DoomEngine.OFF_SFX_WRITE);
		if (sfxRead < 0 || write - sfxRead > DoomEngine.SFX_RING_SIZE) {
			sfxRead = write;
		}
		for (; sfxRead != write; sfxRead++) {
			int base = DoomEngine.OFF_SFX_RING + Math.floorMod(sfxRead, DoomEngine.SFX_RING_SIZE) * DoomEngine.SFX_EVENT_SIZE;
			int type = shm.getInt(base);
			int channel = shm.getInt(base + 4);
			int vol = shm.getInt(base + 8);
			int sep = shm.getInt(base + 12);
			if (channel < 0 || channel >= CHANNELS) {
				continue;
			}
			Voice v = voices[channel];
			switch (type) {
				case 1 -> {
					byte[] name = new byte[16];
					shm.get(base + 16, name);
					int len = 0;
					while (len < 16 && name[len] != 0) {
						len++;
					}
					v.sample = sample(new String(name, 0, len, StandardCharsets.US_ASCII));
					v.pos = 0;
					setPan(v, vol, sep);
				}
				case 2 -> v.sample = null;
				case 3 -> setPan(v, vol, sep);
				default -> {
				}
			}
		}
	}

	private static void setPan(Voice v, int vol, int sep) {
		float volume = Math.max(0, Math.min(127, vol)) / 127f;
		float pan = Math.max(0, Math.min(254, sep)) / 254f;
		v.left = volume * Math.min(1f, (1f - pan) * 2f);
		v.right = volume * Math.min(1f, pan * 2f);
	}

	// DMX format: u16 format (3), u16 rate, u32 count, then 8-bit unsigned PCM
	// with 16 bytes of padding at each end.
	private Sample sample(String name) {
		return samples.computeIfAbsent(name, n -> {
			byte[] lump = wad.lump(n);
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

	private void pollMusic() throws Exception {
		if (musicFailed) {
			return;
		}
		int id = (int) INT.getAcquire(shm, DoomEngine.OFF_MUS_SONG);
		boolean playing = (int) INT.getAcquire(shm, DoomEngine.OFF_MUS_PLAYING) != 0;
		boolean paused = (int) INT.getAcquire(shm, DoomEngine.OFF_MUS_PAUSED) != 0;
		boolean looping = (int) INT.getAcquire(shm, DoomEngine.OFF_MUS_LOOPING) != 0;
		int doomVolume = (int) INT.getAcquire(shm, DoomEngine.OFF_MUS_VOLUME);

		if (id != songId && id != 0) {
			songId = id;
			int len = Math.min((int) INT.getAcquire(shm, DoomEngine.OFF_MUS_LENGTH), DoomEngine.MUSIC_MAX);
			byte[] data = new byte[len];
			shm.get(DoomEngine.OFF_MUSIC, data);
			loadSong(data);
			if (musicFailed) {
				return;
			}
			musicOn = false;
		}
		if (sequencer == null) {
			return;
		}

		sequencer.setLoopCount(looping ? Sequencer.LOOP_CONTINUOUSLY : 0);
		boolean want = playing && !paused;
		if (want && !musicOn) {
			sequencer.start();
			musicOn = true;
		} else if (!want && musicOn) {
			sequencer.stop();
			musicOn = false;
			musicOut.silence();
		}
		musicOut.setGain(listenerVolume * Math.max(0, Math.min(127, doomVolume)) / 127f);
	}

	private void loadSong(byte[] data) {
		try {
			if (sequencer == null) {
				synth = MidiSystem.getSynthesizer();
				synth.open();
				sequencer = MidiSystem.getSequencer(false);
				sequencer.open();
				musicOut = new VolumeReceiver(synth.getReceiver());
				sequencer.getTransmitter().setReceiver(musicOut);
			}
			sequencer.stop();
			musicOut.silence();
			Sequence sequence = data.length > 4 && data[0] == 'M' && data[1] == 'T'
				? MidiSystem.getSequence(new java.io.ByteArrayInputStream(data))
				: MusToMidi.convert(data);
			sequencer.setSequence(sequence);
			sequencer.setTickPosition(0);
		} catch (Exception e) {
			DoomEngine.LOG.warn("Doom music is unavailable", e);
			musicFailed = true;
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
		}
	}

	/** Scales every channel volume message so music follows distance and Minecraft's volume. */
	private static final class VolumeReceiver implements Receiver {
		private final Receiver target;
		private final int[] channelVolume = new int[16];
		private float gain = -1;

		VolumeReceiver(Receiver target) {
			this.target = target;
			java.util.Arrays.fill(channelVolume, 100);
		}

		@Override
		public synchronized void send(MidiMessage message, long timeStamp) {
			if (message instanceof ShortMessage sm && sm.getCommand() == ShortMessage.CONTROL_CHANGE && sm.getData1() == 7) {
				channelVolume[sm.getChannel()] = sm.getData2();
				sendVolume(sm.getChannel());
				return;
			}
			target.send(message, -1);
		}

		synchronized void setGain(float newGain) {
			if (Math.abs(newGain - gain) < 0.01f) {
				return;
			}
			gain = newGain;
			for (int ch = 0; ch < 16; ch++) {
				sendVolume(ch);
			}
		}

		synchronized void silence() {
			for (int ch = 0; ch < 16; ch++) {
				try {
					target.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0), -1);
				} catch (Exception ignored) {
				}
			}
		}

		private void sendVolume(int ch) {
			try {
				int value = Math.round(channelVolume[ch] * Math.max(0, gain));
				target.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 7, Math.min(127, value)), -1);
			} catch (Exception ignored) {
			}
		}

		@Override
		public void close() {
		}
	}
}
