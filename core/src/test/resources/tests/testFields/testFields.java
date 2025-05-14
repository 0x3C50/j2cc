import j2cc.Nativeify;

public class Test {
	private static String setMe = "hello world";
	private String setMe1 = "hello chat";

	@Nativeify
	public static void main(String[] args) {
		System.out.println(setMe);
		System.out.println(setMe = "hello");
		System.out.println(setMe);

		Test test = new Test();
		Test test2 = new Test();
		System.out.println(test.setMe1);
		System.out.println(test2.setMe1);
		test.setMe1 = "hello!";
		System.out.println(test.setMe1);
		System.out.println(test2.setMe1);
	}
}