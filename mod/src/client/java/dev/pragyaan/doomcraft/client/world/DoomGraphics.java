package dev.pragyaan.doomcraft.client.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.pragyaan.doomcraft.DoomCraft;
import dev.pragyaan.doomcraft.world.WadFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Turns the WAD's pictures into Minecraft textures. Wall textures are
 * composited from patches as Doom does, flats are raw 64x64 images, and
 * sprites keep their offsets so they stand on the floor where Doom puts them.
 * Everything is packed into two atlases: one for the level, one for sprites.
 */
public final class DoomGraphics {
	public record Picture(int width, int height, int leftOffset, int topOffset, int[] argb) {
	}

	/** Where a picture sits in an atlas, in pixels. */
	public record Region(int x, int y, int width, int height, int leftOffset, int topOffset) {
	}

	/** One sprite frame as seen from a given rotation, with whether Doom draws it mirrored. */
	public record SpriteView(String lump, boolean flipped) {
	}

	private static DoomGraphics instance;

	private final WadFile wad;
	private final int[] palette = new int[256];
	private final Map<String, Integer> patchIndex = new HashMap<>();
	private final List<String> patchNames = new ArrayList<>();
	private final Map<String, ByteBuffer> textureDefs = new LinkedHashMap<>();
	private final Map<String, Integer> flatLumps = new HashMap<>();
	// sprite name -> frame letter -> 9 views (0 = all angles, 1-8 = rotations)
	private final Map<String, Map<Character, SpriteView[]>> sprites = new HashMap<>();

	private Atlas spriteAtlas;

	public static synchronized DoomGraphics get() {
		if (instance == null) {
			instance = new DoomGraphics(WadFile.shared());
		}
		return instance;
	}

	private DoomGraphics(WadFile wad) {
		this.wad = wad;
		ByteBuffer pal = wad.lump("PLAYPAL");
		for (int i = 0; i < 256; i++) {
			int r = pal.get(i * 3) & 0xFF;
			int g = pal.get(i * 3 + 1) & 0xFF;
			int b = pal.get(i * 3 + 2) & 0xFF;
			palette[i] = 0xFF000000 | r << 16 | g << 8 | b;
		}

		ByteBuffer pnames = wad.lump("PNAMES");
		int count = pnames.getInt(0);
		for (int i = 0; i < count; i++) {
			patchNames.add(WadFile.name8(pnames, 4 + i * 8));
		}
		for (String lump : new String[] {"TEXTURE1", "TEXTURE2"}) {
			ByteBuffer t = wad.lump(lump);
			if (t == null) {
				continue;
			}
			int n = t.getInt(0);
			for (int i = 0; i < n; i++) {
				int offset = t.getInt(4 + i * 4);
				textureDefs.put(WadFile.name8(t, offset), t.slice(offset, t.limit() - offset).order(ByteOrder.LITTLE_ENDIAN));
			}
		}
		for (int i : wad.between("F_START", "F_END")) {
			flatLumps.put(wad.name(i), i);
		}
		for (int i : wad.between("FF_START", "FF_END")) {
			flatLumps.put(wad.name(i), i);
		}
		indexSprites();
	}

	private void indexSprites() {
		List<Integer> lumps = wad.between("S_START", "S_END");
		lumps.addAll(wad.between("SS_START", "SS_END"));
		for (int i : lumps) {
			String name = wad.name(i);
			if (name.length() < 6) {
				continue;
			}
			addView(name, name.substring(0, 4), name.charAt(4), name.charAt(5) - '0', false);
			if (name.length() >= 8) {
				addView(name, name.substring(0, 4), name.charAt(6), name.charAt(7) - '0', true);
			}
		}
	}

	private void addView(String lump, String sprite, char frame, int rotation, boolean flipped) {
		if (rotation < 0 || rotation > 8) {
			return;
		}
		SpriteView[] views = sprites.computeIfAbsent(sprite, k -> new HashMap<>()).computeIfAbsent(frame, k -> new SpriteView[9]);
		views[rotation] = new SpriteView(lump, flipped);
	}

