import j2cc.Nativeify;

@Nativeify
public class Test {
	static int real() {
		try {
			System.out.println("returning in real");
			return 123;
		} finally {
			System.out.println("finally executed");
		}
	}

	public static void main(String[] args) {
		try {
			System.out.println("abc");
			throw new RuntimeException("abc");
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			System.out.println("finally");
		}
		System.out.println(real());
	}
}