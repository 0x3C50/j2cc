package me.x150.j2cc.obfuscator.strings;

import j2cc.Nativeify;

import java.lang.invoke.MethodHandles;

public final class StringDecoder {

	@Nativeify
	public static String idx(MethodHandles.Lookup lk, String e, Class<?> sc, String s, long l) {
		if (sc != String.class) throw new IllegalArgumentException();
		int a = (int) (l >> 32);
		int b = (int) (l & Integer.MAX_VALUE);
		char[] c = s.toCharArray();
		for (int i = 0; i < c.length; i++) {
			c[i] = (char) (c[i] ^ i * a ^ b);
		}
		return new String(c);
	}

	@Nativeify
	public static String key(MethodHandles.Lookup lk, String e, Class<?> sc, String s, String k) {
		if (sc != String.class) throw new IllegalArgumentException();
		char[] key = k.toCharArray();
		char[] data = s.toCharArray();

		for (int i = 0; i < data.length; i++) {
			data[i] = (char) (key[i] ^ data[i]);
		}

		return new String(data);
	}
}