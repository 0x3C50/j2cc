package me.x150.j2cc.output;

import lombok.SneakyThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DirectoryOutputSink implements OutputSink {

	private final Path rootPath;

	public DirectoryOutputSink(Path rootPath) {
		this.rootPath = rootPath;
	}

	@Override
	public OutputStream openFile(String name) throws IOException {
		Path resolve = rootPath.resolve(name);
		Path parent = resolve.getParent();
		Files.createDirectories(parent);
		return Files.newOutputStream(resolve);
	}

	@Override
	public void close() {
		// noop
	}

	@Override
	public String toString() {
		return String.format(
				"%s{rootPath=%s}", getClass().getSimpleName(), this.rootPath);
	}
}