	public boolean hasSprite(String sprite) {
		return sprites.containsKey(sprite);
	}

	/** The view of a sprite frame from a rotation 1-8, falling back to the all-angles view. */
	public SpriteView view(String sprite, char frame, int rotation) {
		Map<Character, SpriteView[]> frames = sprites.get(sprite);
		if (frames == null) {
			return null;
		}
		SpriteView[] views = frames.get(frame);
		if (views == null) {
			views = frames.values().iterator().next();
		}
		if (views[0] != null) {
			return views[0];
		}
		SpriteView v = views[Math.max(1, Math.min(8, rotation))];
		if (v != null) {
			return v;
		}
		for (SpriteView any : views) {
			if (any != null) {
				return any;
			}
		}
		return null;
	}

	// ------------------------------------------------------------ pictures

	/** Decodes a patch-format picture (columns of posts). */
	public Picture picture(String lump) {
		ByteBuffer b = wad.lump(lump);
		if (b == null || b.limit() < 8) {
			return null;
		}
		int w = b.getShort(0) & 0xFFFF;
		int h = b.getShort(2) & 0xFFFF;
		int[] argb = new int[w * h];
		for (int x = 0; x < w; x++) {
			int p = b.getInt(8 + x * 4);
			while (p < b.limit()) {
				int top = b.get(p) & 0xFF;
				if (top == 0xFF) {
					break;
				}
				int len = b.get(p + 1) & 0xFF;
				for (int i = 0; i < len; i++) {
					int y = top + i;
					if (y < h && p + 3 + i < b.limit()) {
						argb[y * w + x] = palette[b.get(p + 3 + i) & 0xFF];
					}
				}
				p += len + 4;
			}
		}
		return new Picture(w, h, b.getShort(4), b.getShort(6), argb);
	}

	public boolean hasTexture(String name) {
		return textureDefs.containsKey(name);
	}

	/** Composites a wall texture from its patches, the way R_GenerateComposite does. */
	public Picture texture(String name) {
		ByteBuffer t = textureDefs.get(name);
		if (t == null) {
			return null;
		}
		int w = t.getShort(12) & 0xFFFF;
		int h = t.getShort(14) & 0xFFFF;
		int patches = t.getShort(20) & 0xFFFF;
		int[] argb = new int[w * h];
		for (int i = 0; i < patches; i++) {
			int base = 22 + i * 10;
			int ox = t.getShort(base);
			int oy = t.getShort(base + 2);
			int pi = t.getShort(base + 4) & 0xFFFF;
			if (pi >= patchNames.size()) {
				continue;
			}
			Picture patch = picture(patchNames.get(pi));
			if (patch == null) {
				continue;
			}
			for (int y = 0; y < patch.height; y++) {
				int ty = oy + y;
				if (ty < 0 || ty >= h) {
					continue;
				}
				for (int x = 0; x < patch.width; x++) {
					int tx = ox + x;
					int c = patch.argb[y * patch.width + x];
					if (tx >= 0 && tx < w && c != 0) {
						argb[ty * w + tx] = c;
					}
				}
			}
		}
		return new Picture(w, h, 0, 0, argb);
	}

	public boolean hasFlat(String name) {
		return flatLumps.containsKey(name);
	}

	public Picture flat(String name) {
		Integer index = flatLumps.get(name);
		if (index == null) {
			return null;
		}
		ByteBuffer b = wad.lump(index);
		int[] argb = new int[64 * 64];
		for (int i = 0; i < 64 * 64 && i < b.limit(); i++) {
			argb[i] = palette[b.get(i) & 0xFF];
		}
		return new Picture(64, 64, 0, 0, argb);
	}

	// ------------------------------------------------------------ atlases

	/** A texture packed from many pictures with a simple shelf packer. */
	public static final class Atlas {
		public final Identifier id;
		public final int width;
		public final int height;
		private final Map<String, Region> regions = new HashMap<>();

