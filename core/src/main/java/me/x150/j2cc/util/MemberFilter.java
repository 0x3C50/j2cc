package me.x150.j2cc.util;

import me.x150.j2cc.conf.Configuration;
import org.objectweb.asm.Type;

import java.util.regex.Pattern;

public record MemberFilter(ClassFilter cf, Pattern name, Pattern type) {
	public static void main(String[] args) {
		Pattern pdx = parseDescriptorGlob("(IILjava.**/String;?)?");
		System.out.println(pdx);
		System.out.println(pdx.matcher("(IILjava/lang/String;D)V").matches());
		System.out.println(pdx.matcher("(IILjava/lang/aaa/String;J)V").matches());
		System.out.println(pdx.matcher("(IILjava/lguh/String;D)V").matches());
		System.out.println(pdx.matcher("(IILjava/lguh/String;)V").matches());
		System.out.println(pdx.matcher("(IILjava/lguh/String;D)Ljava/lang/Guh;").matches());
		Pattern real = parseDescriptorGlob("?");
		System.out.println(real);
		System.out.println(real.matcher("Ljava/lang/String;").matches());
		System.out.println(real.matcher("I").matches());
	}

	public static MemberFilter fromFilter(Configuration.Member f) {
		ClassFilter classFilter = ClassFilter.fromString(f.getClazz());
		Pattern name = ClassFilter.parseGlob(f.getMemberName());
		Pattern type = parseDescriptorGlob(f.getDescriptor());
		return new MemberFilter(classFilter, name, type);
	}

	private static int parseType(StringBuilder tx, String s, int index, char currentChar) {
		return switch (currentChar) {
			case '[' -> {
				int nDims = 0;
				int j;
				for(j = index; j < s.length(); j++) {
					if (s.charAt(j) == '[') {
						nDims++;
					} else break;
				}
				if (nDims <= 2) tx.append("\\[".repeat(nDims));
				else tx.append(Pattern.quote("[".repeat(nDims)));
				yield parseType(tx, s, j, s.charAt(j)) + nDims;
			}
			case 'L' -> {
				tx.append("L");
				// class ref, read until ;
				int endOfRef = s.indexOf(';', index);
				// for this one, normal type glob rules apply
				StringBuilder stringToQuote = new StringBuilder();
				for (int i = index + 1; i < endOfRef; i++) {
					char chr = s.charAt(i);
					if (chr == '*') {
						if (!stringToQuote.isEmpty()) {
							tx.append(Pattern.quote(stringToQuote.toString()));
							stringToQuote = new StringBuilder();
						}
						int level = 0;
						while (i < endOfRef && s.charAt(i) == '*') {
							level++;
							i++;
						}
						i--; // backtrack once to get back into the regular for loop cycle
						if (level == 1) {
							// one part *, allow one name element to be anything but not / or ;
							tx.append("[^/;]*?");
						} else if (level == 2) {
							// two part *, allow one name element to be anything including / but not ;
							tx.append("[^;]*?");
						} else {
							throw new IllegalStateException("Unknown star formation '" + "*".repeat(level) + "'");
						}
					} else if (chr == '?') {
						if (!stringToQuote.isEmpty()) {
							tx.append(Pattern.quote(stringToQuote.toString()));
							stringToQuote = new StringBuilder();
						}
						// one character that cant cross boundaries
						tx.append("[^/;]");
					} else {
						stringToQuote.append(chr == '.' ? '/' : chr);
					}
				}
				if (!stringToQuote.isEmpty()) {
					tx.append(Pattern.quote(stringToQuote.toString()));
				}
				tx.append(";");
				yield (endOfRef + 1) - index; // skip over ; to next element
			}
			// any type
			// either any prim type (incl. V, see case below for reason), or L<anything>;
			case '?' -> {
				tx.append("([ZCBSIFJDV]|L.+?;)");
				yield 1;
			}
			// prim type, just add as regular
			case 'Z', 'B', 'C', 'S', 'I', 'F', 'J', 'D',
				 'V' /* technically not allowed but we'll do it anyway for CQ */ -> {
				tx.append(currentChar);
				yield 1;
			}
			default -> throw new IllegalStateException("Unknown type character " + currentChar);
		};
	}

	public static Pattern parseDescriptorGlob(String s) {
		StringBuilder tx = new StringBuilder("^");
		char firstChar = s.charAt(0);
		if (firstChar == '(') {
			tx.append("\\(");
			// method descriptor
			char currentChar;
			int index = 1;
			while ((currentChar = s.charAt(index)) != ')') {
				index += parseType(tx, s, index, currentChar);
			}
			tx.append("\\)");
			// parse return value
			// +1: skip over ending )
			parseType(tx, s, index + 1, s.charAt(index + 1));
		} else {
			// field descriptor
			parseType(tx, s, 0, firstChar);
		}
		tx.append("$");
		return Pattern.compile(tx.toString());
	}

	public boolean matches(String cl, String name, Type type) {
		return cf.matches(cl) && this.name.matcher(name).matches() && this.type.matcher(type.getDescriptor()).matches();
	}
}
