package me.x150.j2cc.output;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

public class FsOutputSink implements OutputSink {
	private final FileSystem fs;

	public FsOutputSink(FileSystem fs) {
		this.fs = fs;
	}

	@Override
	public OutputStream openFile(String name) throws IOException {
		Path path = fs.getPath(name);
		Path pa = path.getParent();
		if (pa != null) Files.createDirectories(pa);
		return Files.newOutputStream(path);
	}

	@Override
	public void close() {

	}
}
