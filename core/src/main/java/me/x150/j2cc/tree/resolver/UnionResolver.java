package me.x150.j2cc.tree.resolver;

import org.objectweb.asm.tree.ClassNode;

import java.util.Arrays;
import java.util.stream.Collectors;

public class UnionResolver extends Resolver {

	private final Resolver[] resolvers;

	public UnionResolver(Resolver... resolvers) {
		this.resolvers = resolvers;
	}

	@Override
	public void close() throws Exception {
		for (Resolver resolver : resolvers) {
			resolver.close();
		}
	}

	@Override
	public String toString() {
		return "Union(" + Arrays.stream(resolvers).map(Object::toString).collect(Collectors.joining(", ")) + ")";
	}

	@Override
	protected ClassNode resolveInner(String name) {
		for (Resolver resolver : resolvers) {
			ClassNode resolve = resolver.resolve(name);
			if (resolve != null) return resolve;
		}
		return null;
	}
}
