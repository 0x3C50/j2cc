package me.x150.j2cc.cppwriter;

import me.x150.j2cc.util.Util;

public interface Printable {
	static Printable constant(String s) {
		return () -> s;
	}

	static Printable formatted(String s, Object... o) {
		return () -> Util.fmt(s, o);
	}

	String stringify();
}
