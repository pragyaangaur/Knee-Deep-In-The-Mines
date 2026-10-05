package dev.pragyaan.doomcraft.client;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/**
 * Converts Doom's MUS music format into a MIDI sequence. MUS runs at 140 ticks a
 * second, which is 70 ticks per quarter note at the default 120 bpm.
 */
public final class MusToMidi {
	private static final int[] CONTROLLERS = {0, 0, 1, 7, 10, 11, 91, 93, 64, 67, 120, 123, 126, 127, 121};

	private MusToMidi() {
	}

	public static Sequence convert(byte[] mus) throws InvalidMidiDataException {
		if (mus.length < 16 || mus[0] != 'M' || mus[1] != 'U' || mus[2] != 'S' || mus[3] != 0x1A) {
			throw new InvalidMidiDataException("not a MUS lump");
		}
		int scoreStart = u16(mus, 6);
		Sequence sequence = new Sequence(Sequence.PPQ, 70);
		Track track = sequence.createTrack();
		int[] velocity = new int[16];
		java.util.Arrays.fill(velocity, 127);

		for (int ch = 0; ch < 16; ch++) {
			track.add(event(ShortMessage.CONTROL_CHANGE, ch, 7, 127, 0));
		}

		long tick = 0;
		int p = scoreStart;
		while (p < mus.length) {
			int desc = mus[p++] & 0xFF;
			int type = (desc >> 4) & 7;
			int ch = midiChannel(desc & 15);
			switch (type) {
				case 0 -> track.add(event(ShortMessage.NOTE_OFF, ch, mus[p++] & 0x7F, 0, tick));
				case 1 -> {
					int note = mus[p++] & 0xFF;
					if ((note & 0x80) != 0) {
						velocity[ch] = mus[p++] & 0x7F;
					}
					track.add(event(ShortMessage.NOTE_ON, ch, note & 0x7F, velocity[ch], tick));
				}
				case 2 -> {
					int bend = (mus[p++] & 0xFF) * 64;
					track.add(event(ShortMessage.PITCH_BEND, ch, bend & 0x7F, (bend >> 7) & 0x7F, tick));
				}
				case 3 -> {
					int sys = mus[p++] & 0xFF;
					if (sys >= 10 && sys <= 14) {
						track.add(event(ShortMessage.CONTROL_CHANGE, ch, CONTROLLERS[sys], 0, tick));
					}
				}
				case 4 -> {
					int ctrl = mus[p++] & 0xFF;
					int value = Math.min(mus[p++] & 0xFF, 127);
					if (ctrl == 0) {
						track.add(event(ShortMessage.PROGRAM_CHANGE, ch, value, 0, tick));
					} else if (ctrl < 10) {
						track.add(event(ShortMessage.CONTROL_CHANGE, ch, CONTROLLERS[ctrl], value, tick));
					}
				}
				case 5 -> {
				}
				case 6 -> p = mus.length;
				default -> p++;
			}
			if ((desc & 0x80) != 0 && p < mus.length) {
				long delay = 0;
				int b;
				do {
					b = mus[p++] & 0xFF;
					delay = delay * 128 + (b & 0x7F);
				} while ((b & 0x80) != 0 && p < mus.length);
				tick += delay;
			}
		}
		track.add(new MidiEvent(new MetaMessage(0x2F, new byte[0], 0), tick));
		return sequence;
	}

	// MUS channel 15 is percussion, which is MIDI channel 9.
	private static int midiChannel(int mus) {
		if (mus == 15) {
			return 9;
		}
		return mus >= 9 ? mus + 1 : mus;
	}

	private static MidiEvent event(int command, int channel, int a, int b, long tick) throws InvalidMidiDataException {
		return new MidiEvent(new ShortMessage(command, channel, a, b), tick);
	}

	private static int u16(byte[] b, int off) {
		return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
	}
}
