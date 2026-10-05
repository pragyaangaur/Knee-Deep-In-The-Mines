package dev.pragyaan.doomcraft.client.world;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;

import dev.pragyaan.doomcraft.client.MusToMidi;
import dev.pragyaan.doomcraft.world.WadFile;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/** Plays the current map's music (D_E1M1 and so on) through Java's General MIDI synthesizer. */
public final class DoomMusic {
	private static final String[] DOOM2 = {"RUNNIN", "STALKS", "COUNTD", "BETWEE", "DOOM", "THE_DA", "SHAWN", "DDTBLU", "IN_CIT",
		"DEAD", "STLKS2", "THEDA2", "DOOM2", "DDTBL2", "RUNNI2", "DEAD2", "STLKS3", "ROMERO", "SHAWN2", "MESSAG", "COUNT2",
		"DDTBL3", "AMPIE", "THEDA3", "ADRIAN", "MESSG2", "ROMER2", "TENSE", "SHAWN3", "OPENIN", "EVIL", "ULTIMA"};

	private Sequencer sequencer;
	private Synthesizer synth;
	private Receiver out;
	private String playing;
	private float volume = -1;

	public static String lumpFor(String map) {
		if (map.startsWith("MAP")) {
			int n = Integer.parseInt(map.substring(3));
			return n >= 1 && n <= DOOM2.length ? "D_" + DOOM2[n - 1] : null;
		}
		return "D_" + map;
	}

	/** Plays this map's song, or stops when {@code map} is null. Call every tick. */
	public void update(String map) {
		String lump = map == null ? null : lumpFor(map);
		if (lump == null || !lump.equals(playing)) {
			stop();
			if (lump != null) {
				start(lump);
			}
		}
		if (sequencer != null) {
			Minecraft mc = Minecraft.getInstance();
			float v = mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC);
			if (Math.abs(v - volume) > 0.01f) {
				volume = v;
				for (int ch = 0; ch < 16; ch++) {
					try {
						// Expression (CC 11) scales on top of the song's own channel volumes.
						out.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 11, Math.round(v * 127)), -1);
					} catch (Exception ignored) {
					}
				}
			}
		}
	}

	private void start(String lump) {
		playing = lump;
		byte[] data = WadFile.shared().bytes(lump);
		if (data == null) {
			return;
		}
		try {
			if (synth == null) {
				synth = MidiSystem.getSynthesizer();
				synth.open();
				sequencer = MidiSystem.getSequencer(false);
				sequencer.open();
				out = synth.getReceiver();
				sequencer.getTransmitter().setReceiver(out);
			}
			sequencer.setSequence(data[0] == 'M' && data[1] == 'T' ? MidiSystem.getSequence(new java.io.ByteArrayInputStream(data))
				: MusToMidi.convert(data));
			sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
			sequencer.setTickPosition(0);
			sequencer.start();
			volume = -1;
		} catch (Exception e) {
			dev.pragyaan.doomcraft.client.DoomEngine.LOG.warn("Doom music is unavailable", e);
		}
	}

	/** Releases the sequencer and synthesizer. The sequencer's thread would otherwise keep the game from exiting. */
	public void close() {
		stop();
		if (sequencer != null) {
			sequencer.close();
			sequencer = null;
		}
		if (synth != null) {
			synth.close();
			synth = null;
		}
	}

	public void stop() {
		playing = null;
		if (sequencer != null && sequencer.isOpen()) {
			sequencer.stop();
			for (int ch = 0; ch < 16; ch++) {
				try {
					out.send(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 123, 0), -1);
				} catch (Exception ignored) {
				}
			}
		}
	}
}
