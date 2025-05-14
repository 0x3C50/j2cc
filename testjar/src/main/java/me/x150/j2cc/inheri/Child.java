package me.x150.j2cc.inheri;

public class Child extends Parent {
	@Override
	public String get() {
		return "child, " + super.get();
	}
}
