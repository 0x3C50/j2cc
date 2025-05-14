import j2cc.Nativeify;

public class Test {
	@Nativeify
	public static void main(String[] args) {
		try {
			throw new RuntimeException("Test 123");
		} catch (ArithmeticException a) {
			System.out.println("arithmetic (wrong): " + a.getMessage());
		} catch (RuntimeException re) {
			System.out.println("runtime exception (correct): " + re.getMessage());
		}

		try {
			int i = 1 / 0;
		} catch (ArithmeticException a) {
			System.out.println("arithmetic (correct): " + a.getMessage());
		} catch (RuntimeException re) {
			System.out.println("runtime exception (wrong): " + re.getMessage());
		}

		try {
			try {
				int i = 1 / 0;
			} catch (RuntimeException re) {
				System.out.println("inner (correct): " + re.getMessage());
				throw re;
			}
		} catch (ArithmeticException e) {
			System.out.println("outer (also correct): " + e.getMessage());
		}

		try {
			System.out.println("try body does not throw (correct)");
		} finally {
			System.out.println("finally still runs (correct)");
		}

		try {
			try {
				int i = 1 / 0;
			} catch (RuntimeException re) {
				System.out.println("inner RuntimeException (correct): " + re.getMessage());
				throw re;
			}
		} catch (IllegalArgumentException e) {
			System.out.println("outer IllegalArgument (wrong): " + e.getMessage());
		} finally {
			System.out.println("finally executed (correct)");
		}
	}
}