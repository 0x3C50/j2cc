package me.x150.j2cc.clSplit;

public class C extends B {
	@Override
	protected String doStuff() {
		return "C";
	}

	public static void main(String[] args) {
		B bruh = new C();
		System.out.println(bruh.doStuff());
	}
}
