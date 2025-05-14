@j2cc.Nativeify
public class Test {
	public static void main(String[] args) {
		String s = "hello chat";
		System.out.println(s);
		System.out.println(s.length());
		System.out.println(s.isEmpty());
		System.out.println(s.equals("goodbye chat"));

		System.out.println("".length());
		System.out.println(" ".length());

		System.out.println("".isEmpty());
		System.out.println(" ".isEmpty());

		System.out.println("".equals(" "));

		System.out.println("\u9986äöü\uFFFF\uFFFE\u0000\u48FEhello world");
	}
}
