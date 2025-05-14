package me.x150.j2cc.tree;

import me.x150.j2cc.tree.resolver.Resolver;
import me.x150.j2cc.util.Util;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Workspace implements AutoCloseable {
	final Resolver classResolver;
	final Map<String, ClassInfo> infos = new ConcurrentHashMap<>();
	private final Map<String, ClassNode> cnCache = new ConcurrentHashMap<>();

	public Workspace(Resolver classResolver) {
		this.classResolver = classResolver;
	}

	public Workspace(ClassNode[] nodes, Resolver classResolver) {
		this.classResolver = classResolver;
		// first make all preloaded classes available
		for (ClassNode node : nodes) {
			cnCache.put(node.name, node);
		}
		// then link
		for (ClassNode node : nodes) {
			infos.put(node.name, resolveUp(node));
		}
	}

	@Override
	public String toString() {
		return "Workspace{resolver=" + classResolver + "}";
	}

	private ClassNode getNode(String n) {
		return cnCache.computeIfAbsent(n, classResolver::resolve);
	}

	private ClassInfo getNodeAndResolveToInfo(String n) {
		ClassNode head = getNode(n);
		if (head == null) return null;
		return resolveUp(head);
	}

	public Collection<ClassInfo> registerAndMapExternal(Collection<ClassNode> cns) {
		for (ClassNode cn : cns) {
			cnCache.put(cn.name, cn);
		}
		List<ClassInfo> out = new ArrayList<>();
		for (ClassNode cn : cns) {
			out.add(resolveUp(cn));
		}
		return out;
	}

	public Type findCommonSupertype(Type a, Type b) {
		assert a.getSort() >= Type.ARRAY && b.getSort() >= Type.ARRAY : "findCommonSupertype is only needed on references. merge(primitiveA, primitiveB) = top";
		if (a.equals(b)) return a; // perfect!

		int aSort = a.getSort();
		int bSort = b.getSort();
		if (aSort == Type.ARRAY && bSort == Type.ARRAY) {
			// merge arrays
			int minimumDims = Math.min(a.getDimensions(), b.getDimensions());
			a = Type.getType(a.getDescriptor().substring(minimumDims));
			b = Type.getType(b.getDescriptor().substring(minimumDims));
			// one or both of a, b is/are now no longer an array. we can try merging normally
			Type commonSupertypeOfBoth = findCommonSupertype(a, b);
			return Type.getType("[".repeat(minimumDims) + commonSupertypeOfBoth.getDescriptor());
		} else if (aSort == Type.ARRAY || bSort == Type.ARRAY) {
			// one is an array, the other is not (since A&B has been ruled out, only A or B or none remain)
			// this can only result in Object, since arrays only implicitly inherit from Object
			return Util.OBJECT_TYPE;
		}
		// no elements are arrays at this point

		String type1 = a.getInternalName();
		ClassInfo cl1 = get(type1);
		String type2 = b.getInternalName();
		ClassInfo cl2 = get(type2);
		if (cl1 == null || cl2 == null || Modifier.isInterface(cl1.node().access) || Modifier.isInterface(
				cl2.node().access)) {
			return Util.OBJECT_TYPE;
		}
		List<String> firstTypeList = new ArrayList<>(cl1.hierarchyParents.stream().filter(f -> !Modifier.isInterface(f.access)).map(it -> it.name).toList());
		List<String> secondTypeList = new ArrayList<>(cl2.hierarchyParents.stream().filter(f -> !Modifier.isInterface(f.access)).map(it -> it.name).toList());

		if (firstTypeList.contains(type2)) {
			// type1 inherits from type2, use type2 as earliest common supertype
			return b;
		}
		if (secondTypeList.contains(type1)) {
			// type2 inherits from type1, read above
			return a;
		}

		// walk up the hierarchy until we find a match
		int reader = 0;
		while (true) {
			// should never overflow here assuming input is sane, hierarchyParents contains Object
			String s = firstTypeList.get(reader++);
			if (secondTypeList.contains(s)) {
				// we found an ancestor from type1 that is in the hierarchy from type2
				// return it
				return Type.getObjectType(s);
			}
		}
	}

	@NotNull
	private ClassInfo resolveUp(ClassNode head) {
		List<ClassNode> parents = new ArrayList<>();
		Set<String> dejaVu = new HashSet<>();
		Deque<String> q = new ArrayDeque<>();
		q.add(head.name);
		while (!q.isEmpty()) {
			String poll = q.poll();
			if (dejaVu.contains(poll)) continue; // already visited
			dejaVu.add(poll);
			ClassNode raw = getNode(poll);
			if (raw == null) {
				throw new IllegalStateException("Unresolved class reference " + poll + ". Path: " + dejaVu);
//				break;
			}
			if (!raw.name.equals("java/lang/Object")) {
				if (raw.superName != null) q.add(raw.superName);
				else q.add("java/lang/Object");
			}
			if (raw.interfaces != null) q.addAll(raw.interfaces);
			if (!poll.equals(head.name)) parents.add(raw);
		}
		return new ClassInfo(this, head, parents);
	}

	public ClassInfo get(String n) {
		return infos.computeIfAbsent(n, this::getNodeAndResolveToInfo);
	}

	@Override
	public void close() throws Exception {
		classResolver.close();
	}

	public record ClassInfo(Workspace in, ClassNode node, List<ClassNode> hierarchyParents) {

	}
}
