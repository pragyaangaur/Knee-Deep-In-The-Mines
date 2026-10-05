package dev.pragyaan.doomcraft.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.NativeImage;
import dev.pragyaan.doomcraft.DoomCraft;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the Doom engine as a child process and exposes its picture as a
 * Minecraft texture. The layout constants mirror engine/doomgeneric_minecraft.c.
 */
public final class DoomEngine {
	public static final Logger LOG = LoggerFactory.getLogger("doomcraft");
	public static final Identifier TEXTURE = DoomCraft.id("screen");

	public static final int WIDTH = 320;
	public static final int HEIGHT = 200;

	static final int MAGIC = 0x4D4F4F44;
	static final int OFF_MAGIC = 0;
	static final int OFF_FRAME = 12;
	static final int OFF_SFX_WRITE = 16;
	static final int OFF_MUS_SONG = 20;
	static final int OFF_MUS_LENGTH = 24;
	static final int OFF_MUS_PLAYING = 28;
	static final int OFF_MUS_LOOPING = 32;
	static final int OFF_MUS_PAUSED = 36;
	static final int OFF_MUS_VOLUME = 40;
	static final int OFF_SFX_RING = 64;
	static final int SFX_RING_SIZE = 256;
	static final int SFX_EVENT_SIZE = 32;
	static final int OFF_FRAMES = 16384;
	static final int FRAME_BYTES = WIDTH * HEIGHT * 4;
	static final int OFF_MUSIC = OFF_FRAMES + 2 * FRAME_BYTES;
	static final int MUSIC_MAX = 256 * 1024;
	static final int SHM_SIZE = OFF_MUSIC + MUSIC_MAX;

	private static final int MSG_KEY = 1;
	private static final int MSG_MOUSE = 2;
	private static final int MSG_QUIT = 3;

	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final DoomEngine INSTANCE = new DoomEngine();

	private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("doomcraft");
	private Process process;
	private OutputStream input;
	private ByteBuffer shm;
	private DoomAudio audio;
	private Wad wad;
	private DynamicTexture texture;
	private int lastFrame = -1;
	private final int[] pixels = new int[WIDTH * HEIGHT];
	private String problem;
	private long nextStartAttempt;

	public static DoomEngine get() {
		return INSTANCE;
	}

	/** Why the engine is not running, for display on the screen. Null when it is fine. */
	public String problem() {
		return problem;
	}

	public boolean isRunning() {
		return process != null && process.isAlive();
	}

	/** Starts the engine if it is not running. Retries at most once every few seconds. */
	public synchronized void ensureRunning() {
		if (isRunning()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextStartAttempt) {
			return;
		}
		nextStartAttempt = now + 3000;
		shutdownQuietly();
		try {
			start();
			problem = null;
		} catch (Exception e) {
			problem = e.getMessage();
			LOG.error("Could not start Doom", e);
			shutdownQuietly();
		}
	}

	private void start() throws IOException {
		Path engine = extractEngine();
		Path iwad = findIwad();
		Path run = Files.createDirectories(dir.resolve("run"));
		Path saves = Files.createDirectories(dir.resolve("saves"));
		Path shmPath = run.resolve("screen.shm");

		// Create and size the shared file before the engine maps it, so the
		// first frame read can never run past the end of the mapping.
		Files.deleteIfExists(shmPath);
		try (FileChannel channel = FileChannel.open(shmPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			channel.truncate(0);
			channel.write(ByteBuffer.allocate(1), SHM_SIZE - 1);
			MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, SHM_SIZE);
			mapped.order(ByteOrder.LITTLE_ENDIAN);
			shm = mapped;
		}

		ProcessBuilder builder = new ProcessBuilder(engine.toString(), "-iwad", iwad.toString())
			.directory(saves.toFile())
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("doom.log").toFile());
		builder.environment().put("DOOMCRAFT_SHM", shmPath.toString());
		process = builder.start();
		input = process.getOutputStream();
		lastFrame = -1;

