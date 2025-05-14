package me.x150.j2cc.util;

import me.x150.j2cc.tree.Remapper;
import me.x150.j2cc.tree.Workspace;
import me.x150.j2cc.tree.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemapperTest implements Opcodes {
	private static final String OBJECT = Type.getInternalName(Object.class);

	private static ClassNode createDummyClass(String name, String parent, String... itfs) {
		ClassNode cn = new ClassNode();
		cn.visit(Opcodes.V11, ACC_PUBLIC, name, null, parent, itfs);
		return cn;
	}

	private static MethodNode createDummyMethod(ClassNode cn, String name, @SuppressWarnings("SameParameterValue") String desc) {
		return (MethodNode) cn.visitMethod(ACC_PUBLIC, name, desc, null, null);
	}

	private static Workspace workspaceOf(ClassNode... cn) throws IOException {
		return new Workspace(
				cn,
				Resolver.stdlibResolver()
		);
	}

	private static Collection<Workspace.ClassInfo> getInfos(Workspace wsp, ClassNode... cn) {
		return Arrays.stream(cn).map(it -> wsp.get(it.name)).toList();
	}

	@Test
	public void testGeneral() throws Throwable {

		ClassNode ext = createDummyClass("External", OBJECT);
		MethodNode hello = createDummyMethod(ext, "hello", "()V");
		hello.access |= ACC_STATIC;

		ClassNode parent = createDummyClass("Parent", OBJECT);
		createDummyMethod(parent, "test", "()V");

		ClassNode child = createDummyClass("Child", parent.name);
		MethodNode test = createDummyMethod(child, "test", "()V");
		test.visitMethodInsn(INVOKESTATIC, ext.name, hello.name, hello.desc, false);

		Workspace workspace = workspaceOf(ext, parent, child);

		Remapper r = new Remapper(workspace, getInfos(workspace, ext, parent, child));
		r.mapMethod(new Remapper.MemberID(ext.name, hello.name, Type.getMethodType(hello.desc)), "theReal");
		r.mapClass(ext.name, "EXT");
		r.mapMethod(new Remapper.MemberID(parent.name, "test", Type.getMethodType(Type.VOID_TYPE)), "fakeMethod");

		r.print();

		List<ClassNode> remappedClasses = Stream.of(ext, parent, child)
				.map(it -> {
					ClassNode rem = new ClassNode();
					it.accept(new ClassRemapper(rem, r));
					return rem;
				})
				.toList();
		assertEquals("EXT", remappedClasses.get(0).name);
		assertEquals("Parent", remappedClasses.get(1).name);
		assertEquals("Child", remappedClasses.get(2).name);

		assertEquals("theReal", remappedClasses.get(0).methods.stream().filter(f -> !f.name.startsWith("<")).findFirst().orElseThrow().name);
		assertEquals("fakeMethod", remappedClasses.get(1).methods.stream().filter(f -> !f.name.startsWith("<")).findFirst().orElseThrow().name);
		MethodNode childFakeMethod = remappedClasses.get(2).methods.stream().filter(f -> !f.name.startsWith("<")).findFirst().orElseThrow();
		assertEquals("fakeMethod", childFakeMethod.name);
		MethodInsnNode methodInsnNode = (MethodInsnNode) childFakeMethod.instructions.get(0);
		assertEquals("EXT", methodInsnNode.owner);
		assertEquals("theReal", methodInsnNode.name);
	}
}