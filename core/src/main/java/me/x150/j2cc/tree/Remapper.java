package me.x150.j2cc.tree;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Log4j2
public class Remapper extends org.objectweb.asm.commons.Remapper {
	private final Workspace workspace;
	private final Collection<Workspace.ClassInfo> ourClasses;
	private final Map<Workspace.ClassInfo, List<Workspace.ClassInfo>> childrenClasses = new ConcurrentHashMap<>();
	@Getter
	private final Map<String, String> classMaps = new ConcurrentHashMap<>();
	@Getter
	private final Map<MemberID, String> fieldMaps = new ConcurrentHashMap<>();
	@Getter
	private final Map<MemberID, String> methodMaps = new LinkedHashMap<>();

	public Remapper(Workspace workspace, Collection<Workspace.ClassInfo> ourClasses) {
		this.workspace = workspace;
		this.ourClasses = ourClasses;
		buildIndex();
	}

	public void print() {
		log.debug("{} class mappings", classMaps.size());
		classMaps.forEach((s, s2) -> log.debug("  {} -> {}", s, s2));
		log.debug("{} field maps", fieldMaps.size());
		fieldMaps.forEach((memberID, s) -> log.debug("  {} {}.{} -> {}", memberID.type, memberID.owner, memberID.name, s));
		log.debug("{} method maps", methodMaps.size());
		methodMaps.forEach((memberID, s) -> log.debug("  {}.{}{} -> {}", memberID.owner, memberID.name, memberID.type, s));
	}

	public Type mapType(Type t) {
		return switch (t.getSort()) {
			case Type.ARRAY -> {
				Type elT = t.getElementType();
				int dims = t.getDimensions();
				yield Type.getType("[".repeat(dims) + mapType(elT).getDescriptor());
			}
			case Type.OBJECT -> {
				String internalName = t.getInternalName();
				String mapped = map(internalName);
				yield Type.getObjectType(mapped);
			}
			case Type.METHOD -> {
				Type[] argTypes = t.getArgumentTypes();
				Type ret = t.getReturnType();
				for (int i = 0; i < argTypes.length; i++) {
					argTypes[i] = mapType(argTypes[i]);
				}
				ret = mapType(ret);
				yield Type.getMethodType(ret, argTypes);
			}
			default -> t;
		};
	}

	public boolean hasMethodMapping(MemberID id) {
		return methodMaps.containsKey(id);
	}

	public String unmapClassName(String mapped) {
		return classMaps.entrySet().stream().filter(f -> f.getValue().equals(mapped)).findAny().map(Map.Entry::getKey).orElse(mapped);
	}

	private void buildIndex() {
		for (Workspace.ClassInfo ourClass : ourClasses) {
			List<Workspace.ClassInfo> childrenList = childrenClasses.computeIfAbsent(ourClass, it -> new ArrayList<>());
			if (Modifier.isFinal(ourClass.node().access)) {
				// we dont need to resolve downwards hierarchy for this; no one can extend us
				continue;
			}
			// we only search for children in our class space, since taking everything into account would take too long
			// also would be rather silly, imagine trying to obfuscate a library
			List<Workspace.ClassInfo> list = ourClasses.stream()
					.filter(f -> f.hierarchyParents().stream().anyMatch(it -> it.name.equals(ourClass.node().name)))
					.toList();
			childrenList.addAll(list);
		}
	}

	public void mapClass(String from, String to) {
		classMaps.put(from, to);
	}

	public void mapField(MemberID id, String to) {
		fieldMaps.put(id, to);
	}

	public Set<MemberID> getRelatedMethodImplementations(MemberID start) {
		Set<MemberID> s = new HashSet<>();
		Queue<MemberID> memberQueue = new ArrayDeque<>();
		memberQueue.add(start);

		while (!memberQueue.isEmpty()) {
			MemberID poll = memberQueue.poll();
			if (s.contains(poll)) continue;
			s.add(poll);
			Workspace.ClassInfo own = workspace.get(poll.owner);
			List<MemberID> a = getMethodImplAbove(own, poll.name, poll.type);
			memberQueue.addAll(a);
			List<MemberID> b = getMethodImplBelow(own, poll.name, poll.type);
			memberQueue.addAll(b);
		}
		return s;
	}

	private List<MemberID> getMethodImplBelow(Workspace.ClassInfo owner, String name, Type t) {
		List<Workspace.ClassInfo> classInfos = childrenClasses.get(owner);
		if (classInfos == null) return new ArrayList<>();
		return classInfos.stream().map(it -> new Pair<>(it, it.node().methods.stream().filter(m -> m.name.equals(name) && Type.getMethodType(m.desc).equals(t)).toList()))
				.filter(f -> !f.b.isEmpty())
				.flatMap(it -> it.b.stream().map(v -> new MemberID(it.a.node().name, v.name, Type.getMethodType(v.desc))))
				.toList();
	}