		Atlas(String name, Map<String, Picture> pictures) {
			List<Map.Entry<String, Picture>> list = new ArrayList<>(pictures.entrySet());
			list.sort((a, b) -> b.getValue().height - a.getValue().height);
			int size = 512;
			while (!fits(list, size, size)) {
				size *= 2;
			}
			width = size;
			height = size;
			NativeImage image = new NativeImage(width, height, true);
			int x = 0, y = 0, shelf = 0;
			for (Map.Entry<String, Picture> e : list) {
				Picture p = e.getValue();
				if (x + p.width + 1 > width) {
					x = 0;
					y += shelf + 1;
					shelf = 0;
				}
				for (int py = 0; py < p.height; py++) {
					for (int px = 0; px < p.width; px++) {
						image.setPixel(x + px, y + py, p.argb[py * p.width + px]);
					}
				}
				regions.put(e.getKey(), new Region(x, y, p.width, p.height, p.leftOffset, p.topOffset));
				x += p.width + 1;
				shelf = Math.max(shelf, p.height);
			}
			id = DoomCraft.id("atlas/" + name);
			DynamicTexture texture = new DynamicTexture(() -> "Doom " + name, image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
		}

		private static boolean fits(List<Map.Entry<String, Picture>> list, int w, int h) {
			int x = 0, y = 0, shelf = 0;
			for (Map.Entry<String, Picture> e : list) {
				Picture p = e.getValue();
				if (p.width + 1 > w) {
					return false;
				}
				if (x + p.width + 1 > w) {
					x = 0;
					y += shelf + 1;
					shelf = 0;
				}
				x += p.width + 1;
				shelf = Math.max(shelf, p.height);
			}
			return y + shelf <= h;
		}

		public Region region(String name) {
			return regions.get(name);
		}

		public void close() {
			Minecraft.getInstance().getTextureManager().release(id);
		}
	}

	/** Every sprite lump in the WAD, in one atlas. Built once, on the render thread. */
	public Atlas sprites() {
		if (spriteAtlas == null) {
			Map<String, Picture> pictures = new LinkedHashMap<>();
			for (Map<Character, SpriteView[]> frames : sprites.values()) {
				for (SpriteView[] views : frames.values()) {
					for (SpriteView v : views) {
						if (v != null && !pictures.containsKey(v.lump())) {
							Picture p = picture(v.lump());
							if (p != null) {
								pictures.put(v.lump(), p);
							}
						}
					}
				}
			}
			// First-person weapon sprites live outside S_START, among the other graphics.
			for (int i = 0; i < wad.size(); i++) {
				String name = wad.name(i);
				if (name.length() >= 5 && isHudGraphic(name) && !pictures.containsKey(name)) {
					Picture p = picture(name);
					if (p != null) {
						pictures.put(name, p);
					}
				}
			}
			spriteAtlas = new Atlas("sprites", pictures);
		}
		return spriteAtlas;
	}

	/** Weapon sprites and status bar graphics (faces, digits, keys, font) live outside S_START. */
	private static boolean isHudGraphic(String name) {
		return switch (name.substring(0, 4)) {
			case "PISG", "PISF", "SHTG", "SHTF", "CHGG", "CHGF", "MISG", "MISF", "SAWG", "PLSG", "PLSF", "PUNG", "STTN", "STKE", "STCF" -> true;
			default -> name.startsWith("STFST") || name.startsWith("STFTL") || name.startsWith("STFTR") || name.startsWith("STFOUCH")
				|| name.startsWith("STFEVL") || name.startsWith("STFKILL") || name.equals("STFGOD0") || name.equals("STFDEAD0");
		};
	}

	/** An atlas holding the wall textures and flats one map uses. */
	public Atlas levelAtlas(String mapName, java.util.Collection<String> textures, java.util.Collection<String> flats) {
		Map<String, Picture> pictures = new LinkedHashMap<>();
		for (String t : textures) {
			Picture p = texture(t);
			if (p != null) {
				pictures.put("T:" + t, p);
			}
		}
		for (String f : flats) {
			Picture p = flat(f);
			if (p != null) {
				pictures.put("F:" + f, p);
			}
		}
		return new Atlas("level_" + mapName.toLowerCase(java.util.Locale.ROOT), pictures);
	}
}
