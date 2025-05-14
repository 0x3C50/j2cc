package me.x150.j2cc;

public class MethodSimTest {
	private static int doOp(int a, int b) {
		a ^= 123;
		a = a | b;
		if (a != 0) a = doOp(a, b);
		return a * b;
	}
	public static void main(String[] args) {
		int someLocal = doOp(10, 20);
		System.out.println(someLocal);
	}
}
