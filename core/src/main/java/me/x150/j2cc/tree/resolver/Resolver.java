package me.x150.j2cc.tree.resolver;

import lombok.SneakyThrows;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.List;

public abstract class Resolver implements AutoCloseable {
	private static Resolver stdlibRes;

	public static Resolver stdlibResolver() throws IOException {
		if (stdlibRes == null) {
			List<Resolver> jarResolvers = new ArrayList<>();
			for (ModuleReference moduleReference : ModuleFinder.ofSystem().findAll()) {
				jarResolvers.add(new JmodResolver(moduleReference));
			}
			stdlibRes = new UnionResolver(jarResolvers.toArray(Resolver[]::new));
		}
		return stdlibRes;
	}

	@SneakyThrows
	public ClassNode resolve(String name) {
		return resolveInner(name);
	}

	protected abstract ClassNode resolveInner(String name) throws IOException;
}
