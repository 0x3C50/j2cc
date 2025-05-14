package me.x150.j2cc.tree.resolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DirectoryResolver extends Resolver {

	private final Path rootPath;

	public DirectoryResolver(Path rootPath) {
		this.rootPath = rootPath;
	}

	@Override
	public void close() {
		// noop
	}

	@Override
	public String toString() {
		return "DirectoryResolver(rootPath=" + rootPath + ")";
	}

	@Override
	protected ClassNode resolveInner(String name) throws IOException {
		Path path = rootPath.resolve(name + ".class");
		if (!Files.exists(path)) return null;
		byte[] sig = new byte[4];
		try (InputStream inputStream = Files.newInputStream(path)) {
			int read = inputStream.read(sig);
			if (read != 4) return null;
			if (sig[0] != (byte) 0xCA || sig[1] != (byte) 0xFE || sig[2] != (byte) 0xBA || sig[3] != (byte) 0xBE)
				return null;
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(sig);
			inputStream.transferTo(baos);
			ClassReader cr = new ClassReader(baos.toByteArray());
			ClassNode cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_FRAMES);
			return cn;
		}
	}
}
