package me.x150.j2cc.output;

import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class JarOutputSink implements OutputSink {

	private final ZipOutputStream file;
	private final Path jarPath;

	@SneakyThrows
	public JarOutputSink(Path jarPath) {
		file = new ZipOutputStream(Files.newOutputStream(jarPath));
		this.jarPath = jarPath;
	}

	@Override
	public OutputStream openFile(String name) throws IOException {
		file.putNextEntry(new ZipEntry(name));
		return new EntryStream(file);
	}

	@Override
	public void close() throws Exception {
		file.close();
	}

	@Override
	public String toString() {
		return String.format(
				"%s{file=%s}", getClass().getSimpleName(), this.jarPath);
	}

	private static class EntryStream extends OutputStream {
		private final ZipOutputStream zos;

		public EntryStream(ZipOutputStream zos) {
			this.zos = zos;
		}

		@Override
		public void write(int b) throws IOException {
			zos.write(b);
		}

		@Override
		public void write(byte @NotNull [] b) throws IOException {
			zos.write(b);
		}

		@Override
		public void write(byte @NotNull [] b, int off, int len) throws IOException {
			zos.write(b, off, len);
		}

		@Override
		public void close() throws IOException {
			zos.closeEntry();
			super.close();
		}
	}
}
