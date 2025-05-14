import j2cc.Nativeify;

@Nativeify
public class Test {
	private static void thisThrows() {
		throw new RuntimeException("hello chat");
	}

	private static void doesNotCatch() {
		System.out.println("this should print");
		thisThrows();
		System.out.println("this should not be printed");
	}

	private static void doesCatch() {
		System.out.println("this should print");
		try {
			thisThrows();
		} catch (RuntimeException re) {
			re.printStackTrace();
		}
		System.out.println("this should print as well (after catch)");
	}

	public static void main(String[] args) {
		doesCatch();
		doesNotCatch();
		System.out.println("should not print");
	}
}