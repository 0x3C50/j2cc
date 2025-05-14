package j2cc.internal;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class is a template for the actual loader initializer. It serves no functionality in the original jar
 */
public class Loader {
	private static final AtomicBoolean loaded = new AtomicBoolean(false);

	@Debug
	private static void printDebug1(String a, String b) {
		System.out.printf(a + "%n", b);
	}

	public static void init() {
		// v.cAS(a, b): if v == a { v := b; return true; } else { return false; }
		if (!loaded.compareAndSet(false, true)) return;
		String resourcePrefix = Platform.RESOURCE_PREFIX;
		printDebug1("j2cc loader v1.0.1%nPlatform: %s", resourcePrefix);
		ClassLoader cl = Loader.class.getClassLoader();
		try (InputStream resourceAsStream = cl.getResourceAsStream("j2cc/relocationInfo.dat");
			 DataInputStream dis = new DataInputStream(Objects.requireNonNull(resourceAsStream));
			 InputStream natives = Objects.requireNonNull(cl.getResourceAsStream("j2cc/natives.bin"))) {
			while (!dis.readUTF().equals(resourcePrefix)) {
				dis.skipBytes(16+48);
			}
			long pos = dis.readLong();
			long fileLength = dis.readLong();
			byte[] k = new byte[48];
			dis.readFully(k);
			long total = 0;
			long cur;
			while (total < pos && (cur = (int) natives.skip(pos - total)) > 0) {
				total += cur;
			}
			if (total != pos) throw new IOException("Failed to read natives file");
			Path tempFile = Files.createTempFile("j2cc", Platform.osType == Platform.WINDOWS ? ".dll" : "");
			tempFile.toFile().deleteOnExit();
			try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
				long totalRead = 0;
				final int maxSize = 1024 * 8;
				byte[] buffer = new byte[maxSize];
				while (totalRead < fileLength) {
					long rem = fileLength - totalRead;
					int size = (int) Math.min(maxSize, rem);
					int actuallyRead = natives.read(buffer, 0, size);
					outputStream.write(buffer, 0, actuallyRead);
					totalRead += actuallyRead;
				}
			}

			String string = tempFile.toString();
			printDebug1("Loading library from %s...", string);
			System.load(string);
			bootstrap(k);
		} catch (EOFException ee) {
			System.err.println("Your platform (" + resourcePrefix + ") isn't supported by this application. Please contact the developers to find out more.");
			System.exit(127);
		} catch (IOException e) {
			throw new Error(e);
		}
	}

	private static native void bootstrap(byte[] k);

	private static native void initClass(Class<?> cl);

	public static void doInit(Class<?> cl) {
		init();
		initClass(cl);
	}
}
