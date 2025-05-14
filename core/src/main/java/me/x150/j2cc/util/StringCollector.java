package me.x150.j2cc.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import lombok.SneakyThrows;
import me.x150.j2cc.util.natives.chacha20_h;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

public class StringCollector {
	private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
	private final Object2IntMap<String> cache = new Object2IntOpenHashMap<>();
	public int reserveString(String s) {
		synchronized (cache) {
			return cache.computeIfAbsent(s, this::createStringIndex);
		}
	}

	@SneakyThrows
	private synchronized int createStringIndex(String s) {
		int current = baos.size();
		baos.write(Util.encodeMUTF(s));
		baos.write(0);
		return current;
	}

	public void writeEncryptedPoolTo(OutputStream os, byte[] key) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment keyData = arena.allocateFrom(chacha20_h.C_CHAR, key);
			int nElements = baos.size();
			long n = Math.ceilDiv(nElements, 64L);
			MemorySegment chachaBuffer = arena.allocate(chacha20_h.C_CHAR, n * 64L);
			chacha20_h.fuckMyShitUp(keyData, chachaBuffer, n);
			byte[] array = chachaBuffer.toArray(chacha20_h.C_CHAR);
			byte[] byteArray = baos.toByteArray();
			for (int i = 0; i < byteArray.length; i++) {
				os.write(byteArray[i] ^ array[i]);
			}
		}
	}

	public int size() {
		return baos.size();
	}
}
