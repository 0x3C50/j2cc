package me.x150.j2cc.conf.javaconf;

import java.util.stream.Stream;

public class Util {
	public static <T> Stream<Class<? super T>> walkHierarchy(Class<T> cl) {
		Stream.Builder<Class<? super T>> real = Stream.builder();
		Class<? super T> current = cl;
		while (current != null) {
			real.accept(current);
			current = current.getSuperclass();
		}
		return real.build();
	}
}
