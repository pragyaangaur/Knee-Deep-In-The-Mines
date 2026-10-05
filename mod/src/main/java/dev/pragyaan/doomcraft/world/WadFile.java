package dev.pragyaan.doomcraft.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Reads lumps out of a WAD. Later lumps with the same name win, as in Doom.
 * Map lumps are found by position after their marker, since every map has
 * lumps called THINGS, LINEDEFS and so on.
 */
public final class WadFile {
	private static WadFile shared;

	private final ByteBuffer data;
	private final List<String> names = new ArrayList<>();
	private final List<int[]> entries = new ArrayList<>();
	private final Map<String, Integer> byName = new HashMap<>();

	public WadFile(byte[] bytes) {
		data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int count = data.getInt(4);
		int directory = data.getInt(8);
		for (int i = 0; i < count; i++) {
			int entry = directory + i * 16;
			byte[] raw = new byte[8];
			data.get(entry + 8, raw);
			int len = 0;
			while (len < 8 && raw[len] != 0) {
				len++;
			}
			String name = new String(raw, 0, len, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
			names.add(name);
			entries.add(new int[] {data.getInt(entry), data.getInt(entry + 4)});
			byName.put(name, i);
		}
	}

	/** The IWAD the whole mod uses: the player's own from config/doomcraft/wads, or the bundled shareware. */
	public static synchronized WadFile shared() {
		if (shared == null) {
			try {
				shared = new WadFile(Files.readAllBytes(locateIwad()));
			} catch (IOException e) {
				throw new IllegalStateException("Could not read a Doom IWAD", e);
			}
		}
		return shared;
	}

	public static Path locateIwad() throws IOException {
		Path wads = Files.createDirectories(FabricLoader.getInstance().getConfigDir().resolve("doomcraft").resolve("wads"));
		try (Stream<Path> files = Files.list(wads)) {
			Path chosen = files
				.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wad"))
				.filter(p -> !p.getFileName().toString().equalsIgnoreCase("doom1.wad"))
				.filter(WadFile::isIwad)
				.sorted()
				.findFirst()
				.orElse(null);
			if (chosen != null) {
				return chosen;
			}
		}
		Path shareware = wads.resolve("doom1.wad");
		try (InputStream in = WadFile.class.getResourceAsStream("/doomcraft/wads/doom1.wad")) {
			if (in == null) {
				throw new IOException("The mod jar is missing the shareware WAD");
			}
			byte[] bytes = in.readAllBytes();
			if (!Files.exists(shareware) || Files.size(shareware) != bytes.length) {
				Files.write(shareware, bytes);
			}
		}
		return shareware;
	}

	private static boolean isIwad(Path path) {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] header = in.readNBytes(4);
			return header.length == 4 && header[0] == 'I' && header[1] == 'W' && header[2] == 'A' && header[3] == 'D';
		} catch (IOException e) {
			return false;
		}
	}

	public int indexOf(String name) {
		Integer index = byName.get(name.toUpperCase(Locale.ROOT));
		return index == null ? -1 : index;
	}

	public boolean has(String name) {
		return indexOf(name) >= 0;
	}

	public int size() {
		return names.size();
	}

	public String name(int index) {
		return names.get(index);
	}

	public ByteBuffer lump(int index) {
		int[] e = entries.get(index);
		return data.slice(e[0], e[1]).order(ByteOrder.LITTLE_ENDIAN);
	}

	public ByteBuffer lump(String name) {
		int index = indexOf(name);
		return index < 0 ? null : lump(index);
	}

	public byte[] bytes(String name) {
		ByteBuffer b = lump(name);
		if (b == null) {
			return null;
		}
		byte[] out = new byte[b.remaining()];
		b.get(out);
		return out;
	}

	/** Lump names between two markers, such as F_START and F_END. */
	public List<Integer> between(String start, String end) {
		List<Integer> out = new ArrayList<>();
		int s = indexOf(start);
		int e = indexOf(end);
		if (s < 0 || e < 0) {
			return out;
		}
		for (int i = s + 1; i < e; i++) {
			if (entries.get(i)[1] > 0) {
				out.add(i);
			}
		}
		return out;
	}

	public static String name8(ByteBuffer b, int offset) {
		int len = 0;
		while (len < 8 && b.get(offset + len) != 0) {
			len++;
		}
		byte[] raw = new byte[len];
		b.get(offset, raw);
		return new String(raw, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
	}
}
