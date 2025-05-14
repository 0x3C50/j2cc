package me.x150.j2cc.cppwriter;

import me.x150.j2cc.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class SwitchCaseCodeSegment implements Printable {
	private final Printable what;
	private final List<CaseElement> cases;

	public SwitchCaseCodeSegment(Printable what) {
		this.what = what;
		cases = new ArrayList<>();
	}

	public void newCase(String w, Object... o) {
		cases.add(new CaseElement(Printable.constant(Util.fmt(w, o)), new ArrayList<>(), false));
	}

	public void add(String w, Object... o) {
		cases.getLast().body.add(Printable.constant(Util.fmt(w, o)));
	}

	public void addStmt(String w, Object... o) {
		add(w + ";", o);
	}

	public void dflt() {
		cases.add(new CaseElement(null, new ArrayList<>(), true));
	}

	@Override
	public String stringify() {
		StringBuilder sb = new StringBuilder();
		sb.append("switch (").append(what.stringify()).append(") {\n");
		for (CaseElement aCase : cases) {
			if (aCase.dflt) sb.append("    default:\n");
			else sb.append("    case ").append(aCase.what.stringify()).append(":\n");
			sb.append("        ").append(aCase.body.stream().map(Printable::stringify).flatMap(s -> Arrays.stream(s.split("\n"))).collect(Collectors.joining("\n        "))).append("\n");
		}
		sb.append("}");
		return sb.toString();
	}

	record CaseElement(Printable what, List<Printable> body, boolean dflt) {
	}
}
