package me.x150.j2cc.input;

import me.x150.j2cc.tree.resolver.DirectoryResolver;
import me.x150.j2cc.tree.resolver.Resolver;

import java.nio.file.Path;

public class DirectoryInputProvider implements InputProvider {

	private final Path rootPath;

	public DirectoryInputProvider(Path rootPath) {
		this.rootPath = rootPath;
	}

	@Override
	public Path getFile(String path) {
		return rootPath.resolve(path);
	}

	@Override
	public Resolver toResolver() {
		return new DirectoryResolver(rootPath);
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
