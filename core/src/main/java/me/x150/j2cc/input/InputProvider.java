package me.x150.j2cc.input;

import me.x150.j2cc.tree.resolver.Resolver;

import java.nio.file.Path;

public interface InputProvider extends AutoCloseable {
	Path getFile(String path);

	Resolver toResolver();

}
