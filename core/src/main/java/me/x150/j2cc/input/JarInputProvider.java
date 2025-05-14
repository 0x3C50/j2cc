package me.x150.j2cc.input;

import me.x150.j2cc.tree.resolver.FsResolver;
import me.x150.j2cc.tree.resolver.Resolver;

import java.nio.file.FileSystem;
import java.nio.file.Path;

public record JarInputProvider(FileSystem fs) implements InputProvider {
	@Override
	public Path getFile(String path) {
		return fs.getPath(path);
	}

	@Override
	public Resolver toResolver() {
		return new FsResolver(fs);
	}

	@Override
	public void close() {

	}

	@Override
	public String toString() {
		return String.format(
				"%s{fs=%s}", getClass().getSimpleName(), this.fs);
	}
}
