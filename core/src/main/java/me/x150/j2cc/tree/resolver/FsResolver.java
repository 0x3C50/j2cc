package me.x150.j2cc.tree.resolver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FsResolver extends Resolver {
	private final FileSystem fs;

	public FsResolver(FileSystem fs) {
		this.fs = fs;
	}

	@Override
	public void close() throws Exception {
		fs.close();
	}

	@Override
	public String toString() {
		return "JarResolver(" + fs + ")";
	}

	@Override
	protected ClassNode resolveInner(String name) throws IOException {
		Path path = fs.getPath(name + ".class");
		if (!Files.exists(path)) return null;
		try (SeekableByteChannel seekableByteChannel = Files.newByteChannel(path, StandardOpenOption.READ)) {
			if (seekableByteChannel.size() <= 4) return null;
			ByteBuffer sig = ByteBuffer.allocate(4);
			seekableByteChannel.read(sig);
			sig.flip();
			int anInt = sig.getInt();
			if (anInt != 0xCAFEBABE) {
				return null; // not a class
			}
			long remaining = seekableByteChannel.size() - seekableByteChannel.position();
			ByteBuffer allocate = ByteBuffer.allocate((int) remaining);
			seekableByteChannel.read(allocate);
			allocate.flip();
			byte[] array = allocate.array();
			ClassReader cr = new ClassReader(array, -4, 0);
			ClassNode cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_FRAMES);
			return cn;
		}
	}
}
