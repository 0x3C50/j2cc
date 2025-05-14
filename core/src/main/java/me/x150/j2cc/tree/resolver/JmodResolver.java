package me.x150.j2cc.tree.resolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.Optional;

public class JmodResolver extends Resolver {
	private final ModuleReader mr;
	private final Path path;

	public JmodResolver(ModuleReference md) throws IOException {
		mr = md.open();
		path = md.location().map(Path::of).orElse(Path.of("jmod", String.valueOf(md.descriptor().hashCode())));
	}

	@Override
	public void close() throws Exception {
		mr.close();
	}

	@Override
	public String toString() {
		return "JmodResolver(repr=" + path + ")";
	}

	@Override
	protected ClassNode resolveInner(String name) throws IOException {
		Optional<InputStream> open = mr.open(name + ".class");
		if (open.isEmpty()) return null;
		byte[] sig = new byte[4];
		try (InputStream inputStream = open.get()) {
			int read = inputStream.read(sig);
			if (read != 4) return null;
			if (sig[0] != (byte) 0xCA || sig[1] != (byte) 0xFE || sig[2] != (byte) 0xBA || sig[3] != (byte) 0xBE)
				return null;
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(sig);
			inputStream.transferTo(baos);
			ClassReader cr = new ClassReader(baos.toByteArray());
			ClassNode cn = new ClassNode();
			cr.accept(cn, 0); // keep all meta for these ones
			return cn;
		}
	}
}
