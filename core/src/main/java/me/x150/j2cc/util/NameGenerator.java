package me.x150.j2cc.util;

import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class NameGenerator {
	public static final String ALPH_LOWER = "abcdefghijklmnopqrstuvwxyz";
	public static final String ALPH_UPPER = ALPH_LOWER.toUpperCase();
	public static final String ALPH_LOWER_UPPER = ALPH_LOWER + ALPH_UPPER;
	final String dictionary;
	long counter = 0;

	public static void main(String[] args) {
		NameGenerator ng = new NameGenerator("abcd");
		for (int i = 0; i < 5000; i++) {
			ng.nextName();
		}
	}

	public String nextName() {
		long n = ++counter;
		long base = dictionary.length();
		if (n < base) {
			return String.valueOf(dictionary.charAt((int) n - 1));
		}
		int length = (int) Math.ceil(Math.log(n) / Math.log(base));
		int[] idx = new int[length];
		Arrays.fill(idx, -1);
		while (n > 0) {
			idx[idx.length - 1]++;
			n--;
			for (int i = idx.length - 1; i >= 0; i--) {
				if (idx[i] >= base) {
					idx[i - 1]++;
					idx[i] = 0;
				}
			}
		}
		return Arrays.stream(idx)
				.filter(value -> value != -1)
				.mapToObj(value -> String.valueOf(dictionary.charAt(value)))
				.collect(Collectors.joining());
	}
}
