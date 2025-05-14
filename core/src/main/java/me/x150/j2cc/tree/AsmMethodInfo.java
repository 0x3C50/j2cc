package me.x150.j2cc.tree;

import dev.xdark.jlinker.MethodModel;
import org.objectweb.asm.tree.MethodNode;

public record AsmMethodInfo(AsmClassInfo owner, MethodNode me) implements MethodModel {

	@Override
	public int accessFlags() {
		return me.access;
	}
}
