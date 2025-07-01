package me.x150.j2cc;

import j2cc.Exclude;
import j2cc.Nativeify;
import me.x150.j2cc.inheri.Parent;
import me.x150.j2cc.inheri.SecondChild;

import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Scanner;

//@Nativeify
public class Main {
	static void real() {
		System.out.println(new String(Math.random() == 0.5 ? new char[] {
				't', 'h', 'e', 'o', 'n', 'e'
		} : new char[] {'t', 'h', 'e', 't', 'w', 'o'}));

//		Object o = "abc";
//		switch (o) {
//			case String s when s.equals("guh") -> System.out.println("pluh");
//			case Integer i -> System.out.println("integer");
//			default -> System.out.println("default");
//		}
	}
	@Exclude(Exclude.From.COMPILATION)
	public static void hooked(String dli_fname, long dli_fbase, String dli_sname, long dli_saddr) {
		System.out.printf("go ahead and unload %s for me, won't you? mounted at %d. %s %d\n", dli_fname, dli_fbase, dli_sname, dli_saddr);
		System.exit(1);
	}

	static int getA() {
		return 54839;
	}
	static int getB() {
		return 4890;
	}
	static int c() {
		return Math.max(getA() ^ getB(), getB() & getA());
	}
	static int getLengthButFunny(Object[] guh) {
		return guh.length;
	}
//		@Exclude({Exclude.From.OBFUSCATION, Exclude.From.COMPILATION})
	public static void main(String[] args) throws Throwable {

		System.out.println("hi!!!!");

		MethodHandles.Lookup lookup = MethodHandles.lookup();
		MethodHandle printfMethod = lookup.findVirtual(PrintStream.class, "printf", MethodType.methodType(PrintStream.class, String.class, Object[].class));
		printfMethod.invoke(System.out, "this is a message: %s %d %09.2f%n", "hi", 123, 123.45678f);
		Object[] pfArgs = {"hi", 123, 123.45678f};
		printfMethod.asFixedArity().invoke(System.out, "this is a message: %s %d %09.2f%n", pfArgs);

		testappending();

		System.out.println(c());

		System.out.println(getLengthButFunny(new Object[234]));
		System.out.println(getLengthButFunny(new Object[234]));

		System.out.println(0.69420f);
		System.out.println(420.696969d);
		System.out.println(Double.MAX_VALUE);
		System.out.println(Double.MIN_VALUE);

		System.out.println(123);
		System.out.println(234);
		System.out.println(345);
		System.out.println(0x7fffffff);
		System.out.println(0x80000000);
		System.out.println(0x7fffffffffffffffL);
		System.out.println(0x8000000000000000L);

//		if (true) return;

		real();
		long l2 = 124093437565057L;
		long l3 = l2 ^ 134019679364572L;
		long l4 = l2 ^ 56034417067134L;

		System.out.println(l2);
		System.out.println(l3);
		System.out.println(l4);

		String x = "hello";
		if (x.equals("hello")) System.out.println("hello equals hello");
		else System.out.println("hello does not equal hello");
		if (x == null) System.out.println("hello is null");
		else System.out.println("hello is not null");
		int i = 123;
		if (i == 123) System.out.println("123 is 123");
		else if (i == 0) System.out.println("123 is 0");
		else System.out.println("123 is " + i + ", not 123 or 0");
		System.out.println(x != null ? "tenary" : "expression");
		switch (x) {
			case "hello" -> System.out.println("switch success");
			case "no" -> System.out.println("what???");
			default -> System.out.println("oh no: " + x);
		}
		switch (3) {
			case 3:
				System.out.println("correct!");
			case 4:
				System.out.println("also correct");
				break;
			case 2:
				System.out.println("NOT correct!");
				break;
			case 1:
				System.out.println("even less correct");
				break;
			default:
				System.out.println("oh god");
		}

		Parent ptr = new SecondChild();
		System.out.println(ptr.get());
		Scanner sc = new Scanner(System.in);
		System.out.println("paste any stacktrace, end input with 'END'");
		StringBuilder st = new StringBuilder();
		String line;
		while (!(line = sc.nextLine()).equalsIgnoreCase("end")) {
			st.append(line).append("\n");
		}
		String full = st.toString();
		System.out.println("Stacktrace to remap:\n\n" + full + "\nRemapping...");
		try {
			StackTranslator transl = new StackTranslator(
					s -> s + "RemappedClass",
					descriptor -> descriptor.methodName() + "RemappedMethod"
			);
			StackTranslator.ExceptionInfo exceptionInfo = transl.parseAndRemapStacktrace(full);
			System.out.println("Remapped stacktrace:");
			System.out.println(exceptionInfo.toString());
		} catch (Throwable t) {
			System.out.println("ufck");
			t.printStackTrace(System.err);
		}
	}

	@Nativeify
	private static void testappending() {
		String a = new String(new char[] {'h', 'i'});
		System.out.println("j"+a+"Arr0");
	}

	private static final StringBuilder theFUnny = new StringBuilder("hello chat");

	static {
		System.out.println("class initialized: "+theFUnny);
	}

}