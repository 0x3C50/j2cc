package me.x150.j2cc.tree;

import dev.xdark.jlinker.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public record AsmClassInfo(Workspace tree, Workspace.ClassInfo me) implements ClassModel<AsmMethodInfo, AsmFieldInfo> {
	@Override
	public @NotNull String name() {
		return me.node().name;
	}

	@Override
	public int accessFlags() {
		return me.node().access;
	}

	@Override
	public @Nullable ClassModel<AsmMethodInfo, AsmFieldInfo> superClass() {
		String sp = me.node().superName;
		if (sp == null) return null;
		Workspace.ClassInfo classInfo = tree.get(sp);
		return new AsmClassInfo(tree, classInfo);
	}

	@Override
	public @NotNull @Unmodifiable Iterable<? extends @NotNull ClassModel<AsmMethodInfo, AsmFieldInfo>> interfaces() {
		List<String> itfs = me.node().interfaces;
		return itfs.stream().map(it -> new AsmClassInfo(tree, tree.get(it))).toList();
	}

	@Override
	public @Nullable AsmMethodInfo findMethod(@NotNull String name, @NotNull MethodDescriptor descriptor) {
		List<MethodNode> methods = me.node().methods;
		String descriptorString = descriptor.toString();
		for (MethodNode m : methods) {
			if (name.equals(m.name) && descriptorString.equals(m.desc)) {
				return new AsmMethodInfo(this, m);
			}
		}
		return null;
	}

	@Override
	public @Nullable AsmFieldInfo findField(@NotNull String name, @NotNull FieldDescriptor descriptor) {
		List<FieldNode> methods = me.node().fields;
		String descriptorString = descriptor.toString();
		for (FieldNode m : methods) {
			if (name.equals(m.name) && descriptorString.equals(m.desc)) {
				return new AsmFieldInfo(this, m);
			}
		}
		return null;
	}
}
