package me.x150.j2cc.cppwriter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SourceBuilder implements Printable {
	private final List<Printable> members = new CopyOnWriteArrayList<>();
	private final List<Printable> top = new CopyOnWriteArrayList<>();

	//	@Nativeify
	public Method method(String flags, String ret, String name, String... params) {
		Method e = new Method(flags, ret, name, params);
		members.add(e);
		return e;
	}

	//	@Nativeify
	public void include(String name, boolean local) {
		top.add(new Include(name, local));
	}

	//	@Nativeify
	public void global(String p, Object... params) {
		top.add(Printable.formatted(p, params));
	}

	public void addTop(Printable p) {
		top.add(p);
	}

	@Override
	public String stringify() {
		StringBuilder sb = new StringBuilder();
		for (Printable printable : top) {
			sb.append(printable.stringify()).append("\n");
		}
		for (Printable member : members) {
			sb.append(member.stringify()).append("\n");
		}
		sb.deleteCharAt(sb.length()-1);
		return sb.toString();
	}
}
