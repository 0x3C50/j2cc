package me.x150.j2cc.cppwriter;

import lombok.Getter;
import me.x150.j2cc.compiler.MemberCache;
import me.x150.j2cc.util.Util;
import org.intellij.lang.annotations.Language;

import java.util.*;
import java.util.stream.Stream;

public class Method implements Printable {
	public final String name;
	final List<String> varDefs = new ArrayList<>();
	final List<Printable> head = new ArrayList<>();
	final List<Printable> lines = new ArrayList<>();
	@Getter
	private final String[] params;
	@Getter
	private final String returns;
	@Getter
	private final String flags;
	int cflow = 0;

	public record Scope(Map<String, String> currentClassDefs, Map<String, MemberCache.Descriptor> currentMethodDefs,
						Map<String, MemberCache.Descriptor> currentFieldDefs) {}

	// initial scope
	public final Deque<Scope> scopes = new ArrayDeque<>(List.of(new Scope(new HashMap<>(), new HashMap<>(), new HashMap<>())));

	public void noteClassDef(String var, String clazz) {
		// note the current definition in the current scope
		if (clazz == null) scopes.peek().currentClassDefs().remove(var);
		else scopes.peek().currentClassDefs().put(var, clazz);
		// since this scope MIGHT be entered conditionally, we cannot assume the outer variable is still the same
		// it is now Either(previous, clazz)
		// since we cannot do anything useful with that information, we nuke it from the previous defs,
		// for it to be redefined when needed
		Iterator<Scope> iterator = scopes.iterator();
		iterator.next();
		while (iterator.hasNext()) {
			Scope next = iterator.next();
			next.currentClassDefs.remove(var);
		}
		generateScopeComment(true);
	}

	public void noteMethodDef(String var, MemberCache.Descriptor def) {
		// note the current definition in the current scope
		scopes.peek().currentMethodDefs().put(var, def);
		// since this scope MIGHT be entered conditionally, we cannot assume the outer variable is still the same
		// it is now Either(previous, def)
		// since we cannot do anything useful with that information, we nuke it from the previous defs,
		// for it to be redefined when needed
		Iterator<Scope> iterator = scopes.iterator();
		iterator.next();
		while (iterator.hasNext()) {
			Scope next = iterator.next();
			next.currentMethodDefs.remove(var);
		}
		generateScopeComment(true);
	}

	public void noteFieldDef(String var, MemberCache.Descriptor def) {
		// note the current definition in the current scope
		scopes.peek().currentFieldDefs().put(var, def);
		// since this scope MIGHT be entered conditionally, we cannot assume the outer variable is still the same
		// it is now Either(previous, def)
		// since we cannot do anything useful with that information, we nuke it from the previous defs,
		// for it to be redefined when needed
		Iterator<Scope> iterator = scopes.iterator();
		iterator.next();
		while (iterator.hasNext()) {
			Scope next = iterator.next();
			next.currentFieldDefs.remove(var);
		}
		generateScopeComment(true);
	}


	public Method(String flags, String returns, String name, String... params) {
		this.flags = flags;
		this.params = params;
		this.returns = returns;
		this.name = name;
	}

	public void add(@Language("C++") String s, Object... params) {
		lines.add(Printable.constant("    ".repeat(cflow) + Util.fmt(s, params)));
	}

	public void addHead(@Language("C++") String s, Object... params) {
		head.add(Printable.constant(Util.fmt(s, params)));
	}

	public void addStatementHead(@Language("C++") String s, Object... params) {
		addHead(s + ";", params);
	}

	public void addStatement(@Language(value = "C++", suffix = ";") String s, Object... params) {
		add(s + ";", params);
	}

	public void beginScope(@Language(value = "C++", suffix = " {") String condition, Object... params) {
		if (condition.isEmpty()) {
			add("{", params);
		} else {
			add(condition + " {", params);
		}
		scopes.push(new Scope(new HashMap<>(), new HashMap<>(), new HashMap<>()));
		cflow++;
		generateScopeComment(false);
	}

	public void endScope() {
		cflow--;
		add("}");
		scopes.pop();
		generateScopeComment(false);
	}

	private void generateScopeComment(boolean lite) {
		if (lite) {
			dumpScope(scopes.peek(), scopes.size()-1);
		} else {
			int sc = 0;
			for (Scope scope : scopes.reversed()) {
				dumpScope(scope, sc);
				sc++;
			}
		}
	}

	private void dumpScope(Scope scope, int sc) {
//		comment("scope "+ sc +":");
//		scope.currentClassDefs.forEach((s, s2) -> comment("  "+s+" := C "+s2));
//		scope.currentMethodDefs.forEach((s, descriptor) -> comment("  "+s+" := M "+descriptor));
//		scope.currentFieldDefs.forEach((s, descriptor) -> comment("  "+s+" := F "+descriptor));
	}

	public void clearScopes() {
		for (Scope scope : scopes) {
			scope.currentMethodDefs.clear();
			scope.currentClassDefs.clear();
			scope.currentFieldDefs.clear();
		}
	}

	public void scopeElse() {
		cflow--;
		add("} else {");
		cflow++;
	}


	public void comment(@Language(value = "C++", prefix = "// ") String text) {
		for (String s : text.split("\n")) {
			add("// $l", s);
		}
	}

	public void addP(Printable s) {
		lines.add(s);
	}

	public VarDefinitionCtx local(String type, String name) {
		String v = type + " " + name + ";";
		if (!varDefs.contains(v)) {
			varDefs.add(v);
			head.add(Printable.constant(v));
		}
		return new VarDefinitionCtx(name);
	}

	public VarDefinitionCtx localInitialValue(String type, String name, @Language("C++") String initial) {
		String v = type + " " + name + " = " + initial + ";";
		if (!varDefs.contains(v)) {
			varDefs.add(v);
			head.add(Printable.constant(v));
		}
		return new VarDefinitionCtx(name);
	}

	@Override
	public String stringify() {
		if (cflow != 0) throw new IllegalStateException("Unclosed scope");
		String head = (flags != null ? flags + " " : "") + returns + " " + name + "(" + String.join(", ", params) + ")";
		if (lines.isEmpty()) return head + ";";
		StringBuilder sb = new StringBuilder(head);
		sb.append(" {");
		Stream.concat(this.head.stream(), this.lines.stream())
				.map(Printable::stringify)
				.flatMap(p -> Arrays.stream(p.split("\n")))
				.forEach(s -> sb.append("\n    ").append(s));
		sb.append("\n}");
		return sb.toString();
	}

	public class VarDefinitionCtx {
		final String n;

		private VarDefinitionCtx(String n) {
			this.n = n;
		}

		public void initStmt(@Language("C++") String s, Object... p) {
			Method.this.addStatement(n + " = " + s, p);
		}
	}

}
