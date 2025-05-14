import j2cc.Nativeify;

import java.util.function.BiFunction;
import java.util.function.Consumer;

@Nativeify
public class Test {
	private static void runLambda(Runnable r) {
		r.run();
	}

	public static void main(String[] args) {
		String s = "Hello world";
		runLambda(() -> {
			System.out.println(s);
		});
		Consumer<String> printer = v -> System.out.println(v);
		printer.accept("Hello chat");

		BiFunction<String, String, String> stringConcatenator = (a, b) -> a + b;
		printer.accept(stringConcatenator.apply("Hello", "World"));
	}
}