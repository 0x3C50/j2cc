import j2cc.Nativeify;

@Nativeify
public class Test {
	static String what() {
		try {
			System.out.println("in try");
			return "hi";
		} finally {
			System.out.println("in finally");
			return "what";
		}
	}

	public static void main(String[] args) {
		System.out.println(what());
	}
}
