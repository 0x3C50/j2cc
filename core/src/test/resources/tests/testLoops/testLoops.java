@j2cc.Nativeify
public class Test {
	public static void main(String[] args) {
		int i;
		for (i = 0; i < 10; i++) {
			System.out.printf("i = %d%n", i);
		}
		System.out.printf("end: i = %d%n", i);
		while (i > 0) {
			System.out.printf("i = %d%n", i--);
		}
		System.out.printf("end: i = %d%n", i);
		for (int v = 0; v < 60; v++) {
			if (v % 5 != 0) continue;
			if (v % 3 == 0) continue;
			System.out.printf("v = %d%n", v);
		}
		out:
		for (int e = 1; e < 8; e++) {
			for (int v = 1; v < e; v++) {
				if (v % 5 == 0) continue out;
				System.out.printf("%d %d%n", e, v);
			}
		}
	}
}
