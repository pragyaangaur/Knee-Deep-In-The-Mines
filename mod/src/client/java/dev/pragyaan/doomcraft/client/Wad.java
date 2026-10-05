package dev.pragyaan.doomcraft.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reads lumps out of a WAD file. Later lumps with the same name win, as in Doom. */
final class Wad {
	private final ByteBuffer data;
	private final Map<String, int[]> lumps = new HashMap<>();

	Wad(Path path) throws IOException {
		data = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
		int count = data.getInt(4);
		int directory = data.getInt(8);
		for (int i = 0; i < count; i++) {
			int entry = directory + i * 16;
			int offset = data.getInt(entry);
			int size = data.getInt(entry + 4);
			byte[] name = new byte[8];
			data.get(entry + 8, name);
			int len = 0;
			while (len < 8 && name[len] != 0) {
				len++;
			}
			lumps.put(new String(name, 0, len, java.nio.charset.StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT), new int[] {offset, size});
		}
	}

	byte[] lump(String name) {
		int[] where = lumps.get(name.toUpperCase(Locale.ROOT));
		if (where == null) {
			return null;
		}
		byte[] out = new byte[where[1]];
		data.get(where[0], out);
		return out;
	}
}
