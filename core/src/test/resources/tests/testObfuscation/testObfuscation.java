import j2cc.AlwaysInline;
import j2cc.Nativeify;

@Nativeify
public class Test {
	String msg;

	public Test(String msg) {
		this.msg = msg;
	}

	@AlwaysInline
	private static void printStacktrace() {
		System.out.println("We're currently in:");
		new Throwable().printStackTrace();
	}

	@AlwaysInline
	private static String test(String s) {
		return "hello " + s;
	}

	//	@Nativeify
	public static void main(String[] args) {
		System.out.printf("hello %s%n", "varargs");
		System.out.println("hello simple print");

		System.out.println(test("inlining"));

		printStacktrace();

		new Test("hello world").pri();

		System.out.println(Integer.valueOf(0));
		System.out.println(Integer.valueOf(0) == Integer.valueOf(0));
		System.out.println(Integer.valueOf(-128) == Integer.valueOf(-128));
		System.out.println(Integer.valueOf(127) == Integer.valueOf(127));
		System.out.println(Integer.valueOf(-129) == Integer.valueOf(-129));
		System.out.println(Integer.valueOf(128) == Integer.valueOf(128));

		System.out.println(Character.valueOf((char) 127) == Character.valueOf((char) 127));
		System.out.println(Character.valueOf((char) 128) == Character.valueOf((char) 128));
		System.out.println(Character.valueOf((char) 123));
	}

	@AlwaysInline
	void pri() {
		printStacktrace();
		System.out.println("Yep: " + msg);
	}
}