		wad = new Wad(iwad);
		audio = new DoomAudio(shm, wad);
		audio.start();
		LOG.info("Doom started with {} (pid {})", iwad.getFileName(), process.pid());
	}

	private Path extractEngine() throws IOException {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if (!os.contains("mac")) {
			throw new IOException("The bundled Doom engine is built for macOS only. Build engine/ for " + os + " to play here.");
		}
		Path target = Files.createDirectories(dir.resolve("engine")).resolve("doom-macos");
		copyResource("/doomcraft/engine/doom-macos", target);
		target.toFile().setExecutable(true, true);
		return target;
	}

	/**
	 * Uses the first IWAD the player has put in config/doomcraft/wads. When there is
	 * none, the shareware DOOM1.WAD that ships with the mod is copied there.
	 */
	private Path findIwad() throws IOException {
		Path wads = Files.createDirectories(dir.resolve("wads"));
		try (Stream<Path> files = Files.list(wads)) {
			Path chosen = files
				.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wad"))
				.filter(p -> !p.getFileName().toString().equalsIgnoreCase("doom1.wad"))
				.filter(DoomEngine::isIwad)
				.sorted()
				.findFirst()
				.orElse(null);
			if (chosen != null) {
				return chosen;
			}
		}
		Path shareware = wads.resolve("doom1.wad");
		copyResource("/doomcraft/wads/doom1.wad", shareware);
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

	private static void copyResource(String resource, Path target) throws IOException {
		try (InputStream in = DoomEngine.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("The mod jar is missing " + resource);
			}
			byte[] data = in.readAllBytes();
			if (Files.exists(target) && Files.size(target) == data.length && java.util.Arrays.equals(Files.readAllBytes(target), data)) {
				return;
			}
			Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.write(tmp, data);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Copies the newest finished frame into the texture. Call on the render thread. */
	public void updateTexture() {
		if (texture == null) {
			texture = new DynamicTexture(() -> "Doom screen", WIDTH, HEIGHT, true);
			Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
		}
		ByteBuffer buffer = shm;
		if (buffer == null || (int) INT.getAcquire(buffer, OFF_MAGIC) != MAGIC) {
			return;
		}
		int frame = (int) INT.getAcquire(buffer, OFF_FRAME);
		if (frame == lastFrame) {
			return;
		}
		lastFrame = frame;
		buffer.slice(OFF_FRAMES + (frame & 1) * FRAME_BYTES, FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);

		NativeImage image = texture.getPixels();
		for (int y = 0, i = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++, i++) {
				image.setPixel(x, y, pixels[i] | 0xFF000000);
			}
		}
		texture.upload();
	}

	public void setListenerVolume(float volume) {
		DoomAudio a = audio;
		if (a != null) {
			a.setListenerVolume(volume);
		}
	}

	public void sendKey(int doomKey, boolean pressed) {
		send(MSG_KEY, pressed ? 1 : 0, doomKey & 0xFF, 0);
	}

	public void sendMouse(int buttons, int dx) {
		send(MSG_MOUSE, buttons, dx, 0);
	}

	private synchronized void send(int type, int a, int b, int c) {
		if (!isRunning()) {
			return;
		}
		try {
			input.write(new byte[] {(byte) type, (byte) a, (byte) b, (byte) c});
			input.flush();
		} catch (IOException e) {
			// The engine has exited. The screen notices and offers a restart.
		}
	}

	/** Ends the engine and stops its audio. Safe to call more than once. */
	public synchronized void stop() {
		if (isRunning()) {
			send(MSG_QUIT, 0, 0, 0);
		}
		shutdownQuietly();
		nextStartAttempt = 0;
	}

	private void shutdownQuietly() {
		if (audio != null) {
			audio.shutdown();
			audio = null;
		}
		if (process != null) {
			try {
				input.close();
			} catch (IOException ignored) {
			}
			Process p = process;
			p.onExit().orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).exceptionally(t -> {
				p.destroyForcibly();
				return p;
			});
			process = null;
		}
		wad = null;
		shm = null;
	}
}