	private List<MemberID> getMethodImplAbove(Workspace.ClassInfo owner, String name, Type t) {
		List<ClassNode> classInfos = owner.hierarchyParents();
		return classInfos.stream().map(it -> new Pair<>(it, it.methods.stream().filter(m -> m.name.equals(name) && Type.getMethodType(m.desc).equals(t)).toList()))
				.filter(f -> !f.b.isEmpty())
				.flatMap(it -> it.b.stream().map(v -> new MemberID(it.a.name, v.name, Type.getMethodType(v.desc))))
				.toList();
	}

	public void mapMethod(MemberID id, String to) {
		String name = id.name;
		if (name.startsWith("<")) throw new IllegalArgumentException("special method " + name);
		if (methodMaps.containsKey(id)) throw new IllegalStateException("already mapped");
		Workspace.ClassInfo owner = workspace.get(id.owner);
		MethodNode methodNode = owner.node().methods.stream().filter(f -> f.name.equals(id.name) && Type.getMethodType(f.desc).equals(id.type)).findAny().orElseThrow();
		if (Modifier.isStatic(methodNode.access)) {
			// no reason to walk inheritance tree
			methodMaps.put(id, to);
		} else {
			// need to walk inheritance
			for (MemberID relatedMethodImplementation : getRelatedMethodImplementations(id)) {
				if (methodMaps.containsKey(relatedMethodImplementation))
					throw new IllegalStateException("Partially mapped inheritance tree: " + relatedMethodImplementation + " already has a mapping (to " + methodMaps.get(relatedMethodImplementation) + ")");
				methodMaps.put(relatedMethodImplementation, to);
			}
		}
	}

	@Override
	public String map(String internalName) {
		return classMaps.getOrDefault(internalName, internalName);
	}

	@Override
	public String mapMethodName(String owner, String name, String descriptor) {
		if (owner.startsWith("[")) {
			return name;
		}
		if (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle")) {
			// most likely polymorphic, we dont have mappings for those anyway
			return name;
		}
		Workspace.ClassInfo ownerClass = workspace.get(owner);
		if (ownerClass == null) {
//			log.error("Can't find class {} (mapping method {}.{}{})", owner, owner, name, descriptor);
			return name;
		}
		Optional<ClassNode> firstDeclaringNode = Stream.concat(Stream.of(ownerClass.node()), ownerClass.hierarchyParents().stream())
				.filter(f -> f.methods.stream().anyMatch(e -> e.name.equals(name) && e.desc.equals(descriptor)))
				.findFirst();
		if (firstDeclaringNode.isEmpty()) {
			// could happen if method is polymorphic, but the only classes that have polymorphic descs are already filtered out by now
			// genuine issue
			log.error("Can't find declaring member of {}.{}{}", owner, name, descriptor);
			return name;
		}
		ClassNode classNode = firstDeclaringNode.orElseThrow();
		MemberID m = new MemberID(classNode.name, name, Type.getMethodType(descriptor));
		String s = methodMaps.get(m);
		if (s == null) return name;
		return s;
	}

	@Override
	public String mapAnnotationAttributeName(String descriptor, String name) {
		Type t = Type.getType(descriptor);
		if (t.getSort() != Type.OBJECT) return name;
		descriptor = t.getInternalName();
		Workspace.ClassInfo classInfo = workspace.get(descriptor);
		if (classInfo == null) {
//			log.error("Can't find class {} (mapping annotation attribute {} {})", descriptor, descriptor, name);
			return name;
		}
		Optional<MethodNode> first = classInfo.node().methods
				.stream().filter(f -> f.name.equals(name) && Type.getArgumentCount(f.desc) == 0).findFirst();
		if (first.isPresent()) {
			MethodNode methodNode = first.get();
			String s = methodMaps.get(new MemberID(descriptor, methodNode.name, Type.getMethodType(methodNode.desc)));
			if (s != null) return s;
		}
		return name;
	}

	@Override
	public String mapFieldName(String owner, String name, String descriptor) {
		Workspace.ClassInfo ownerClass = workspace.get(owner);
		if (ownerClass == null) return super.mapFieldName(owner, name, descriptor);
		Optional<ClassNode> firstDeclaringNode = Stream.concat(Stream.of(ownerClass.node()), ownerClass.hierarchyParents().stream())
				.filter(f -> f.fields.stream().anyMatch(e -> e.name.equals(name) && e.desc.equals(descriptor)))
				.findFirst();
		if (firstDeclaringNode.isEmpty()) {
			log.error("THE FUCK???????? {}.{}{}", owner, name, descriptor);
			return name;
		}
		ClassNode classNode = firstDeclaringNode.get();
		MemberID real = new MemberID(classNode.name, name, Type.getType(descriptor));
		String s = fieldMaps.get(real);
		if (s == null) return name;
		else return s;
	}

	public record MemberID(String owner, String name, Type type) {
	}
}
