import j2cc.Nativeify;

public class Test {
	private static int echo(int e) {return e;}
	@Nativeify
	public static void main(String[] args) {
		String s = "hello";
		if (s.equals("hello")) System.out.println("hello equals hello");
		else System.out.println("hello does not equal hello");
		if (s == null) System.out.println("hello is null");
		else System.out.println("hello is not null");
		int i = echo(123);
		if (i == 123) System.out.println("123 is 123");
		else if (i == 0) System.out.println("123 is 0");
		else System.out.println("123 is " + i + ", not 123 or 0");
		System.out.println(s != null ? "tenary" : "expression");
		switch (s) {
			case "hello" -> System.out.println("switch success");
			case "no" -> System.out.println("what???");
			default -> System.out.println("oh no: " + s);
		}
		int v = 2;
		switch (echo(3*v)) {
			case 6:
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
	}
}