package me.x150.j2cc.util;

import java.util.regex.Pattern;

public record ClassFilter(Pattern p) {
	public static Pattern parseGlob(String s) {
		StringBuilder tx = new StringBuilder("^");
		compilePatternInternal(s, tx);
		tx.append("$");
		return Pattern.compile(tx.toString());
	}

	public static void compilePatternInternal(String s, StringBuilder tx) {
		int length = s.length();
		StringBuilder stringToQuote = new StringBuilder();
		for (int i = 0; i < length; i++) {
			char chr = s.charAt(i);
			if (chr == '*') {
				if (!stringToQuote.isEmpty()) {
					tx.append(Pattern.quote(stringToQuote.toString()));
					stringToQuote = new StringBuilder();
				}
				int level = 0;
				while (i < length && s.charAt(i) == '*') {
					level++;
					i++;
				}
				i--; // backtrack once to get back into the regular for loop cycle
				if (level == 1) {
					tx.append("[^/]*?");
				} else if (level == 2) {
					tx.append(".*?");
				} else {
					throw new IllegalStateException("Unknown star formation '" + "*".repeat(level) + "'");
				}
			} else if (chr == '?') {
				if (!stringToQuote.isEmpty()) {
					tx.append(Pattern.quote(stringToQuote.toString()));
					stringToQuote = new StringBuilder();
				}
				// one character that cant cross boundaries
				tx.append("[^/]");
			} else {
				stringToQuote.append(chr);
			}
		}
		if (!stringToQuote.isEmpty()) {
			tx.append(Pattern.quote(stringToQuote.toString()));
		}
	}

	public static ClassFilter fromString(String s) {
		return new ClassFilter(parseGlob(s.replace('.', '/')));
	}

	public boolean matches(String internalName) {
		return p.matcher(internalName).matches();
	}
}
